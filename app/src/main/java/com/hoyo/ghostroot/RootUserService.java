package com.hoyo.ghostroot;

import android.content.Context;
import android.os.Process;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs inside Shizuku's UserService process: uid 2000 (SHELL_UID), Seccomp=0.
 *
 * Flow is a byte-for-byte match of the PC-side autoroot.sh that succeeded 6/6:
 *  - ONE full attack per boot. Never retry the exploit on a polluted heap
 *    (a second run after a failed attempt is the reboot/wedge scenario).
 *  - No `am kill-all` (the proven PC flow never kills vivo services).
 *  - 240s hard timeout on the CLI, same as the PC `timeout 240` cap.
 *  - fire.sh watcher armed once, before the attack.
 */
public class RootUserService extends IRootRunner.Stub {

    private static final long CLI_TIMEOUT_SECONDS = 240;
    /** rounds per Shizuku session (one boot) — mirrors PC autoroot's cycle loop. */
    private static final int MAX_ROUNDS = 10;
    /** PC rhythm: ~20s cooldown between cycles. */
    private static final long ROUND_COOLDOWN_SECONDS = 20;
    /** Log analysis (21 attacks, 2026-10-30): 0/11 writes landed when the
     * attack started <100s after boot; 3/3 landed at >=102s. Boot-time service
     * churn keeps the heap unstable during the first minute — wait it out. */
    /** Log analysis initially suggested >=100s, but that sample was thermally
     * confounded. iqoo-neo9-ghostlock-pc field wisdom + our own morning 6/6
     * (fired at 30-60s) say fire as early as the heap allows. */
    private static final float MIN_START_UPTIME_SECONDS = 32f;
    /** Thermal guard: the exploit is a futex/pselect timing race; a heat-soaked
     * SoC (47°C observed) throttles clocks and mistimes the race -> route wedge.
     * Cool device (morning) = 6/6; hot device (afternoon) = ~2/3 resets. */
    private static final int HOT_THRESHOLD_MDEG = 42000;
    private static final int COOL_TARGET_MDEG = 39000;
    private static final long MAX_THERMAL_WAIT_MS = 10 * 60 * 1000L;
    /** md5 of the only profile that is proven safe + working on this device. */
    private static final String PROVEN_PROFILE_MD5 = "b0aa22e0351dbf3871f5997a8f2847cd";
    /* v26: verdict/state handoff files (world-readable, uid-2000-writable).
     * - .gr_verdict  the FINAL outcome of this session, written once in
     *   finally(): the ONLY success proof is `su -c id` containing uid=0
     *   (same judge as PC autoroot). enforce=0 / KSU-present WITHOUT a
     *   passing su check is verdict=1 (landed-but-unverified), never 0.
     * - .gr_state    per-phase heartbeat: lets the Activity distinguish
     *   "device reset mid-attack" from "silent death" after a reboot. */
    private static final String VERDICT_FILE = "/data/local/tmp/.gr_verdict";
    private static final String STATE_FILE = "/data/local/tmp/.gr_state";
    /** v19 telemetry: structured per-round rows for hit-rate analysis
     * (PC vs phone-context A/B, temperature correlation). v20 bumps to a
     * new file + schema with zone-named temperature. */
    private static final String CSV_FILE = "/data/local/tmp/ghostroot-runs2.csv";
    /** v19: narrative log (App-side lines survive the on-screen loss after a
     * natural exploit reset — run.log only holds native CLI output).
     * v27: master log is APPEND-ONLY (never truncated — the 11-01 incident
     * lost session 1 to truncation); each session ALSO gets its own
     * ghostroot-app-<ts>.log file, pruned to the newest 8. */
    private static final String APP_LOG_FILE = "/data/local/tmp/ghostroot-app.log";
    private static final String SESSION_LOG_GLOB = "ghostroot-app-";
    private static final int SESSION_LOGS_KEPT = 8;
    private static final long MASTER_LOG_MAX_BYTES = 2 * 1024 * 1024L;
    /** v18: magica auto-patch. Assets (embedded in the APK) are the golden
     * artifacts from 06-kernelsu-manager; lib md5 matches the PC-validated
     * /data/local/tmp/libksud.so (2c84145e...). After root lands the service
     * runs fix.sh via su so the manager is safe to open on THIS boot. */
    private static final String TMP = "/data/local/tmp";
    private static final String ASSET_LIB = "libksud-fixed.so";
    private static final String ASSET_FIX = "fix.sh";
    private static final String FIXED_LIB_MD5 = "2c84145e3efb6f7f045fef3ed1eea4c6";
    /** v20: environment matched to adbd's own shell env (the context that
     * went 2/9 today) — replaces the earlier hand-written PATH order and
     * adds the few base vars a native binary may probe. */
    private static final String SH_ENV =
            "PATH=/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin"
                    + ":/apex/com.android.virt/bin:/system_ext/bin:/system/bin:/system/xbin"
                    + ":/odm/bin:/vendor/bin:/vendor/xbin HOME=/ TMPDIR=/data/local/tmp"
                    + " ANDROID_DATA=/data ANDROID_ROOT=/system ANDROID_ASSETS=/system/app"
                    + " ANDROID_STORAGE=/storage ANDROID_I18N_ROOT=/apex/com.android.i18n"
                    + " ANDROID_TZDATA_ROOT=/apex/com.android.tzdata"
                    + " ANDROID_ART_ROOT=/apex/com.android.art"
                    + " ASEC_MOUNTPOINT=/mnt/asec DOWNLOAD_CACHE=/data/cache"
                    + " EXTERNAL_STORAGE=/sdcard ANDROID_BOOTLOGO=1"
                    + " USER=shell LOGNAME=shell SHELL=/bin/sh TERM=xterm-256color";

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /* v26: verdict accumulator — set by every exit path, flushed to
     * VERDICT_FILE exactly once in finally(). MIN_VALUE = "not set yet". */
    private int lastVerdict = Integer.MIN_VALUE;
    private String lastReason = "unset";
    private String lastSu = "";
    private int lastRound = -1;
    /** v32: magica 补丁结果独立随行 —— root 成功与"管理器可用"是两个结论，
     * 不能在界面上混为一个提示。 */
    private String lastMagica = "not-run";
    /** v35: DirtyFrag-vr W1c 取证 —— 1=dmesg 有摘除行 / 0=dmesg 可读但无此行
     * / unknown=dmesg 不可读（shell 域受限），取证只在 root 活着时做。 */
    private String lastVrDisarmed = "unknown";
    /** v27: this session's own log file (never overwritten by the next run). */
    private String currentSessionFile;
    /** v33: 当前会话 ID（服务端下发）。verdict 文件会带上 session= 字段，
     * 服务端 finish() 只认精确匹配 —— 这是严格会话绑定，时间窗口只是辅助。 */
    private volatile String currentSessionId = "none";
    /* v34: 上一会话存档。prepareSession 必须在删除 verdict 文件【之前】
     * 完成采集 —— 否则 startAppLogSession 看到的 verdictFile=absent 是
     * 程序自己删的，上一会话的"异常中断证据"就被清理动作销毁了。
     * prepareSession 采集 → startAppLogSession 消费。 */
    private volatile String archivedPrevState = "";
    private volatile String archivedPrevVerdict = "";
    private volatile boolean archivedPrevVerdictPresent;

    public RootUserService(Context context) {
        this.context = context;
    }

    @Override
    public boolean isReady() {
        return Process.myUid() == Process.SHELL_UID;
    }

    /** v33/v34: 每次执行前由服务端调用。顺序不可换：先把上一会话的
     * .gr_state / .gr_verdict 存进内存字段（供会话头归档），再删文件。
     * v34 之前先删后归档，导致"上次异常中断"的证据被清理动作自己抹掉。 */
    @Override
    public void prepareSession(String sessionId) {
        currentSessionId = sessionId == null || sessionId.isEmpty() ? "none" : sessionId;
        /* 先归档 */
        archivedPrevState = readText(STATE_FILE).trim();
        archivedPrevVerdictPresent = new File(VERDICT_FILE).isFile();
        archivedPrevVerdict = archivedPrevVerdictPresent
                ? readText(VERDICT_FILE).trim() : "";
        /* 后清理 */
        new File(VERDICT_FILE).delete();
        writeLine(APP_LOG_FILE, "=== SESSION " + currentSessionId
                + " prepared (prev state archived: " + archivedPrevState.length()
                + "B, verdict present=" + archivedPrevVerdictPresent + ") ===\n", true);
    }

    @Override
    public void run(String cliPath, String profilePath, int settleSeconds, ILogCallback callback) {
        if (!running.compareAndSet(false, true)) {
            try {
                callback.onLog("another run is already in progress");
                callback.onDone(2);
            } catch (RemoteException ignored) {
            }
            return;
        }
        Thread worker = new Thread(() -> execute(cliPath, profilePath, settleSeconds, callback), "ghostroot-run");
        worker.setDaemon(true);
        worker.start();
    }

    private void execute(String cliPath, String profilePath, int settleSeconds, ILogCallback callback) {
        int exit = -1;
        /* v33: 若 Shizuku 复用同一 UserService 对象处理后续任务，内存里的
         * 上一任务结论必须先清零 —— 删结果文件清不掉这些字段，提前退出/
         * 异常路径否则会夹带旧身份、旧轮次、旧 magica 状态。 */
        lastVerdict = Integer.MIN_VALUE;
        lastReason = "unset";
        lastSu = "";
        lastRound = -1;
        lastMagica = "not-run";
        lastVrDisarmed = "unknown";
        try {
            /* v19: from now on every narrative line lands on disk too, so a
             * natural reset can no longer erase the session's story. */
            startAppLogSession();
            /* v26: drop any verdict left by a PREVIOUS session, then start
             * the heartbeat so a reboot mid-run is forensically visible. */
            new File(VERDICT_FILE).delete();
            writeState("preflight", 0);
            /* v31 (P2-4): 失败重启后的窗口采样 —— 新 boot 尚无 root，先做
             * best-effort 尝试。本设备 shell 读 pstore 是 Permission denied，
             * 这里会如实记 "deferred until root"；若 shell 可读（或哪天
             * 变得可读）则直接保存，补上"复位后、root 前"的证据空窗。 */
            attemptPstoreSample(callback, false);
            ensureCsvHeader();
            log(callback, "uid=" + Process.myUid() + " (expect 2000), seccomp=" + seccompState());

            File cli = new File(cliPath);
            File profile = new File(profilePath);
            if (!cli.isFile()) {
                log(callback, "missing CLI binary: " + cliPath);
                log(callback, "exit=-1");
                lastVerdict = -1;
                lastRound = 0;
                lastReason = "missing-cli:" + cliPath;
                return;
            }
            if (!profile.isFile()) {
                log(callback, "missing profile: " + profilePath);
                log(callback, "exit=-1");
                lastVerdict = -1;
                lastRound = 0;
                lastReason = "missing-profile:" + profilePath;
                return;
            }

            /* profile integrity gate: only the md5-verified original may run.
             * A patched profile (w1_scratch_repair_attempts=1) hard-resets the
             * phone — never execute with an unverified profile. */
            String md5 = md5(profile);
            if (!PROVEN_PROFILE_MD5.equals(md5)) {
                log(callback, "ABORT: profile md5=" + md5 + " != proven " + PROVEN_PROFILE_MD5);
                log(callback, "restore the original profile (profile.bin.bak-w1v2) first — bad profiles reset the phone");
                log(callback, "exit=-2");
                lastVerdict = -1;
                lastRound = 0;
                lastReason = "profile-md5-mismatch:" + md5;
                return;
            }
            log(callback, "profile md5 ok: " + md5);

            /* preflight: the fire chain needs all four pieces in place */
            StringBuilder missing = new StringBuilder();
            for (String f : new String[]{"fire.sh", "one.sh", "b0"}) {
                if (!new File("/data/local/tmp", f).isFile()) {
                    missing.append(f).append(' ');
                }
            }
            if (missing.length() > 0) {
                log(callback, "ABORT: fire chain incomplete, missing: " + missing);
                log(callback, "exit=-3");
                lastVerdict = -1;
                lastRound = 0;
                lastReason = "fire-chain-missing:" + missing.toString().trim();
                return;
            }
            log(callback, "preflight ok: ghostlock + profile(md5) + fire.sh + one.sh + b0");

            /* v29: PC autoroot's FIRST action every cycle is root_ok — if
             * root is already live on this boot it exits without attacking.
             * The App lacked this gate: the 11-01 10:26 reset happened when a
             * fresh attack ran on a boot where KSU was ALREADY loaded (root
             * verified 10:25). Attacking a KSU-loaded/polluted heap is the
             * known wedge scenario — never do it. */
            String preSu = suCheck();
            boolean preKsu = readText("/proc/modules").contains("kernelsu");
            if (preSu.contains("uid=0") || preKsu) {
                boolean verified = preSu.contains("uid=0");
                log(callback, verified
                        ? "ROOT ALREADY LIVE on this boot (su -c id: " + preSu.trim() + ") — skipping attack entirely (PC root_ok gate)"
                        : "KernelSU module already loaded but root unverified (" + preSu.trim() + ") — skipping attack (polluted-heap guard)");
                lastVerdict = verified ? 0 : 1;
                lastRound = 0;
                lastSu = preSu.trim();
                lastReason = verified ? "root-already-live-skip-attack" : "ksu-already-loaded-skip-attack";
                if (verified) {
                    exit = 0;
                    lastMagica = runMagicaFix(callback);
                    /* v33: 管理器提示必须在补丁检查之后，且按结果分流 ——
                     * 不能在检查前就说"直接打开管理器"。 */
                    boolean mgrOk = "manager-patched".equals(lastMagica)
                            || "already-patched".equals(lastMagica);
                    log(callback, "已在本 boot 拿到 root（无需再打）；管理器补丁状态="
                            + lastMagica + (mgrOk
                            ? " → 可直接打开 KernelSU 管理器"
                            : " → 打开管理器前先跑 PC fix.sh"));
                    /* v30/v31: 采样入口覆盖 already-rooted 路径 —— root 活着
                     * 但不攻击的会话同样要抓 pstore/取证快照 */
                    attemptPstoreSample(callback, true);
                    /* v35: W1c 摘除去证 —— already-rooted 路径同样验证 */
                    lastVrDisarmed = vrDisarmProbe();
                    log(callback, "vr_disarmed=" + lastVrDisarmed + " (W1c dmesg evidence)");
                } else {
                    log(callback, "仅检测到 KSU 模块、身份未验证 —— 不要打开管理器，"
                            + "先确认补丁状态（PC: su -c sh /data/local/tmp/fix.sh）");
                }
                writeState("already-rooted", 0);
                csvRow("already-rooted", readUptime(), 0, String.valueOf(exit),
                        readText("/sys/fs/selinux/enforce").trim(), preKsu, verified ? "root-live" : "ksu-only");
                return;
            }
            log(callback, "no KSU on this boot yet — clean heap, attack allowed");

            /* v18: deploy the magica-fix artifacts (fixed libksud + fix.sh) from
             * APK assets. uid 2000 can write /data/local/tmp, no root needed. */
            deployMagicaAssets(callback);

            File workDir = new File("/data/local/tmp");
            File logFile = new File(workDir, "ghostroot-run.log");

            /* wait for the heap to settle: attacks started <100s never landed
             * a write in today's 21-run sample (see snapshot analysis) */
            float uptime = readUptime();
            if (uptime < MIN_START_UPTIME_SECONDS) {
                long waitMs = (long) ((MIN_START_UPTIME_SECONDS - uptime) * 1000L);
                log(callback, "uptime=" + uptime + "s < " + (int) MIN_START_UPTIME_SECONDS
                        + "s: waiting " + (waitMs / 1000) + "s for boot heap to settle (log-driven optimum)");
                Thread.sleep(waitMs);
                uptime = readUptime();
            }
            int settle = Math.min(settleSeconds, 5);
            log(callback, "uptime=" + uptime + "s settle=" + settle + "s, up to " + MAX_ROUNDS + " rounds");
            if (settle > 0) {
                Thread.sleep(settle * 1000L);
            }

            /* fire watcher first, exactly like PC ensure_fire before the attack */
            armFireWatcher(callback);

            List<String> argv = new ArrayList<>();
            argv.add(cli.getAbsolutePath());
            argv.add("--load-prebuilt-profile");
            argv.add(profile.getAbsolutePath());
            argv.add("--force-attack");

            dumpDiagnostics(workDir);

            /* ===== up to MAX_ROUNDS full attacks this boot (PC autoroot flow) =====
             * PC's proven 6/6 came from cycling the full attack on the same
             * boot (12 cycles, 20s apart) with the original profile whose
             * w1_scratch_repair_attempts=3 is the safety net for repeated
             * writes. Each round is an independent lottery draw; a route
             * wedge resets the phone (fresh heap next boot) — same as PC. */
            for (int round = 1; round <= MAX_ROUNDS; round++) {
                log(callback, "===== ROUND " + round + "/" + MAX_ROUNDS + " =====");
                if (round > 1) {
                    log(callback, "cooldown " + ROUND_COOLDOWN_SECONDS + "s (PC autoroot rhythm)");
                    Thread.sleep(ROUND_COOLDOWN_SECONDS * 1000L);
                    if (readText("/proc/modules").contains("kernelsu")) {
                        log(callback, "KernelSU appeared during cooldown (fire chain)");
                        break;
                    }
                }
                /* passive telemetry only — resets are this exploit's normal
                 * failure form (per iqoo-neo9-ghostlock-pc), not thermal-gated */
                int tempMdeg = readSoCTemp();
                log(callback, "SoC temp=" + tempMdeg / 1000 + "C (telemetry)");
                /* fire watcher first (deduped via .fire.pid) */
                armFireWatcher(callback);

                log(callback, "exec round " + round + ": " + String.join(" ", argv));
                float attackUptime = readUptime();
                String enforceBefore = readText("/sys/fs/selinux/enforce").trim();
                csvRow("started", attackUptime, round, "NA", enforceBefore, false, "");
                /* v26 heartbeat: if the device resets mid-attack, .gr_state
                 * keeps saying "attacking" while the app log shows a ROUND
                 * line with no matching ROUND END — that pair is the reset
                 * signature (vs a silent crash, which would also lack the
                 * native exit line). */
                writeState("attacking", round);
                exit = runCli(argv, logFile, 240, null, callback);

                String enforce = readText("/sys/fs/selinux/enforce").trim();
                boolean ksu = readText("/proc/modules").contains("kernelsu");
                csvRow("done", attackUptime, round, String.valueOf(exit), enforce, ksu, "");
                /* v26: one structured END line per round — rc + kernel state,
                 * so every sample carries 执行开始→执行结束→退出原因. */
                log(callback, "ROUND " + round + " END rc=" + exit
                        + " enforce=" + (enforce.isEmpty() ? "UNREADABLE" : enforce)
                        + " ksu=" + (ksu ? "1" : "0"));
                if (ksu || "0".equals(enforce)) {
                    /* The exploit LANDED (permissive or KSU up). Per v26 the
                     * judge is now the SAME as PC autoroot: `su -c id` must
                     * contain uid=0. Landing without a passing su check is
                     * verdict=1 (landed-but-unverified), NOT success. */
                    if (verifyLanded(callback, enforce, ksu, round, attackUptime, exit) == 0) {
                        exit = 0;
                    }
                    return;
                }
                log(callback, "round " + round + " failed safe (page pollution / Write 1 failed)");
                writeState("cooldown", round);
            }
            /* v26: cooldown 期检测到 KSU 的 break 也会落到这里 —— 先按同一
             * 判据验证 su，再决定是"landed"还是"耗尽"。 */
            String enforceAfter = readText("/sys/fs/selinux/enforce").trim();
            boolean ksuAfter = readText("/proc/modules").contains("kernelsu");
            if (ksuAfter || "0".equals(enforceAfter)) {
                if (verifyLanded(callback, enforceAfter, ksuAfter, MAX_ROUNDS, readUptime(), exit) == 0) {
                    exit = 0;
                }
            } else {
                log(callback, "RESULT: " + MAX_ROUNDS + " rounds exhausted this boot — tap reboot, start Shizuku, run again");
                lastVerdict = -1;
                lastRound = MAX_ROUNDS;
                lastReason = MAX_ROUNDS + "-rounds-exhausted";
            }
        } catch (Throwable t) {
            log(callback, "error: " + t);
            if (lastVerdict == Integer.MIN_VALUE) {
                lastVerdict = 1;
                lastReason = "exception:" + t.getClass().getSimpleName();
            }
        } finally {
            /* v26: flush the final verdict — this is the file the app-side
             * service reads, and the ONLY place success can come from.
             * Every path above either set lastVerdict or left MIN_VALUE,
             * which means "died before deciding" -> unknown(1), NOT failure:
             * we cannot tell a reset from a crash without more evidence. */
            if (lastVerdict == Integer.MIN_VALUE) {
                lastVerdict = 1;
                lastReason = "no-verdict-decided";
            }
            writeVerdictFile(exit);
            log(callback, "enforce=" + readText("/sys/fs/selinux/enforce").trim());
            log(callback, "ksu_module=" + (readText("/proc/modules").contains("kernelsu") ? "present" : "absent"));
            log(callback, "exit=" + exit);
            running.set(false);
            try {
                callback.onDone(exit);
            } catch (RemoteException ignored) {
            }
        }
    }

    /** v26: persist the session's final verdict for the app-side service.
     * Schema: ts / verdict(0|1|-1) / su / enforce / ksu / round / exit / magica
     * / vr_disarmed(1|0|unknown, v35) / reason. */
    private void writeVerdictFile(int exit) {
        StringBuilder sb = new StringBuilder();
        sb.append("ts=").append(System.currentTimeMillis() / 1000).append('\n');
        sb.append("session=").append(currentSessionId).append('\n');
        sb.append("verdict=").append(lastVerdict).append('\n');
        sb.append("round=").append(lastRound).append('\n');
        sb.append("su=").append(lastSu.replace('\n', ' ')).append('\n');
        sb.append("enforce=").append(readText("/sys/fs/selinux/enforce").trim()).append('\n');
        sb.append("ksu=").append(readText("/proc/modules").contains("kernelsu") ? "1" : "0").append('\n');
        sb.append("exit=").append(exit).append('\n');
        sb.append("magica=").append(lastMagica).append('\n');
        /* v35: DirtyFrag-vr W1c 摘除去证（1/0/unknown） */
        sb.append("vr_disarmed=").append(lastVrDisarmed).append('\n');
        sb.append("reason=").append(lastReason).append('\n');
        writeLine(VERDICT_FILE, sb.toString(), false);
    }

    /** v26: per-phase heartbeat so a post-reboot Activity can tell
     * "reset mid-attack" from "finished" from "silent death". */
    private void writeState(String phase, int round) {
        writeLine(STATE_FILE, "ts=" + System.currentTimeMillis() / 1000
                + " phase=" + phase + " round=" + round + "/" + MAX_ROUNDS
                + " uptime=" + readUptime() + "s", false);
    }

    /** v26: landed 后的统一验证 —— 等 fire 链装完 KSU（最多 90s），然后
     * `su -c id` 是否含 uid=0 是唯一成功判据（与 PC autoroot 的 root_ok
     * 完全一致）。返回最终 verdict（0=验证成功 / 1=landed 未验证），
     * 并顺手完成 magica 补丁与 CSV 记录。 */
    private int verifyLanded(ILogCallback callback, String enforce, boolean ksu,
                             int round, float attackUptime, int exit) throws InterruptedException {
        for (int i = 0; i < 18 && !readText("/proc/modules").contains("kernelsu"); i++) {
            Thread.sleep(5000);
        }
        ksu = readText("/proc/modules").contains("kernelsu");
        writeState("verifying", round);
        String su = suCheck();
        lastSu = su.trim();
        boolean rootVerified = lastSu.contains("uid=0");
        log(callback, "ROOT VERIFY (su -c id): "
                + (rootVerified ? "uid=0 ✓ VERIFIED" : "NOT verified -> " + lastSu));
        String magica = "";
        if (rootVerified) {
            /* v18: root is live on this boot — patch the manager's libksud
             * now so opening KernelSU manager can never re-run magica
             * cold-start (the wedge/reboot trap). */
            magica = runMagicaFix(callback);
            lastMagica = magica;
            attemptPstoreSample(callback, true);
            /* v35: W1c 摘除去证 —— one.sh v5 用 dfroot_vr.ko 摘除 vr.ko
             * sys_exit 探针，dmesg 的 "W1c vr sys_exit probe disarmed" 是唯一证据行 */
            lastVrDisarmed = vrDisarmProbe();
            log(callback, "vr_disarmed=" + lastVrDisarmed + " (W1c dmesg evidence)");
            log(callback, "SUCCESS (root verified) in round " + round
                    + ": enforce=" + enforce + " ksu=" + ksu);
        } else {
            log(callback, "LANDED but root NOT verified in round " + round
                    + " — enforce=" + enforce + " ksu=" + ksu
                    + " (fire chain may still be working; check again later)");
        }
        csvRow(rootVerified ? "success" : "landed", attackUptime, round,
                String.valueOf(exit), enforce, ksu, magica);
        lastRound = round;
        lastVerdict = rootVerified ? 0 : 1;
        lastReason = rootVerified ? "su-verified-round-" + round
                : "landed-round-" + round + "-su-unverified";
        writeState(rootVerified ? "done" : "landed-unverified", round);
        return lastVerdict;
    }

    /** Run a CLI once via `sh -c` (adb-shell-like env), hard timeout, output appended to logFile. */
    private int runCli(List<String> argv, File logFile, long timeoutSeconds,
                       java.util.Map<String, String> env, ILogCallback callback) throws IOException, InterruptedException {
        long startOffset = logFile.isFile() ? logFile.length() : 0L;
        /* v20/v21: a JVM parent keeps stray fds alive (no CLOEXEC on native
         * library sockets) and they leak into the sh child. v21 fixes the
         * v20 syntax bug: "VAR=x for …" is a POSIX-syntax error, so the env
         * now goes through `exec env …` AFTER the fd-close loop instead of
         * prefixing the compound statement. */
        StringBuilder fdList = new StringBuilder();
        for (int i = 3; i <= 200; i++) {
            fdList.append(i).append(' ');
        }
        StringBuilder cmd = new StringBuilder()
                .append("cd /data/local/tmp; for i in ").append(fdList)
                .append("; do [ -e /proc/self/fd/$i ] && eval \"exec $i>&-\" 2>/dev/null; done")
                .append("; echo fds_after_close=$(ls /proc/self/fd | wc -l); exec env ")
                .append(SH_ENV)
                .append(' ');
        /* SH_ENV is "PATH=… HOME=/ TMPDIR=… …": argv after `env` picks up the
         * assignments as its own env for its child. ProcessBuilder passes
         * this as ONE argv; mksh expands $(…) locally at each evaluate step. */
        for (String a : argv) {
            cmd.append(' ').append(a);
        }
        ProcessBuilder builder = new ProcessBuilder("sh", "-c", cmd.toString());
        builder.directory(new File("/data/local/tmp"));
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        if (env != null && !env.isEmpty()) {
            builder.environment().putAll(env);
        }
        java.lang.Process process = builder.start();

        Thread watchdog = new Thread(() -> {
            try {
                if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    return;
                }
                log(callback, "TIMEOUT " + timeoutSeconds + "s -> destroyForcibly (same as PC timeout)");
                process.destroyForcibly();
            } catch (InterruptedException ignored) {
            }
        }, "cli-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        tail(logFile, startOffset, process, callback);
        int exit = process.waitFor();
        watchdog.interrupt();
        log(callback, "native exit=" + exit);
        return exit;
    }

    /** uid 2000 (u:r:shell:s0) may reboot — same as adb shell reboot.
     * v27: write a durable marker BEFORE rebooting. Next session's header
     * reads .gr_state: phase=manual-reboot means the restart was OUR doing;
     * phase=attacking + no marker + no verdict means an involuntary reset
     * (kernel/watchdog) — together with the bootreason props this finally
     * separates 主动重启 from 内核异常. */
    @Override
    public void rebootDevice() {
        try {
            String marker = "=== REBOOT REQUESTED ts=" + System.currentTimeMillis() / 1000
                    + " uptime=" + readUptime() + "s enforce="
                    + readText("/sys/fs/selinux/enforce").trim() + " ===\n";
            writeLine(APP_LOG_FILE, marker, true);
            if (currentSessionFile != null) {
                writeLine(currentSessionFile, marker, true);
            }
            writeState("manual-reboot", 0);
        } catch (Exception ignored) {
        }
        Thread t = new Thread(() -> {
            try {
                new ProcessBuilder("sh", "-c", "reboot").start().waitFor();
            } catch (Exception ignored) {
            }
        }, "reboot");
        t.setDaemon(true);
        t.start();
    }

    /** Max thermal-zone temperature in milli-degrees C (best SoC proxy). */
    private int readSoCTemp() {
        int max = 0;
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones != null) {
            for (File z : zones) {
                if (!z.getName().startsWith("thermal_zone")) {
                    continue;
                }
                File t = new File(z, "temp");
                if (!t.isFile()) {
                    continue;
                }
                try {
                    int v = Integer.parseInt(readText(t.getAbsolutePath()).trim());
                    if (v > max && v < 100000) {
                        max = v;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return max;
    }

    private String md5(File file) {
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "error:" + e;
        }
    }

    /** uid 2000 (u:r:shell:s0) may su once KernelSU is live; returns the id output. */
    private String suCheck() {
        return rootShell("id", 30).out.trim();
    }

    /** v35: DirtyFrag-vr W1c 摘除去证 —— root 活着时读 dmesg 找
     * "W1c vr sys_exit probe disarmed"（one.sh v5 摘除动作的唯一指纹行）。
     * 返回 "1"（已摘除）/ "0"（dmesg 可读但无此行）/ "unknown"（dmesg 不可读）。
     * 注意：shell 域读 dmesg 受限时绝不能误报 0，必须落 unknown。 */
    private String vrDisarmProbe() {
        ShellResult r = rootShell("dmesg", 30);
        if (r.timedOut || !r.ok()) {
            return "unknown";
        }
        return r.out.contains("W1c vr sys_exit probe disarmed") ? "1" : "0";
    }

    /** v30: 命令结果三元组 —— 输出文本之外必须带退出码与超时标记，
     * 调用方才有资格区分"成功空输出 / 失败 / 被杀"。 */
    private static final class ShellResult {
        final int exit;          // -1 = 被超时终止或启动失败
        final boolean timedOut;
        final String out;
        ShellResult(int e, boolean t, String o) { exit = e; timedOut = t; out = o; }
        boolean ok() { return !timedOut && exit == 0; }
    }

    /** v31: ACTUALLY run as root (`su -c` wraps the whole command), with a
     * bounded read: the reader lives on a daemon thread, the main path waits
     * at most timeoutSeconds(+2s join) and then gives up with timedOut=true.
     * 这保证本方法在有限时间内返回 —— 即使 su 的子进程握着管道不放，
     * reader 线程被遗弃（daemon）后主路径照样继续，管道泄漏止于该线程。
     * 之前两版的问题：v29 只杀 su 不保证子进程退净、主线程仍可能被
     * 未关闭的输出卡死；v30 的"读循环必然终止"对孙子进程不成立。 */
    /** v31: bounded shell 三元组实现（su 与普通 sh 共用）。读循环在 daemon
     * 线程上，主路径最多等 timeout(+2s join) 就带 timedOut=true 返回 ——
     * 即使 su 的子进程握着管道不放，主路径也不会被卡死（reader 被遗弃，
     * 管道泄漏止于该 daemon 线程）。v30 的"读循环必然终止"只对直接子进程
     * 成立，对孙子进程不成立，这里不再那样承诺。 */
    private ShellResult boundedShell(String[] argv, long timeoutSeconds) {
        try {
            final java.lang.Process p = new ProcessBuilder(argv)
                    .redirectErrorStream(true).start();
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (sb) {
                            sb.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "shell-reader");
            reader.setDaemon(true);
            reader.start();
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
            }
            reader.join(2000);
            String out;
            synchronized (sb) {
                out = sb.toString();
            }
            return new ShellResult(finished ? p.exitValue() : -1, !finished, out);
        } catch (Exception e) {
            return new ShellResult(-1, false, "error: " + e);
        }
    }

    private ShellResult rootShell(String cmd, long timeoutSeconds) {
        return boundedShell(new String[]{"su", "-c", SH_ENV + " " + cmd}, timeoutSeconds);
    }

    private ShellResult plainShell(String cmd, long timeoutSeconds) {
        return boundedShell(new String[]{"sh", "-c", SH_ENV + " " + cmd}, timeoutSeconds);
    }

    /**
     * v18 preflight: make sure /data/local/tmp has fix.sh + the FIXED
     * libksud.so (from APK assets). Skips the copy when the on-device lib
     * already md5-matches the shipped fixed version.
     */
    private void deployMagicaAssets(ILogCallback callback) {
        File fix = new File(TMP, "fix.sh");
        File lib = new File(TMP, "libksud.so");
        boolean libOk = false;
        if (lib.isFile()) {
            try (java.io.InputStream in = new java.io.FileInputStream(lib)) {
                libOk = FIXED_LIB_MD5.equals(md5Stream(in));
            } catch (Exception ignored) {
            }
        }
        if (libOk && fix.isFile()) {
            log(callback, "magica fix artifacts already in place (md5 " + FIXED_LIB_MD5 + ")");
            return;
        }
        try {
            boolean wroteFix = copyAsset(ASSET_FIX, fix);
            boolean wroteLib = copyAsset(ASSET_LIB, lib);
            log(callback, "magica artifacts deployed: fix=" + (fix.isFile() ? wroteFix : false)
                    + " lib=" + (lib.isFile() ? wroteLib : false));
        } catch (Exception e) {
            log(callback, "magica deploy error: " + e);
        }
        /* verify what actually landed */
        try {
            String md5 = execOut("md5sum", TMP + "/libksud.so");
            log(callback, "libksud md5: " + md5.trim());
        } catch (Exception e) {
            log(callback, "libksud md5 check error: " + e);
        }
    }

    /** Stream an APK asset into target (uid 2000-owned, /data/local/tmp). */
    private boolean copyAsset(String name, File target) throws IOException {
        java.io.InputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            in = context.getAssets().open(name);
            out = new java.io.FileOutputStream(target);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.getFD().sync();
            String mode = ASSET_FIX.equals(name) ? "755" : "644";
            try {
                execOut("chmod", mode, target.getAbsolutePath());
            } catch (Exception e) {
                /* non-fatal; next stage re-verifies via md5 */
            }
            return true;
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { if (out != null) out.close(); } catch (IOException ignored) {}
        }
    }

    private String md5Stream(java.io.InputStream in) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * v18 success stage: run fix.sh as root. v28 rework — the 11-01 sample
     * showed cp/chown/chmod Permission-denied ~2s after KSU late-load while
     * the old script still announced "已替换/完成". New discipline:
     *  1. md5 pre-check FIRST: if the manager lib already IS the fixed one,
     *     skip the copy and report "already-patched" (a failed cp on top of
     *     an already-patched lib is NOT a success story to tell).
     *  2. Only the final md5 decides; fix.sh v2 prints per-step rc and exits
     *     non-zero on failure.
     *  3. Retry once after 10s on failure.
     * v30 correction: 当时把那次 Permission denied 归因为"KSU sepolicy 瞬态"
     * 是错的 —— 真相是旧 runAsRoot 根本没走 su，fix.sh 一直以 uid 2000 运行，
     * cp /data/app 自然被拒。现在 runAsRoot 真正 su -c 后，cp 才第一次
     * 具备成功条件；重试保留，作为瞬态场景兜底。
     */
    private String runMagicaFix(ILogCallback callback) {
        File fix = new File(TMP, "fix.sh");
        if (!fix.isFile()) {
            log(callback, "[MAGICA] fix.sh missing — run the PC patch or reinstall GhostRoot assets");
            return "fix-sh-missing";
        }
        /* pre-check: maybe yesterday's patch survived the reboot (it lives in
         * /data/app which persists across reboots) */
        ShellResult cur = rootShell("md5sum $(pm path me.weishu.kernelsu | head -1 "
                + "| sed 's/package://;s|/base.apk|/lib/arm64/libksud.so|')", 30);
        log(callback, "[MAGICA] pre-check manager lib md5 (exit=" + cur.exit
                + (cur.timedOut ? " TIMEDOUT" : "") + "): " + cur.out.trim());
        if (cur.ok() && cur.out.trim().startsWith(FIXED_LIB_MD5)) {
            log(callback, "[MAGICA] already patched (md5 matches) — skipping copy");
            return "already-patched";
        }
        if (!cur.ok()) {
            log(callback, "[MAGICA] pre-check failed (exit=" + cur.exit
                    + (cur.timedOut ? " timedOut" : "") + ") — continuing to fix.sh anyway");
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            log(callback, "[MAGICA] running fix.sh via su (attempt " + attempt + ") …");
            ShellResult r = rootShell("sh " + TMP + "/fix.sh", 60);
            for (String line : r.out.split("\n")) {
                if (!line.isEmpty()) {
                    log(callback, "[MAGICA] " + line);
                }
            }
            if (r.timedOut) {
                log(callback, "[MAGICA] fix.sh TIMED OUT — result unknown, NOT claiming success");
            } else if (r.exit != 0) {
                log(callback, "[MAGICA] fix.sh exited " + r.exit + " (non-zero = failure by design)");
            }
            if (r.ok() && r.out.contains("[VERIFIED]")) {
                log(callback, "[MAGICA] ✓ verified — 管理器可安全打开（32680 版）");
                return "manager-patched";
            }
            if (attempt == 1 && !r.ok()) {
                log(callback, "[MAGICA] …likely transient (KSU just loaded); retrying in 10s");
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ignored) {
                }
            }
        }
        log(callback, "[MAGICA] ✗ NOT verified after retries — do NOT open the manager on this boot; use PC: adb push libksud + su -c sh fix.sh");
        return "manager-NOT-patched";
    }

    /** v28→v31: snapshot /sys/fs/pstore while root is live.
     * v31 (P2 全量)：列表与复制都经 rootShell（带退出码/超时标记）；
     * 超时、非零退出、空输出三者分开报告，"可读且为空"只在
     * exit==0 && !timedOut 时才允许说；每个条目复制后再用 root 端
     * wc -c 与本地文件长度比对，字节数一致才计入 preserved ——
     * 部分写入/中途超时不再算成功。 */
    private void attemptPstoreSample(ILogCallback callback, boolean useRoot) {
        String tag = useRoot ? "[PSTORE]" : "[PSTORE-early]";
        try {
            ShellResult ls = useRoot
                    ? rootShell("ls -A /sys/fs/pstore 2>&1", 30)
                    : plainShell("ls -A /sys/fs/pstore 2>&1", 15);
            String l = ls.out.trim();
            if (ls.timedOut) {
                log(callback, tag + " listing TIMED OUT — nothing concluded");
                return;
            }
            if (ls.exit != 0) {
                if (useRoot) {
                    log(callback, tag + " listing FAILED exit=" + ls.exit + ": " + l
                            + " — cannot tell whether entries exist, no conclusion drawn");
                } else {
                    log(callback, tag + " shell read failed (exit=" + ls.exit
                            + ", expected Permission denied on this device) — "
                            + "sampling deferred until root lands");
                }
                return;
            }
            if (l.isEmpty()) {
                log(callback, tag + " readable and EMPTY right now (exit=0) — no ramoops "
                        + "entries at this moment (not proof about the backend itself; "
                        + "entries are normally consumed at boot)");
                return;
            }
            File dir = new File(TMP, "pstore-snap-" + System.currentTimeMillis() / 1000);
            boolean dirOk = dir.mkdirs() || dir.isDirectory();
            if (!dirOk) {
                log(callback, tag + " FAIL: cannot create snapshot dir " + dir);
                return;
            }
            int saved = 0, total = 0;
            for (String name : l.split("\n")) {
                if (name.isEmpty()) {
                    continue;
                }
                total++;
                File out = new File(dir, name);
                ShellResult cp = useRoot
                        ? rootShell("cat /sys/fs/pstore/" + name + " > " + out.getAbsolutePath(), 30)
                        : plainShell("cat /sys/fs/pstore/" + name + " > " + out.getAbsolutePath(), 15);
                if (cp.timedOut || cp.exit != 0) {
                    log(callback, tag + " copy FAILED/TIMEDOUT for " + name
                            + " (exit=" + cp.exit + " timedOut=" + cp.timedOut + ")");
                    continue;
                }
                ShellResult sz = useRoot
                        ? rootShell("wc -c < /sys/fs/pstore/" + name, 15)
                        : plainShell("wc -c < /sys/fs/pstore/" + name, 15);
                long remote = -1;
                try {
                    remote = Long.parseLong(sz.out.trim());
                } catch (NumberFormatException ignored) {
                }
                if (sz.ok() && remote >= 0 && out.isFile()
                        && out.length() == remote) {
                    saved++;
                } else {
                    log(callback, tag + " entry INCOMPLETE: " + name
                            + " local=" + (out.isFile() ? out.length() : -1)
                            + " remote=" + remote);
                }
            }
            log(callback, tag + " preserved " + saved + "/" + total
                    + " entries (byte-size-verified) -> " + dir);
        } catch (Exception e) {
            log(callback, tag + " preserve error: " + e);
        }
    }

    private void dumpDiagnostics(File workDir) {
        File diag = new File(workDir, "ghostroot-diag.log");
        StringBuilder sb = new StringBuilder();
        sb.append("=== diag ").append(new java.util.Date()).append(" ===\n");
        sb.append("uid=").append(Process.myUid()).append(" pid=").append(Process.myPid()).append('\n');
        sb.append("--- cgroup ---\n").append(readText("/proc/self/cgroup"));
        sb.append("--- status(keys) ---\n");
        for (String line : readText("/proc/self/status").split("\n")) {
            if (line.startsWith("Threads:") || line.startsWith("Seccomp:")
                    || line.startsWith("CapEff:") || line.startsWith("NoNewPrivs:")) {
                sb.append(line).append('\n');
            }
        }
        String[] fds = new File("/proc/self/fd").list();
        sb.append("fds=").append(fds == null ? -1 : fds.length).append('\n');
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(diag, true)) {
            out.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            /* diagnostics are best-effort */
        }
    }

    private void armFireWatcher(ILogCallback callback) {
        try {
            File fire = new File("/data/local/tmp/fire.sh");
            if (!fire.isFile()) {
                log(callback, "fire.sh missing; skipping watcher");
                return;
            }
            /* one watcher only: PC-side uses .fire.pid to dedupe */
            File pidFile = new File("/data/local/tmp/.fire.pid");
            String pid = readText(pidFile.getAbsolutePath()).trim();
            if (!pid.isEmpty()) {
                try {
                    String alive = execOut("kill", "-0", pid);
                    if (!alive.isEmpty()) {
                        log(callback, "fire watcher alive pid=" + pid);
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
            /* detached fire watcher: survives UserService teardown, same as
             * PC ensure_fire (nohup-style, deduped via .fire.pid) */
            File fireLog = new File("/data/local/tmp/.fire.log");
            new ProcessBuilder("sh", "-c",
                    "cd /data/local/tmp && nohup sh fire.sh >> .fire.log 2>&1 &")
                    .directory(fire.getParentFile())
                    .redirectErrorStream(true)
                    .start();
            log(callback, "fire watcher armed (detached)");
        } catch (Exception e) {
            log(callback, "fire arm error: " + e);
        }
    }

    private String execOut(String... cmd) throws IOException, InterruptedException {
        java.lang.Process p = new ProcessBuilder(cmd).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        p.waitFor();
        return sb.toString();
    }

    private float readUptime() {
        try {
            String s = readText("/proc/uptime").trim();
            int sp = s.indexOf(' ');
            return Float.parseFloat(sp > 0 ? s.substring(0, sp) : s);
        } catch (Exception e) {
            return 0f;
        }
    }

    private void tail(File file, long offset, java.lang.Process process, ILogCallback callback) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            boolean alive = true;
            while (alive) {
                String line = raf.readLine();
                if (line != null) {
                    log(callback, line);
                    continue;
                }
                alive = process.isAlive();
                if (alive) {
                    Thread.sleep(150);
                }
            }
            String rest;
            while ((rest = raf.readLine()) != null) {
                log(callback, rest);
            }
        } catch (Exception e) {
            log(callback, "tail error: " + e);
        }
    }

    /** v19: session header for the narrative log.
     * v27 rework — the 11-01 double-reset lost session 1 entirely because
     * this used to TRUNCATE the master log. Now:
     *  1. BEFORE touching anything, archive the previous session's .gr_state
     *     and .gr_verdict into the header (the reset signature survives).
     *  2. Record the CURRENT boot's reason properties — they describe WHY the
     *     previous session's device restarted (kernel_panic / watchdog / cold
     *     reboot …), the classification pstore would have given us.
     *  3. Master log = append-only with a session separator; this session
     *     additionally gets its own file so two runs are never interleaved. */
    private void startAppLogSession() {
        /* v34: 上一会话的 state/verdict 已由 prepareSession 在删除前采集
         * 到内存字段 —— 这里只消费存档，不再读文件（文件此刻已被本会话
         * 的清理动作删除，读到的"absent"不具证据价值）。 */
        String prevState = archivedPrevState;
        boolean prevVerdict = archivedPrevVerdictPresent;
        String prevVerdictBody = archivedPrevVerdict;
        String bootReason = prop("ro.boot.bootreason") + " / " + prop("sys.boot.reason")
                + " / " + prop("persist.sys.boot.reason");

        StringBuilder sb = new StringBuilder();
        sb.append("=== GhostRoot session ts=").append(System.currentTimeMillis() / 1000)
                .append(" uid=").append(Process.myUid())
                .append(" pid=").append(Process.myPid())
                .append(" uptime=").append(readUptime())
                .append("s temp=").append(readSoCTemp())
                .append("mdeg enforce=").append(readText("/sys/fs/selinux/enforce").trim())
                .append(" ===\n");
        sb.append("=== PREV-SESSION bootreason=[").append(bootReason).append("]")
                .append(" state=[").append(prevState).append("]")
                .append(" verdictFile=").append(prevVerdict ? "present" : "absent")
                .append(" ===\n");
        if (prevVerdict) {
            sb.append("--- prev verdict body ---\n").append(prevVerdictBody).append('\n');
        }
        if (prevState.contains("phase=attacking") && !prevVerdict) {
            /* v29: 措辞降级（P2）——"停在 attacking 且无 verdict"只能证明
             * 上次没有形成结束记录。主动重启、进程被杀、系统复位都产生同样
             * 的残留；分类只能靠上面的 bootreason props 和 REBOOT REQUESTED
             * 标记，这里不做任何归因。 */
            sb.append("=== PREV SESSION LEFT NO CLEAN END RECORD (state=attacking, no verdict). "
                    + "Cause NOT determined: voluntary reboot / process kill / system reset "
                    + "all look identical here — classify only via bootreason props "
                    + "and the REBOOT REQUESTED marker, if present ===\n");
        }
        /* master log: append-only, rotate when oversized */
        writeLine(APP_LOG_FILE, sb.toString(), true);
        rotateIfBig(APP_LOG_FILE, MASTER_LOG_MAX_BYTES);
        /* per-session file */
        try {
            String name = SESSION_LOG_GLOB
                    + new java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                            .format(new java.util.Date()) + ".log";
            currentSessionFile = new File("/data/local/tmp", name).getAbsolutePath();
            writeLine(currentSessionFile, sb.toString(), false);
            pruneSessionLogs();
        } catch (Exception ignored) {
            currentSessionFile = null;
        }
    }

    /** v27: read a system property (uid 2000 may call getprop freely). */
    private String prop(String name) {
        try {
            String v = execOut("getprop", name);
            return v == null ? "" : v.trim();
        } catch (Exception e) {
            return "err";
        }
    }

    /** v27: rename log to .old when it exceeds maxBytes (keep one generation). */
    private void rotateIfBig(String path, long maxBytes) {
        try {
            File f = new File(path);
            if (f.isFile() && f.length() > maxBytes) {
                File old = new File(path + ".old");
                if (old.exists()) {
                    old.delete();
                }
                f.renameTo(old);
            }
        } catch (Exception ignored) {
        }
    }

    /** v27: keep only the newest SESSION_LOGS_KEPT per-session files. */
    private void pruneSessionLogs() {
        File[] all = new File("/data/local/tmp").listFiles();
        if (all == null) {
            return;
        }
        java.util.List<File> sessions = new ArrayList<>();
        for (File f : all) {
            if (f.isFile() && f.getName().startsWith(SESSION_LOG_GLOB)
                    && f.getName().endsWith(".log")) {
                sessions.add(f);
            }
        }
        java.util.Collections.sort(sessions, (a, b) -> a.getName().compareTo(b.getName()));
        while (sessions.size() > SESSION_LOGS_KEPT) {
            sessions.remove(0).delete();
        }
    }

    /** v19: create the CSV header once (file persists across boots for A/B stats). */
    private void ensureCsvHeader() {
        File f = new File(CSV_FILE);
        if (!f.isFile() || f.length() == 0) {
            writeLine(CSV_FILE, "ts_epoch,uptime_s,round,event,native_exit,ksu,enforce,temp,note", true);
        }
    }

    /** v20: max-zone temperature in "mdeg@zone" form (v19 took an unnamed
     * max which hid which sensor produced the reading). */
    private String readSoCTempText() {
        int max = 0;
        String zone = "unknown";
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones != null) {
            for (File z : zones) {
                if (!z.getName().startsWith("thermal_zone")) {
                    continue;
                }
                File t = new File(z, "temp");
                if (!t.isFile()) {
                    continue;
                }
                try {
                    int v = Integer.parseInt(readText(t.getAbsolutePath()).trim());
                    if (v > max && v < 100000) {
                        max = v;
                        String type = new File(z, "type").isFile()
                                ? readText(new File(z, "type").getAbsolutePath()).trim()
                                : z.getName();
                        zone = type.isEmpty() ? z.getName() : type;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return max + "@" + zone;
    }

    /**
     * v19 CSV row. Semantics:
     *  - "started" fires just before the attack: a row with no matching "done"
     *    means the device reset mid-attack (the intrinsic failure form).
     *    native_exit is "NA" here — a placeholder, never a real exit code.
     *  - "done" fires after the CLI returns (native exit + enforce/ksu state).
     *  - "success" fires once (root verified via su); "landed" = permissive/
     *    KSU present but su NOT verified; both carry magica status in note.
     */
    private void csvRow(String event, float uptimeS, int round, String nativeExit,
                        String enforce, boolean ksu, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append(System.currentTimeMillis() / 1000);
        sb.append(',').append(uptimeS);
        sb.append(',').append(round);
        sb.append(',').append(event);
        sb.append(',').append(nativeExit);
        sb.append(',').append(ksu ? "1" : "0");
        sb.append(',').append(enforce);
        sb.append(',').append(readSoCTempText());
        sb.append(',').append(note == null ? "" : note.replace(',', ';'));
        writeLine(CSV_FILE, sb.append('\n').toString(), true);
    }

    private void writeLine(String path, String line, boolean append) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(path, append)) {
            out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!line.endsWith("\n")) {
                out.write('\n');
            }
            out.flush();
            out.getFD().sync();
        } catch (IOException ignored) {
            /* telemetry is best-effort; never kill a run over it */
        }
    }

    private void log(ILogCallback callback, String line) {
        /* v19: narrative lines persist on disk first, then go to the UI.
         * After a natural exploit reset the screen is gone — the file isn't.
         * v23: mirror to logcat too (svc-side debugging when the Activity
         * view is absent, e.g. babysitter-triggered runs).
         * v27: master log (append) + this session's own file (append). */
        writeLine(APP_LOG_FILE, line, true);
        if (currentSessionFile != null) {
            writeLine(currentSessionFile, line, true);
        }
        android.util.Log.i("GhostRootSvc", "usvc: " + line);
        try {
            callback.onLog(line);
        } catch (RemoteException ignored) {
        }
    }

    private String seccompState() {
        String status = readText("/proc/self/status");
        for (String line : status.split("\n")) {
            if (line.startsWith("Seccomp:")) {
                return line.substring(8).trim();
            }
        }
        return "unknown";
    }

    private String readText(String path) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(path)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            return "";
        }
        return sb.toString();
    }
}
