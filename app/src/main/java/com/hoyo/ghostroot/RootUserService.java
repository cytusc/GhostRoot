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
    /** rounds per Shizuku session (one boot) — mirrors PC autoroot's cycle loop.
     * v45: 每轮都是独立 lottery 前提是"堆还干净"——fire 链一旦 LANDED（.fired
     * 匹配本 boot），循环立即终止，绝不二次开火（06:59 事故根因）。 */
    private static final int MAX_ROUNDS = 10;
    /** PC rhythm: ~20s cooldown between cycles. */
    private static final long ROUND_COOLDOWN_SECONDS = 20;
    /** v45: fire.sh v7 的落地标记 —— LANDED 后写本 boot 的 boot_id。
     * 同 boot 值匹配 = 该内核已被成功攻击过，再攻即 known wedge scenario。 */
    private static final String FIRED_PATH = "/data/local/tmp/.fired";
    /** v45: 跨工具门禁 —— timer 系 exploit 驱动器的 pidfile（同一台设备
     * 混用两套 root 工具会互相污染堆状态）。 */
    private static final String TIMER_PID_PATH = "/data/local/tmp/98260.pid";
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

    /** v38 (RootFamiliar checkKernel 借鉴): profile.bin 符号地址唯一对应的内核。 */
    private static final String EXPECTED_KERNEL_RELEASE = "5.15.197-g346b1c36bd5c-dirty";
    /** v38: 本机内核的 ksud uapi（2026-11-01 ksud-gk 配平实测）。 */
    private static final int EXPECTED_KSUD_UAPI = 5;
    /* v41: ZygiskNext late-load 自愈 —— bootloader 锁死导致 KSU 只能靠 exploit
     * 事后注入，zygote 永远早于 root 启动，ZN 的 zygote 注入竞态必输：表现
     * 为 HMA-OSS / LSPosed 全部"模块未激活"（2026-11-02 实测根因）。
     * 解法：单例 zn-daemon + framework 软重启（stop/start，~40s，KSU 内核
     * 模块与 Permissive 状态不受影响），让 daemon 捕获新 zygote —— 实测
     * zygote_states 0→2，HMA 状态目录 status.json 随即出现。 */
    private static final String ZN_MODDIR = "/data/adb/modules/zygisksu";
    private static final String ZN_BIN = ZN_MODDIR + "/bin/zygiskd";
    private static final String ZN_SOFTREBOOT_MARK = "/data/local/tmp/.gr_zn_softreboot";
    /** v60: 同一 boot 内软重启冷却 20min → 2min。20min 是 v58 自毒循环修复
     * 前的保守值（连点修复反复 stop/start 框架 = 软重启风暴，还会杀 Shizuku
     * server 与守护进程）；v58 起清失败态已夹在 stop 之前一刻执行，
     * post-fs-data 重跑时状态干净，注入成功后 zygotes>0 直接走 zn-ok 分支
     * 不再进冷却。软重启本身 ~60s 完成，2min 足够框架稳定，同时仍挡住
     * 连点。实证 2026-11-05 13:32：提权会话软重启后 20min 白等，三次修复
     * 触发全被冷却吃掉（zero 留痕），设备明明只差一次软重启。 */
    private static final int ZN_SOFTREBOOT_COOLDOWN_MIN = 2;
    private static final String ASSET_LIB = "libksud-fixed.so";
    private static final String ASSET_FIX = "fix.sh";
    /** v58: 框架软重启后复活 root app_process 守护类模块（SuToaster 等）。 */
    private static final String ASSET_REVIVE = "revive_module_daemons.sh";
    private static final String REVIVE_DIR = "/data/adb/ghostroot";
    private static final String REVIVE_PATH = REVIVE_DIR + "/revive_module_daemons.sh";
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
    /** v49: znRepair 的守卫位 —— 与 run() 的 running 分开，修复与提权
     * 互斥（同时跑会互抢 shell/ptrace），但状态互不污染。 */
    private final AtomicBoolean repairing = new AtomicBoolean(false);
    /** v39: kmsg 采集器可管理句柄 —— 此前线程/logcat 子进程即起即弃，任务
     * 收尾无法回收、重复启动会叠加采集器（review 2026-11-02 确认的缺陷）。 */
    private Thread kmsgFollowerThread;
    private java.lang.Process kmsgFollowerProcess;
    private volatile boolean kmsgFollowerStop;
    /* v69-diag(2): root dmesg 通道的句柄组 —— 字段语义与 kmsgFollower 同构 */
    private java.lang.Process dmesgFollowerProcess;
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
    /** v38 (RootFamiliar checkKernel 借鉴): uname -r（/proc/sys/kernel/osrelease）。
     * 与 profile 期望内核不符时 fail-fast，绝不在 OTA 后的陌生内核上开火。 */
    private String lastKernelRelease = "unknown";
    /** v38 (RootFamiliar EnvProbe 借鉴): 工作区可用性摘要 tmp:wx adb:r kallsyms:full。 */
    private String lastEnv = "unknown";
    /** v38 (RootFamiliar ManagerProbe.uapi 借鉴): /data/adb/ksud 的 (uapi: N)
     * 与内核 uapi 不匹配时自动换装 /data/local/tmp/ksud-gk（含 b0 通道兜底）。 */
    private String lastUapi = "not-checked";
    /** v41: ZygiskNext 激活结果 —— zn-ok-zygoteN / zn-softreboot-scheduled /
     * zn-zygote0-softreboot-cooldown(Nm) / zn-absent / zn-error:*。 */
    private String lastZn = "not-run";
    /** v42 (13:2x 复位事故修复): fire 链收尾状态 —— killed=exploit 进程已不在
     * / still-running=落地后 ghostlock 还在跑（管理器门禁据此拒绝放行）。 */
    private String lastFirek = "not-run";
    /** v48 (2026-11-03 netfix 故障分析): 落地后网络金丝雀。背景：vivo_net_fix 的
     * 190 条 sepolicy 规则走 sepolicy.rule 文件，由 ksud 在开机 post-fs-data
     * 注入 —— uapiGuard 即使当场把 uapi2 换成 uapi5，本 boot 的规则也补不上
     * （13:51 boot 实测：Enforcing + 规则未注入 → 网络死，canary 全 FAIL）。
     * 本字段只做记录与归因（net=ok / dead-enforcing-reboot-pending / ...），
     * 是否 reboot 由 autoloop/用户决策 —— sepolicy 注入发生在下次 boot。 */
    private String lastNet = "not-checked";
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

    @Override
    public String openLsposedManager() {
        ShellResult who = rootShell("id -u", 12);
        if (!who.ok() || !"0".equals(who.out.trim())) {
            return "✗ Shizuku 通道无法取得 root（exit=" + who.exit
                    + ", timeout=" + who.timedOut + "）: " + who.out.trim();
        }
        ShellResult module = rootShell("test -f /data/adb/modules/zygisk_lsposed/daemon"
                + " && test ! -f /data/adb/modules/zygisk_lsposed/disable"
                + " && test ! -f /data/adb/modules/zygisk_lsposed/remove", 12);
        if (!module.ok()) {
            return "✗ LSPosed 模块未安装、已停用或无法访问（exit=" + module.exit
                    + ", timeout=" + module.timedOut + "）: " + module.out.trim();
        }
        ShellResult launch = rootShell("am start -W -a android.intent.action.MAIN"
                + " -c org.lsposed.manager.LAUNCH_MANAGER"
                + " -n com.android.shell/.BugreportWarningActivity", 15);
        boolean started = launch.ok() && launch.out.contains("Status: ok")
                && !launch.out.contains("Error:") && !launch.out.contains("Exception");
        return new StringBuilder().append(started
                ? "✅ 已发送 LSPosed 管理器启动请求（框架是否激活请以管理器显示为准）\n"
                : "✗ LSPosed 管理器启动失败\n")
                .append("exit=").append(launch.exit).append(", timeout=")
                .append(launch.timedOut).append('\n').append(launch.out.trim()).toString();
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

    /** v49: ZN 一键修复（手机端手动触发，不依赖 PC）。
     * 与 run() 用不同守卫位 —— 修复与提权互斥但互不干扰状态。
     * 链路复用 zygiskAutoActivate（v49 已含闩锁前置清除 + status 失败补
     * daemon），手动语义额外保证：无论命中哪条分支都清一次 dex2oat-flags
     * （幂等），盖住 cooldown/absent 分支不清理的空档。
     * rc: 0=完成, 2=忙碌, 3=root 未落地, 其他=失败。 */
    @Override
    public void znRepair(ILogCallback callback) {
        if (!repairing.compareAndSet(false, true)) {
            try {
                callback.onLog("another repair is already in progress");
                callback.onDone(2);
            } catch (RemoteException ignored) {
            }
            return;
        }
        Thread worker = new Thread(() -> {
            int exit = 0;
            try {
                log(callback, "===== ZN 一键修复开始（手动触发）=====");
                /* 修复链全部走 su —— root 未落地时任何动作都没有意义，
                 * 明确报 3 让 UI 提示先提权，而不是让一串 shell 失败淹没原因 */
                ShellResult who = rootShell("id -u", 10);
                String uid = who.out == null ? "" : who.out.trim();
                if (!who.ok() || !"0".equals(uid)) {
                    log(callback, "root 未落地（id -u = " + uid + "）—— 先「开始执行」提权，再回来修复");
                    exit = 3;
                } else {
                    log(callback, "root 就绪（uid=0），进入自愈链…");
                    /* v58: 修复入口先复活死掉的守护类模块进程（SuToaster 等）
                     * —— 软重启杀了它们而 KernelSU 不会重跑模块启动脚本，
                     * 这正是模块属性里 "(❌Disabled)" 反复出现的根源 */
                    reviveModuleDaemons(callback);
                    /* v61: 防复发自愈 —— module.prop 被清空/损坏会让 daemon
                     * 静默退出、注入窗口错过（本次 zygisk 修复失灵的根因）。
                     * 幂等：prop 空则从 orig 恢复；orig 缺失则从 prop 补。
                     * 两者并存时 daemon 才能完成初始化并注入。 */
                    rootShell("[ -s " + ZN_MODDIR + "/module.prop ] && cp " + ZN_MODDIR
                            + "/module.prop " + ZN_MODDIR + "/module.prop.tmp1 || cp "
                            + ZN_MODDIR + "/module.prop.orig " + ZN_MODDIR + "/module.prop; "
                            + "rm -f " + ZN_MODDIR + "/module.prop.tmp1; "
                            + "[ -f " + ZN_MODDIR + "/module.prop.orig ] || cp "
                            + ZN_MODDIR + "/module.prop " + ZN_MODDIR + "/module.prop.orig", 10);
                    String r = zygiskAutoActivate(callback);
                    log(callback, "自愈链结论: " + r);
                    /* 手动语义兜底：幂等清除 dex2oat-flags（工行保活，
                     * 用户决策 2026-11-04） */
                    deleteDex2oatFlags(callback, "repair");
                    /* 终态回读 —— 用户要的是"修没修好"的直观证据 */
                    ShellResult st = rootShell(ZN_BIN + " status 2>/dev/null", 20);
                    if (st.ok() && st.out != null) {
                        for (String line : st.out.split("\n")) {
                            String l = line.trim();
                            if (l.startsWith("zygote_states") || l.startsWith("zygote_state_")
                                    || l.startsWith("modules64") || l.startsWith("modules32")
                                    || l.startsWith("modules_with_issue")) {
                                log(callback, "status: " + l);
                            }
                        }
                    } else {
                        log(callback, "status 不可读（daemon 可能仍未起）");
                    }
                    log(callback, "提示: 若上面安排了 soft-reboot，框架将重启 ~40s，"
                            + "期间界面卡住属正常；结束后可再点一次「Zygisk 修复」复查终态");
                    /* v49: 修复成功留痕 —— 手机端「已修复」按钮状态依据本
                     * 文件 + 本 boot 时效（ts 距今 <= uptime）判定。framework
                     * 软重启（stop/start）不清 uptime，状态跨软重启保留；
                     * 整机重启后自然失效。仅 zn-ok / softreboot-scheduled
                     * 两种成功形态留痕 —— zn-absent/ 竞态错误不写，避免
                     * "已修复"误导（按钮保持可点，日志里有真实原因）。 */
                    if (r.startsWith("zn-ok") || r.equals("zn-softreboot-scheduled")) {
                        writeLine(TMP + "/.gr_zn_repair",
                                "ts=" + System.currentTimeMillis() / 1000
                                        + " uptime=" + readUptime() + "s outcome=" + r + "\n",
                                false);
                        log(callback, "修复留痕: " + TMP + "/.gr_zn_repair");
                        /* v51: 同步回写 verdict 的 zn= 字段 —— 否则 UI 的
                         * 「已修复」判定（读 verdict zn 前缀）永远停留在
                         * 会话留下的旧值（如 zn-softreboot-scheduled），
                         * 修复按钮永远停在「软重启后建议再点复查」态。
                         * 只替换 zn= 行，不动 ts/session/verdict（rootLanded
                         * 的本 boot 时效判定依赖原 ts）。 */
                        if (!VERDICT_FILE.isEmpty()) {
                            rootShell("sed -i 's/^zn=.*/zn=" + r + "/' " + VERDICT_FILE
                                    + " 2>/dev/null", 10);
                        }
                    } else {
                        /* v55: 链路跑完 ≠ 修好 —— 非成功结论显式失败（exit=4），
                         * UI 不再显示「✅ 修复完成」；按钮状态依据留痕判定
                         * 本来就不会误亮，这里补齐退出码与文案的一致性。 */
                        log(callback, "⚠ Zygisk 未激活（结论 " + r + "）—— 本次修复按未完成处理");
                        exit = 4;
                    }
                }
            } catch (Throwable e) {
                log(callback, "repair error: " + e);
                exit = 1;
            } finally {
                repairing.set(false);
            }
            try {
                callback.onDone(exit);
            } catch (RemoteException ignored) {
            }
        }, "ghostroot-zn-repair");
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
        lastKernelRelease = "unknown";
        lastEnv = "unknown";
        lastUapi = "not-checked";
        lastNet = "not-checked";
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

            /* v38 (RootFamiliar checkKernel 借鉴): 先确认内核再开火。
             * profile.bin 的符号地址只对这一个内核有效 —— OTA/换内核后
             * 乱开火就是给 vr.ko 送采样。 */
            /* v39: /proc/sys/kernel/osrelease 被 vivo 策略禁读（Permission denied），
             * 只有 uname -r 可用 —— 别改回 readText。 */
            lastKernelRelease = plainShell("uname -r", 10).out.trim();
            if (!EXPECTED_KERNEL_RELEASE.equals(lastKernelRelease)) {
                log(callback, "ABORT: kernel mismatch — running [" + lastKernelRelease
                        + "], profile needs [" + EXPECTED_KERNEL_RELEASE + "]");
                lastVerdict = -1;
                lastRound = 0;
                lastReason = "kernel-mismatch:" + lastKernelRelease;
                log(callback, "exit=-4");
                return;
            }
            log(callback, "kernel ok: " + lastKernelRelease);

            /* v38 (RootFamiliar EnvProbe 借鉴): 工作区可用性一探到底，
             * 结果进 verdict —— 失败现场不再靠猜。 */
            lastEnv = envProbe();
            log(callback, "env probe: " + lastEnv);

            /* v29: PC autoroot's FIRST action every cycle is root_ok — if
             * root is already live on this boot it exits without attacking.
             * The App lacked this gate: the 11-01 10:26 reset happened when a
             * fresh attack ran on a boot where KSU was ALREADY loaded (root
             * verified 10:25). Attacking a KSU-loaded/polluted heap is the
             * known wedge scenario — never do it. */
            String preSu = suCheck();
            boolean preKsu = readText("/proc/modules").contains("kernelsu");
            /* v45: fire.sh v7 落地标记 + 跨工具 exploit 门禁 —— 三者任一命中
             * 都说明本 boot 的堆已被攻击过/根已活，继续开火即 polluted-heap
             * wedge 场景（06:59:52 PS_HOLD 硬复位事故的直接根因）。 */
            boolean preFired = firedThisBoot();
            boolean preForeign = foreignExploitRunning();
            if (preSu.contains("uid=0") || preKsu || preFired || preForeign) {
                boolean verified = preSu.contains("uid=0");
                log(callback, verified
                        ? "ROOT ALREADY LIVE on this boot (su -c id: " + preSu.trim() + ") — skipping attack entirely (PC root_ok gate)"
                        : preKsu
                        ? "KernelSU module already loaded but root unverified (" + preSu.trim() + ") — skipping attack (polluted-heap guard)"
                        : preFired
                        ? "fire chain already LANDED this boot (.fired boot_id match) but root unverified (" + preSu.trim() + ") — skipping attack (polluted-heap guard)"
                        : "another exploit driver (98260) is live on this boot — skipping attack (cross-tool heap guard)");
                lastVerdict = verified ? 0 : 1;
                lastRound = 0;
                lastSu = preSu.trim();
                lastReason = verified ? "root-already-live-skip-attack"
                        : preKsu ? "ksu-already-loaded-skip-attack"
                        : preFired ? "fired-already-this-boot-skip-attack"
                        : "foreign-exploit-live-skip-attack";
                if (verified) {
                    exit = 0;
                    /* v70: 本 boot root 已活、不打 exploit 的会话同样要先
                     * 续上 dmesg 跟随器 —— 设备长期停在已提权态时，这是
                     * 唯一会自动重建内核日志通道的入口（命中 boot 的
                     * verifyLanded 只跑一次；进程被杀/Server 重启后靠这里
                     * 收拾孤儿）。幂等：同 JVM 已有活写者则直接跳过。 */
                    startDmesgFollower(callback);
                    lastMagica = runMagicaFix(callback);
                    /* v33: 管理器提示必须在补丁检查之后，且按结果分流 ——
                     * 不能在检查前就说"直接打开管理器"。 */
                    boolean mgrOk = "manager-patched".equals(lastMagica)
                            || "already-patched".equals(lastMagica);
                    /* v42: 管理器门禁（already-rooted 路径）—— 本 boot 未跑
                     * exploit，仍防同 boot 前序会话残留的 ghostlock 进程。 */
                    lastFirek = exploitRunning() ? "still-running" : "killed";
                    log(callback, "已在本 boot 拿到 root（无需再打）；管理器门禁="
                            + "magica:" + lastMagica + "/exploit:" + lastFirek
                            + (mgrOk && "killed".equals(lastFirek)
                            ? " → 可直接打开 KernelSU 管理器"
                            : " → 暂勿打开管理器（"
                            + (!mgrOk ? "补丁未通过，先跑 PC fix.sh"
                            : "存在残留 ghostlock 进程，先 pkill -9 -x ghostlock") + "）"));
                    /* v30/v31: 采样入口覆盖 already-rooted 路径 —— root 活着
                     * 但不攻击的会话同样要抓 pstore/取证快照 */
                    attemptPstoreSample(callback, true);
                    /* v42: tombstone 归档（root 通道活着时做） */
                    archiveTombstones(callback);
                    /* v35: W1c 摘除去证 —— already-rooted 路径同样验证 */
                    lastVrDisarmed = vrDisarmProbe();
                    log(callback, "vr_disarmed=" + lastVrDisarmed + " (W1c dmesg evidence)");
                    /* v38 (RootFamiliar ManagerProbe.uapi 借鉴): ksud uapi 守卫 */
                    lastUapi = uapiGuard(callback);
                    log(callback, "uapi=" + lastUapi);
                    /* v48: 网络金丝雀（already-rooted 路径同样归因） */
                    lastNet = netCanary(callback);
                    log(callback, "net=" + lastNet);
                    /* v41: ZygiskNext 自愈（HMA-OSS/LSPosed 激活）—— 放最后，
                     * 万一触发软重启也不影响前面的取证与守卫步骤 */
                    lastZn = zygiskAutoActivate(callback);
                    log(callback, "zygisk=" + lastZn);
                } else {
                    log(callback, "仅检测到 KSU 模块、身份未验证 —— 不要打开管理器，"
                            + "先确认补丁状态（PC: su -c sh /data/local/tmp/fix.sh）");
                }
                writeState("already-rooted", 0);
                csvRow("already-rooted", readUptime(), 0, String.valueOf(exit),
                        readText("/sys/fs/selinux/enforce").trim(), preKsu, verified ? "root-live" : "landed-unverified");
                return;
            }
            log(callback, "no KSU / no fired mark / no foreign driver on this boot — clean heap, attack allowed");

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

            /* v37 (borrowed from DirtyFrag-vr kmsglog): 内核日志逐行 fsync 跟随器，
             * 走 logcat -b kernel 通路（v36 的 /dev/kmsg 直读被 SELinux 拒）。
             * wedge/watchdog 复位会吃掉页缓存（pstore 也经常缺失），逐行 sync 落盘
             * 让复位前的内核输出留存到 /data/local/tmp/ghostroot-kmsg.log。 */
            startKmsgFollower(callback);

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
                    /* v45: 冷却判据升级 —— /proc/modules 对运行时 hook 移植
                     * 是盲的（06:59 事故：KSU 已活但模块表无 kernelsu，
                     * round 3 照打）。三层判据任一命中即终止：
                     *   1. /proc/modules 含 kernelsu（传统信号）
                     *   2. su -c id 含 uid=0（root 已活，最终裁决）
                     *   3. .fired == 本 boot_id（fire 链已看见落地并开过链） */
                    if (readText("/proc/modules").contains("kernelsu")) {
                        log(callback, "KernelSU appeared during cooldown (fire chain)");
                        break;
                    }
                    if (firedThisBoot()) {
                        log(callback, "fire chain LANDED during cooldown (.fired boot_id match) — single-shot guard, no re-attack");
                        break;
                    }
                    String cdSu = suCheck();
                    if (cdSu.contains("uid=0")) {
                        log(callback, "root went live during cooldown (su -c id: " + cdSu.trim() + ") — single-shot guard, no re-attack");
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
                /* v45: .fired 匹配本 boot = fire 链在本轮攻击期间看见过落地
                 * （permissive 或 uid=0）并已开链 —— 这是对运行时 hook 移植
                 * 唯一可靠的落地信号（enforce 可能被链内 fixup 恢复、模块表
                 * 不可见）。命中即按 landed 处理，绝不进入下一轮。 */
                boolean firedNow = firedThisBoot();
                if (ksu || "0".equals(enforce) || firedNow) {
                    if (firedNow && !ksu && !"0".equals(enforce)) {
                        log(callback, "landing detected via .fired boot_id match only (enforce restored / module invisible) — treating as LANDED");
                    }
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
             * 判据验证 su，再决定是"landed"还是"耗尽"。 v45: 补 .fired 判据
             * （cooldown 单发守卫 break 的主路径就是它）。 */
            String enforceAfter = readText("/sys/fs/selinux/enforce").trim();
            boolean ksuAfter = readText("/proc/modules").contains("kernelsu");
            boolean firedAfter = firedThisBoot();
            if (ksuAfter || "0".equals(enforceAfter) || firedAfter) {
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
            /* v39: 任务收尾回收 kmsg 采集器（线程 + logcat 子进程）—— 此前
             * 无句柄无回收，UserService 存活期间多次任务会残留/叠加采集器 */
            stopKmsgFollower();
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
        /* v38: RootFamiliar 借鉴三件套 —— 内核校验/环境探测/uapi 守卫 */
        sb.append("krn=").append(lastKernelRelease).append('\n');
        sb.append("env=").append(lastEnv).append('\n');
        sb.append("uapi=").append(lastUapi).append('\n');
        /* v41: ZygiskNext 激活结果（zn-ok-zygoteN / zn-softreboot-scheduled / ...） */
        sb.append("zn=").append(lastZn).append('\n');
        /* v48: 落地后网络金丝雀（ok / dead-enforcing-reboot-pending / dead-permissive） */
        sb.append("net=").append(lastNet).append('\n');
        /* v42: fire 链收尾状态 —— killed / still-running（管理器门禁依据） */
        sb.append("firek=").append(lastFirek).append('\n');
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
        /* v47: 先刷一份 interim verdict —— 本机 KSU 移植模块表不可见，下面的
         * 等待循环可能空转 90s，且后续 magica/pstore/zn 步骤耗时更长；期间
         * 进程若被 LMK 杀掉（v46 实测：ROUND 2 END 后进程消失，verdict 全丢），
         * 旧逻辑只在 finally 写一次 verdict，结果完全丢失。此刻先以
         * su -c id 探测为准落一份 interim，finally 用最终结果覆盖。 */
        String preSu = suCheck();
        lastSu = preSu.trim();
        lastRound = round;
        boolean preVerified = lastSu.contains("uid=0");
        lastVerdict = preVerified ? 0 : 1;
        lastReason = preVerified ? "landed-su-verified-interim" : "landed-pending-verify";
        writeVerdictFile(exit);
        log(callback, "interim verdict flushed: " + lastReason);
        for (int i = 0; i < 18 && !readText("/proc/modules").contains("kernelsu")
                && !lastSu.contains("uid=0"); i++) {
            Thread.sleep(5000);
            /* v47: su 早退判据（与 fire.sh v7 ksu_wait 同款）—— 模块表盲区
             * 下不再固定空等 90s 才进入正式验证 */
            lastSu = suCheck().trim();
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
            /* v70 (2026-11-07): root 一活就先钉住 dmesg 落盘通道 —— 放在
             * 整个收尾链最前（早于 pstore 快照）。本 ROM 三条取证通道的
             * 当日实证：pstore 每次 boot 被 vendor 消费/清空、logcat
             * -b kernel 对 shell 全天零输出、lockup 型冻结不产生 oops；
             * 唯一活通道就是 root dmesg -w 的「全量历史缓冲 + 逐行
             * fsync」。异步启动几乎不耗时，越早接住内核输出越好；
             * follower 不随会话收尾回收，覆盖命中后 ~20min 的延迟冻结
             * 窗口（10:45 那次复现）。 */
            startDmesgFollower(callback);
            /* v69-diag (移植自实验分支 v76 9027fef): 收尾链重排序 ——
             * attemptPstoreSample 提到最前。理由：本日 2026-11-07 两次
             * "落地→收尾窗口复位"（09:02 / 09:08 命中后 boot_id 变化）把
             * root 连同当轮 verdict 一起送走，pstore 里存着的历次内核
             * panic 现场（wedge 的真正归因证据）始终没被读出来。pstore
             * 采样是只读快照，排在所有会动 ksud/框架的步骤（magica/
             * uapi/zygisk）之前：哪怕收尾链后面再出事，现场（含上一个
             * panic）也已归档落袋。 */
            attemptPstoreSample(callback, true);
            /* v18: root is live on this boot — patch the manager's libksud
             * now so opening KernelSU manager can never re-run magica
             * cold-start (the wedge/reboot trap). */
            magica = runMagicaFix(callback);
            lastMagica = magica;
            /* v35: W1c 摘除去证 —— one.sh v5 用 dfroot_vr.ko 摘除 vr.ko
             * sys_exit 探针，dmesg 的 "W1c vr sys_exit probe disarmed" 是唯一证据行 */
            lastVrDisarmed = vrDisarmProbe();
            log(callback, "vr_disarmed=" + lastVrDisarmed + " (W1c dmesg evidence)");
            /* v38 (RootFamiliar ManagerProbe.uapi 借鉴): ksud uapi 守卫 */
            lastUapi = uapiGuard(callback);
            log(callback, "uapi=" + lastUapi);
            /* v48: 网络金丝雀（uapiGuard 之后 —— uapi 换装不影响本 boot 规则，
             * 金丝雀 DEAD+Enforcing 即归因到 sepolicy.rule 待下次 boot 注入） */
            lastNet = netCanary(callback);
            log(callback, "net=" + lastNet);
            /* v41: ZygiskNext 自愈（HMA-OSS/LSPosed 激活）—— 放最后，
             * 万一触发软重启也不影响前面的取证与守卫步骤 */
            lastZn = zygiskAutoActivate(callback);
            log(callback, "zygisk=" + lastZn);
            /* v42 (13:2x 复位事故修复): 管理器门禁 —— magica 补丁通过 且
             * exploit 已被 fire 链终止，两个条件齐备才放行。事故现场：fire 链
             * 卡死在 b0、exploit_killed 从未执行，ghostlock 的 W2/W3 继续
             * 写已落地的内核；此时打开管理器（ksud 内核侧活动）叠加即
             * 触发 init abort → 重启。 */
            boolean mgrOk = "manager-patched".equals(magica)
                    || "already-patched".equals(magica);
            /* fire.sh v6 在 KSU 出现后 0-20s 内杀 ghostlock；给一个有界等待再判定 */
            boolean exploitGone = !exploitRunning();
            for (int w = 0; !exploitGone && w < 25; w++) {
                Thread.sleep(1000);
                exploitGone = !exploitRunning();
            }
            lastFirek = exploitGone ? "killed" : "still-running";
            log(callback, "管理器门禁: magica=" + magica + " exploit=" + lastFirek
                    + (mgrOk && "killed".equals(lastFirek)
                    ? " → 可打开 KernelSU 管理器"
                    : " → 暂勿打开管理器（" + (!mgrOk
                    ? "补丁未通过，先跑 fix.sh"
                    : "exploit 仍在跑：等 .fire.log 出现 exploit_killed，或手动 pkill -9 -x ghostlock")
                    + "）"));
            /* v42: 趁 root 通道活着归档 tombstone（dropbox 回收不可靠） */
            archiveTombstones(callback);
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

    /** v37: 内核日志逐行 fsync 跟随器（borrowed from DirtyFrag-vr kmsglog）。
     * v36 的 /dev/kmsg 直读在 enforcing 下被 SELinux 拒（shell 域 kmsg_device read
     * 不放行，实测 Permission denied）→ 改走 logd 代理：`logcat -b kernel -v raw`
     * 持续流式读取（logd 以特权身份读 kmsg，shell→logd socket 放行）。
     * 每行落盘后 FileDescriptor.sync() 强推 —— watchdog 复位不经过正常关机也
     * 留得住复位前的内核日志。
     * v39 修复（review 2026-11-02 三项）：
     *  1) 线程/子进程句柄存为字段，启动前与任务收尾都先 stopKmsgFollower ——
     *     UserService 存活期间多次调用不再叠加采集器；
     *  2) 达到 16MB 滚动成 .1 后继续采集（旧行为达上限后只写标记不再读正文，
     *     后续故障现场全部丢失）；
     *  3) "已启动"提示改为收到首行内核日志后才确认；进程早退且从未读到数据时
     *     显式上报，采集失败不再被误认为正常。 */
    private void startKmsgFollower(ILogCallback callback) {
        final String KMSG_LOG = "/data/local/tmp/ghostroot-kmsg.log";
        final long MAX_BYTES = 16L * 1024 * 1024;
        /* v39: 幂等 —— 先回收可能残留的旧采集器，再起一个新的 */
        stopKmsgFollower();
        try {
            /* v39 rotation: 文件已到上限则先滚动成 .1，从新文件开始采集 */
            File f = new File(KMSG_LOG);
            if (f.length() >= MAX_BYTES) {
                File rot = new File(KMSG_LOG + ".1");
                rot.delete();
                log(callback, "kmsg log rotated: " + f.length() + " bytes -> "
                        + rot.getName());
                f.renameTo(rot);
            }
            kmsgFollowerStop = false;
            kmsgFollowerProcess = new java.lang.ProcessBuilder(
                    "logcat", "-b", "kernel", "-v", "raw").start();
            final java.lang.Process proc = kmsgFollowerProcess;
            final AtomicBoolean firstData = new AtomicBoolean(false);
            Thread t = new Thread(() -> {
                try (java.io.BufferedReader in = new java.io.BufferedReader(
                        new InputStreamReader(proc.getInputStream()))) {
                    java.io.FileOutputStream out = openKmsgOut(KMSG_LOG);
                    try {
                        String ln;
                        while (!kmsgFollowerStop && (ln = in.readLine()) != null) {
                            /* v39 rotation: 落盘途中达上限也滚动 .1 继续采集 */
                            if (out.getChannel().size() >= MAX_BYTES) {
                                out.close();
                                File rot = new File(KMSG_LOG + ".1");
                                rot.delete();
                                new File(KMSG_LOG).renameTo(rot);
                                out = openKmsgOut(KMSG_LOG);
                            }
                            if (firstData.compareAndSet(false, true)) {
                                log(callback, "kmsg follower confirmed: first kernel line received");
                            }
                            out.write((ln + "\n").getBytes());
                            out.getFD().sync();   // per-line fsync — the whole point
                        }
                    } finally {
                        try {
                            out.close();
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Throwable e) {
                    if (!kmsgFollowerStop) {
                        try {
                            log(callback, "kmsg follower ended: " + e);
                        } catch (Exception ignored) {
                        }
                    }
                } finally {
                    /* v39: 进程早退且从未读到数据 —— 显式上报，
                     * 不能用日志为空排除系统异常 */
                    if (!firstData.get() && !kmsgFollowerStop) {
                        try {
                            log(callback, "kmsg follower WARNING: no kernel data received"
                                    + " (check `logcat -b kernel` availability)");
                        } catch (Exception ignored) {
                        }
                    }
                }
            }, "kmsg-follower");
            t.setDaemon(true);
            kmsgFollowerThread = t;
            t.start();
            /* v39: 措辞改为"launching" —— 确认以收到首行为准，见 confirmed 行 */
            log(callback, "kmsg follower launching (logcat -b kernel) -> " + KMSG_LOG);
        } catch (Throwable e) {
            /* v39: spawn 失败必须说清楚，不能只报已启动 */
            try {
                log(callback, "kmsg follower start FAILED: " + e);
            } catch (Exception ignored) {
            }
        }
    }

    /** v39: 打开（或滚动后重开）kmsg 日志文件，写开始标记并强推落盘。 */
    private java.io.FileOutputStream openKmsgOut(String path) throws java.io.IOException {
        return openKmsgOut(path, "kmsg follower");
    }

    /** v70: 带通道 tag 的重载 —— dmesg 日志不能沿用 "kmsg follower"
     * 的 start 标记，否则归因时两条通道的启动行无法区分（历史上就是用
     * `grep -v "kmsg follower start"` 过滤空转 kmsg 日志的）。 */
    private java.io.FileOutputStream openKmsgOut(String path, String tag)
            throws java.io.IOException {
        java.io.FileOutputStream out = new java.io.FileOutputStream(path, true);
        out.write(("=== " + tag + " start ts=" + System.currentTimeMillis() / 1000
                + " uptime=" + readUptime() + "s ===\n").getBytes());
        out.getFD().sync();
        return out;
    }

    /** v69-diag(2) (2026-11-07): root dmesg 落盘跟随器 —— 本 ROM 归因的
     * 唯一活内核日志通道。当日实证三连：logcat -b kernel 对 shell/power
     * uid 全天零输出（kmsgFollower 空转一整天，只有 start 行没有内容）；
     * /sys/fs/pstore 在每次 boot 被 vendor 消费/清空（"[PSTORE] readable
     * and EMPTY…"全命中），且 lockup 型冻结不产生 oops、pstore 本就为空；
     * 冻结瞬间任何在线读取都不可能 —— 所以唯一路径就是“存量 dmesg
     * 缓冲 + 逐行 fsync 落盘”，冻结瞬间写入停止，已落盘内容跨重启保留。
     *
     * 语义与 kmsgFollower 同构：rotation、per-line fsync、no-data 显式
     * 上报。差异只有三点 —— ① 数据源是 `su -c dmesg -w`（缓冲区先吐
     * 全量历史再 follow，因此开跟随即包含本 boot 全部 W1/W1b/PI route
     * 内核侧痕迹）；② 只在 root 验证通过后调用 —— 2026-11-07 11:37
     * 手动重启后的干净 boot 实测：未提权时 shell(uid 2000) 跑 dmesg
     * 直接 "klogctl: Permission denied"（dmesg_restrict=0 也无效，是
     * SELinux 拦 klogctl），而 root 活着的 boot 上同一命令可读可
     * follow。所以「攻击前就起跟随器」在这台 ROM 上不成立，两个调用
     * 点绝不能上移；③ 不在 finally 里回收 —— 故意让它跨会话
     * 存活直至下一次 reboot（钉住 post-land 延迟冻结窗口，如 10:45 那次
     * 命中 ~20min 后的 UI 冻结）。重复调用幂等：同 JVM 存活则跳过；
     * 跨 JVM 孤儿用 root pkill 收掉再起新写者。 */
    private void startDmesgFollower(ILogCallback callback) {
        final String DMESG_LOG = "/data/local/tmp/ghostroot-dmesg.log";
        final long MAX_BYTES = 32L * 1024 * 1024;
        try {
            /* 同 JVM 已有活写者 → 幂等跳过（跨会话持续写是设计语义） */
            java.lang.Process old = dmesgFollowerProcess;
            if (old != null && old.isAlive()) {
                log(callback, "dmesg follower already running — keeping the single writer");
                return;
            }
            /* 跨 JVM 孤儿（Shizuku server 重启后句柄丢失）：root pkill
             * 收旧再起新，保证同一时刻只有一个写者把 append 文件写穿。
             * v70: 必须按进程名精确匹配 -x，不能用 -f —— -f 会匹配完整
             * 命令行，承载 pkill 的 su/sh 自身命令行就含 "dmesg -w" 字面
             * 串，等于自杀：rootShell 拿不到返回、超时 10s，后续启动还会
             * 被误判成失败。实测本 ROM Toybox 0.8.12 pkill 支持 -x。 */
            rootShell("pkill -x dmesg 2>/dev/null; true", 10);
            File f = new File(DMESG_LOG);
            if (f.length() >= MAX_BYTES) {
                File rot = new File(DMESG_LOG + ".1");
                rot.delete();
                log(callback, "dmesg log rotated: " + f.length() + " bytes -> "
                        + rot.getName());
                f.renameTo(rot);
            }
            dmesgFollowerProcess = new java.lang.ProcessBuilder(
                    "su", "-c", SH_ENV + " dmesg -w").start();
            final java.lang.Process proc = dmesgFollowerProcess;
            final AtomicBoolean firstData = new AtomicBoolean(false);
            Thread t = new Thread(() -> {
                try (java.io.BufferedReader in = new java.io.BufferedReader(
                        new InputStreamReader(proc.getInputStream()))) {
                    java.io.FileOutputStream out = openKmsgOut(DMESG_LOG, "dmesg follower");
                    try {
                        String ln;
                        while ((ln = in.readLine()) != null) {
                            if (out.getChannel().size() >= MAX_BYTES) {
                                out.close();
                                File rot = new File(DMESG_LOG + ".1");
                                rot.delete();
                                new File(DMESG_LOG).renameTo(rot);
                                out = openKmsgOut(DMESG_LOG, "dmesg follower");
                            }
                            if (firstData.compareAndSet(false, true)) {
                                log(callback, "dmesg follower confirmed: first kernel line received "
                                        + "(boot history up to now included)");
                            }
                            out.write((ln + "\n").getBytes());
                            out.getFD().sync();   /* per-line fsync — 整个方案的意义所在 */
                        }
                    } finally {
                        try {
                            out.close();
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Throwable e) {
                    try {
                        log(callback, "dmesg follower ended: " + e);
                    } catch (Exception ignored) {
                    }
                }
            }, "dmesg-follower");
            t.setDaemon(true);
            t.start();
            log(callback, "dmesg follower launching (root su -c dmesg -w) -> " + DMESG_LOG);
            /* v70: 启动自检 —— 3s 内没收到首行必须显式告警。不能只靠采集
             * 线程 finally 里的 no-data 上报：dmesg -w 正常会一直阻塞不返回，
             * 「通道死了但进程还挂着」时 finally 永远不会执行，正是
             * kmsgFollower 空转一整天、日志里只有 start 行却无人察觉的
             * 同款盲点。宁可多花 3s，不能再白等一天。 */
            for (int i = 0; i < 6 && !firstData.get(); i++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!firstData.get()) {
                log(callback, "dmesg follower WARNING: no kernel line in 3s "
                        + "(su grant / dmesg -w / SELinux? channel may be dead)");
            }
        } catch (Throwable e) {
            try {
                log(callback, "dmesg follower start FAILED: " + e);
            } catch (Exception ignored) {
            }
        }
    }

    /** v39: 回收 kmsg 采集器 —— 置停止标志并 destroy logcat 子进程让
     * readLine 尽快返回，join 收线程。幂等，可重复调用（含并发自调用防护：
     * 采集线程自身不会走到这里）。 */
    private void stopKmsgFollower() {
        kmsgFollowerStop = true;
        java.lang.Process p = kmsgFollowerProcess;
        kmsgFollowerProcess = null;
        if (p != null) {
            p.destroy();
        }
        Thread t = kmsgFollowerThread;
        kmsgFollowerThread = null;
        if (t != null && Thread.currentThread() != t) {
            try {
                t.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** v35: DirtyFrag-vr W1c 摘除去证 —— root 活着时读 dmesg 找
     * "W1c vr sys_exit probe disarmed"（one.sh v5 摘除动作的唯一指纹行）。
     * 返回 "1"（已摘除）/ "0"（dmesg 可读但无此行）/ "unknown"（dmesg 不可读）。
     * 注意：shell 域读 dmesg 受限时绝不能误报 0，必须落 unknown。 */
    /** v38 (RootFamiliar EnvProbe 借鉴): 工作区可用性探测，shell 身份即可做。
     * 产出紧凑摘要 —— tmp 可写+可执行 / adb 可读 / kallsyms 是否被 kptr_restrict
     * 归零。任何一项异常都会写进 verdict，失败现场不用再猜。 */
    private String envProbe() {
        StringBuilder sb = new StringBuilder();
        /* tmp: 写+执行双测 —— 只能写不能执行的沙箱会把整条链路卡死 */
        boolean w = false, x = false;
        try {
            File probe = new File(TMP, ".gr_envprobe.sh");
            writeLine(probe.getAbsolutePath(), "#!/system/bin/sh\necho GR_ENV_OK", false);
            probe.setExecutable(true, false);
            java.lang.Process p = new ProcessBuilder(probe.getAbsolutePath()).start();
            boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            String out = "";
            if (done) {
                byte[] b = new byte[64];
                int n = p.getInputStream().read(b);
                out = n > 0 ? new String(b, 0, n) : "";
            }
            x = done && out.contains("GR_ENV_OK");
            w = probe.isFile();
            probe.delete();
        } catch (Throwable ignored) {
        }
        sb.append("tmp:").append(w ? "w" : "-").append(x ? "x" : "-");
        /* adb: vivo LSM 对 /data/adb 的访问是间歇的，只读探测记录现状 */
        File adb = new File("/data/adb");
        sb.append(" adb:").append(adb.canRead() ? "r" : "-");
        /* kallsyms: kptr_restrict 归零时地址列全 0，root 前只能确认这个 */
        String ks = readText("/proc/kallsyms");
        if (ks.isEmpty()) {
            sb.append(" kallsyms:-");
        } else {
            boolean zeroed = ks.contains(" 0000000000000000 ");
            sb.append(" kallsyms:").append(zeroed ? "restricted" : "full");
        }
        return sb.toString();
    }

    /** v38 (RootFamiliar ManagerProbe.uapi 借鉴): ksud uapi 守卫。读
     * /data/adb/ksud -V 的 "(uapi: N)"，与内核期望不符时自动换装
     * /data/local/tmp/ksud-gk（uapi:5）。直写被 vivo LSM 拒时退到
     * b0 atcid_vendor_socket 通道（2026-11-02 实测可用）。尽力而为，
     * 失败只记录不阻塞 —— 换装失败只影响后续模块安装，不影响 root。
     * v76: 换装前先校验 ksud-gk 的 md5（与项目已验产物一致性），防用到
     * 旧版/错误 ksud 把模块链和 sepolicy 注入一并带崩。
     * 同时记录签发路径供后续软重启选签发者：v76 起 zygiskAutoActivate
     * 的 ksud soft-reboot 走 ksud-gk，绕开 uapi2 软重启即静默失败的坑。 */
    private String uapiGuard(ILogCallback callback) {
        try {
            ShellResult v = rootShell("/data/adb/ksud -V 2>/dev/null", 15);
            int uapi = parseUapi(v.ok() ? v.out : "");
            if (uapi == EXPECTED_KSUD_UAPI) {
                return "uapi" + uapi + "-ok";
            }
            if (!new File(TMP, "ksud-gk").isFile()) {
                return "uapi" + uapi + "-mismatch-no-gk";
            }
            /* v76: ksud-gk 完整性门禁 —— md5 只认 2026-11-03 已验的 uapi5 签发产物。
             * 直接 cp 会把旧版本/错误 ksud 换到 /data/adb/ksud，后续模块安装与
             * sepolicy 注入静默失败，比 uapi2 报错还难排查。 */
            ShellResult gkMd5 = rootShell("md5sum /data/local/tmp/ksud-gk 2>/dev/null", 10);
            String md5 = gkMd5.out == null ? "" : gkMd5.out.trim();
            if (!md5.startsWith("2432bd6318e9f97f9fa445ee9cba0c5a")) {
                log(callback, "uapi guard: ksud-gk md5=" + md5
                        + " != proven(2432bd63...) — refusing swap, treat as no-gk");
                return "uapi" + uapi + "-mismatch-bad-gk(md5)";
            }
            log(callback, "uapi guard: /data/adb/ksud is uapi" + uapi
                    + ", kernel needs uapi" + EXPECTED_KSUD_UAPI + " — swapping");
            /* 直写（root 窗口内通常可行） */
            ShellResult s1 = rootShell(
                    "cp -f /data/adb/ksud /data/adb/ksud-uapi2.bak 2>/dev/null; "
                            + "cp -f /data/local/tmp/ksud-gk /data/adb/ksud && "
                            + "chmod 755 /data/adb/ksud", 20);
            int after = parseUapi(rootShell("/data/adb/ksud -V 2>/dev/null", 15).out);
            if (after == EXPECTED_KSUD_UAPI) {
                return "uapi" + uapi + "-to-" + after;
            }
            /* b0 通道兜底（vivo LSM 防篡改拦直写时） */
            log(callback, "uapi guard: direct swap failed, trying b0 channel");
            writeLine(new File(TMP, ".uapi_swap.sh").getAbsolutePath(),
                    "#!/system/bin/sh\n"
                            + "cp -f /data/adb/ksud /data/adb/ksud-uapi2.bak 2>/dev/null\n"
                            + "cp -f /data/local/tmp/ksud-gk /data/adb/ksud\n"
                            + "chmod 755 /data/adb/ksud\n"
                            + "/data/adb/ksud -V\n", false);
            ShellResult s2 = rootShell(
                    "/data/local/tmp/b0 atcid_vendor_socket "
                            + "'sh /data/local/tmp/.uapi_swap.sh #seedkeeper_client' >/dev/null 2>&1; sleep 2; "
                            + "/data/adb/ksud -V 2>/dev/null", 30);
            int after2 = parseUapi(s2.out);
            if (after2 == EXPECTED_KSUD_UAPI) {
                return "uapi" + uapi + "-to-" + after2 + "-via-b0";
            }
            return "uapi" + uapi + "-mismatch-swap-failed";
        } catch (Throwable e) {
            return "guard-error";
        }
    }

    /** v38: 从 ksud -V 输出解析 "(uapi: N)"，读不到返回 -1。 */
    private static int parseUapi(String out) {
        if (out == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\(uapi:\\s*(\\d+)\\)").matcher(out);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** v48 (2026-11-03 netfix 故障分析): 落地后网络金丝雀 + 归因。
     * 判据：ping 223.5.5.5（shell 域即可，不走 root——顺带验证非特权域数据面）。
     * 归因逻辑：
     *   ok                      —— 网络（含 shell 域）正常，vivo_net_fix 规则已生效
     *   dead-enforcing-reboot-pending —— Enforcing + 网络死：典型 uapi 失配致
     *                              sepolicy.rule 未注入（或 v1.7 域级规则在
     *                              Enforcing 下救不了 unlabeled 数据面）。
     *                              uapiGuard 若刚换装成功，规则下次 boot 注入
     *                              —— reboot 是恢复路径，记录给 autoloop/用户决策
     *   dead-permissive         —— Permissive 下仍死：非 SELinux 问题（路由/
     *                              供应商侧），转 pstore/dmesg 取证
     * 只记录不动作：不自动 setenforce（与隐藏目标冲突）、不自动 reboot。 */
    private String netCanary(ILogCallback callback) {
        try {
            ShellResult p = rootShell("ping -c1 -W2 223.5.5.5 >/dev/null 2>&1 && echo OK || echo DEAD", 10);
            boolean ok = p.ok() && p.out != null && p.out.contains("OK");
            if (ok) {
                return "ok";
            }
            String enforce = readText("/sys/fs/selinux/enforce").trim();
            if ("1".equals(enforce)) {
                log(callback, "net canary: DEAD + Enforcing —— vivo_net_fix 规则未生效"
                        + "（sepolicy.rule 由 ksud 开机注入；若 uapiGuard 刚换装，reboot 后恢复）");
                return "dead-enforcing-reboot-pending";
            }
            log(callback, "net canary: DEAD + Permissive —— 非 SELinux 问题，需另行取证");
            return "dead-permissive";
        } catch (Throwable e) {
            return "canary-error:" + e.getClass().getSimpleName();
        }
    }

    /** v41: ZygiskNext 自动激活（late-load 自愈）。
     * 判据与动作：`zygiskd status` 的 zygote_states>0 才算注入成功；
     * 未注入时先把 zn-daemon 收敛成单例（多实例会互抢 ptrace 注入），
     * 再安排一次 framework 软重启（stop/start）让 daemon 捕获新 zygote。
     * 软重启命令用 setsid 脱离 App 进程组 —— stop 会杀光 App 进程，
     * 重启链必须活过 App 的死亡（root 域进程不受 AM 管理）。
     * 同 boot 防死循环：20 分钟内只安排一次。 */
    private String zygiskAutoActivate(ILogCallback callback) {
        try {
            // v49 自愈前置：先清 ZN 闩锁与自禁用。LKM 时序竞态（zygote ~20s 启动
            // > exploit ~50s 落地）下 ZN post-fs-data 注入必失败，会写
            // .abort_msg 闩锁 + disable 自禁用；不清则后续 daemon 拒启、
            // soft-reboot 后无人接驾注入新 zygote（POSTMORTEM-20261104 问题5）。
            rootShell("rm -f /data/adb/zygisksu/.abort_msg " + ZN_MODDIR + "/disable", 10);
            ShellResult st = rootShell(ZN_BIN + " status 2>/dev/null", 20);
                if (st.timedOut || !st.ok()) {
                    /* v49：daemon 可能已被闩锁/竞态杀掉 —— 先补一个单例再重读状态 */
                    rootShell("cd " + ZN_MODDIR + " && setsid nohup ./bin/zygiskd daemon "
                            + ">/dev/null 2>&1 </dev/null &", 10);
                    Thread.sleep(2000);
                    st = rootShell(ZN_BIN + " status 2>/dev/null", 20);
                    if (st.timedOut || !st.ok()) {
                        /* v55: 补 daemon 后仍读不到 status —— 按「模块本体是否
                         * 在位」分流：模块缺失 → 真 zn-absent 不动系统；模块
                         * 在位而 status 不通 → daemon 已被 "Last injection
                         * failed" 状态逼退（旧 zygote 注入必败，LKM 时序竞态
                         * 的必然结果），zygotes 视为 0 继续走下方软重启调度，
                         * 让 daemon 在新 zygote 上重试注入。v54 及之前直接
                         * return "zn-absent" 放弃自愈 —— 即「App 显示修复
                         * 成功但 Zygisk 实际未激活」的根因（实测 16:50）。 */
                        ShellResult bin = rootShell(
                                "ls " + ZN_BIN + " >/dev/null 2>&1 && echo yes || echo no", 5);
                        if (!"yes".equals(bin.out == null ? "" : bin.out.trim())) {
                            return "zn-absent";
                        }
                        log(callback, "zygisk: daemon 无响应但模块在位 —— 走软重启换新 zygote");
                    }
                }
            int zygotes = parseFieldInt(st.out, "zygote_states");
            int daemons = pidCount("zn-daemon");
            log(callback, "zygisk: zygote_states=" + zygotes + " zn-daemon=" + daemons);
            if (daemons != 1) {
                rootShell("for p in $(pidof zn-daemon); do kill $p 2>/dev/null; done; "
                        + "sleep 1", 15);
                rootShell("cd " + ZN_MODDIR + " && setsid nohup ./bin/zygiskd daemon "
                        + ">/dev/null 2>&1 </dev/null &", 10);
                Thread.sleep(2000);
                daemons = pidCount("zn-daemon");
                log(callback, "zygisk: single-instance daemon -> zn-daemon=" + daemons);
            }
            if (zygotes > 0 && daemons >= 1) {
                deleteDex2oatFlags(callback, "zn-ok");
                return "zn-ok-zygote" + zygotes;
            }
            File mark = new File(ZN_SOFTREBOOT_MARK);
            long ageMin = mark.isFile()
                    ? (System.currentTimeMillis() - mark.lastModified()) / 60000L
                    : Long.MAX_VALUE;
            if (ageMin < ZN_SOFTREBOOT_COOLDOWN_MIN) {
                return "zn-zygote0-softreboot-cooldown(" + ageMin + "m)";
            }
            writeLine(ZN_SOFTREBOOT_MARK, "ts=" + System.currentTimeMillis() / 1000
                    + " uptime=" + readUptime() + "s zygotes=" + zygotes + "\n", false);
            /* v61 关键修复：软重启从手搓 stop/start 改为 /data/adb/ksud soft-reboot。
             * 根因链（2026-11-05 13:47-14:10 全链排查）：module.prop 损坏 →
             * post-fs-data 的 daemon 读不到模块元数据静默退出 → 本 boot 注入
             * 窗口错过；stop/start 又不保证 daemon 干净退出（zygiskd exit），
             * 失败状态残留 → 之后所有 daemon 启动判 "Last injection failed"
             * 写闩锁自杀（自毒死锁）。ksud soft-reboot 官方模拟重启会正确跑
             * emulated-soft-reboot.sh（zygiskd exit 干净退出）并在 stop 前重跑
             * post-fs-data → daemon 先于新 zygote 就位 → 注入成功（实测
             * zygote_states:2 / preloaded 3/3 modules / modules_with_issue:0）。 */
            /* v76: 软重启签发者按 uapi 选择签发路径。/data/adb/ksud 是 uapi2 时，
             * soft-reboot 模块管理/sepolicy 注入静默失败（service.sh 内部
             * emulated-soft-reboot.sh 也救不了），此时必须走 ksud-gk(uapi5)。
             * md5 不符或 ksud-gk 缺失则回落到原路径（尽力而为，不让软重启整条链失灵）。 */
            String softRebootBin = "/data/adb/ksud";
            ShellResult gkCheck = rootShell(
                    "md5sum /data/local/tmp/ksud-gk 2>/dev/null | grep -q 2432bd6318e9f97f9fa445ee9cba0c5a && echo ok || echo no", 8);
            String ksudUapi = "";
            ShellResult kv = rootShell("/data/adb/ksud -V 2>/dev/null", 8);
            if (kv.ok()) ksudUapi = kv.out == null ? "" : kv.out;
            if (ksudUapi.contains("uapi: 2")
                    && gkCheck.out != null && "ok".equals(gkCheck.out.trim())
                    && new File(TMP, "ksud-gk").isFile()) {
                softRebootBin = "/data/local/tmp/ksud-gk";
                log(callback, "zygisk: /data/adb/ksud is uapi2 — using ksud-gk (uapi5) for soft-reboot");
            }
            // LSPosed 的退出交给 KernelSU 模块生命周期；软重启命令执行前框架仍在使用它。
            final String softBin = softRebootBin;
            rootShell("setsid sh -c 'sleep 6; "
                    + "rm -f /data/adb/zygisksu/.abort_msg "
                    + ZN_MODDIR + "/disable; " + softBin + " soft-reboot' "
                    + ">/dev/null 2>&1 </dev/null &", 8);
            // v49: 用户决策（保工行）—— 软重启后 lsposed zygisk 模块在新 zygote
            // 初始化时会重设 dex2oat-flags（工行闪退根因，POSTMORTEM 问题1），
            // 延迟 ~120s 追加清除一次，盖住"prop 在会话结束后才被设置"的时序。
            rootShell("setsid sh -c 'sleep 120; /data/adb/ksu/bin/resetprop "
                    + "--delete dalvik.vm.dex2oat-flags' >/dev/null 2>&1 </dev/null &", 8);
            // v58: 框架软重启（stop/start）会顺带杀死 root app_process 守护类
            // 模块进程（实测 2026-11-05：SuToaster 在 10:53 软重启后被杀，
            // module.prop 自写 "(❌Disabled)"）。KernelSU 的 boot-completed
            // 模块脚本只在整机开机重跑，纯 stop/start 不触发 —— 守护无人复活。
            // 修复链自己安排的软重启必须自己兜底：新框架起来后（+45s）
            // 检查并复活（运行各模块自带的 boot-completed.sh，等价开机）。
            rootShell("setsid sh -c 'sleep 45; /system/bin/sh /data/adb/ghostroot/revive_module_daemons.sh'"
                    + " >/dev/null 2>&1 </dev/null &", 8);
            log(callback, "zygisk: zygote NOT hooked (late-load race) -> framework soft reboot "
                    + "scheduled; ZN will catch the fresh zygote (~40s)");
            return "zn-softreboot-scheduled";
        } catch (Throwable e) {
            return "zn-error:" + e.getClass().getSimpleName();
        }
    }

    /** 保留既有工行兼容策略；框架初始化后再次清除该属性。 */
    private void deleteDex2oatFlags(ILogCallback callback, String tag) {
        rootShell("/data/adb/ksu/bin/resetprop --delete "
                + "dalvik.vm.dex2oat-flags 2>/dev/null", 10);
        log(callback, "zygisk: dex2oat-flags cleared (" + tag + ") —— ICBC 保活策略");
    }

    /** v58: root app_process 守护类模块进程的复活逻辑。
     * 背景见 zygiskAutoActivate 内注释：框架软重启杀 SuToaster，而
     * KernelSU 的 boot-completed 模块脚本只在整机开机重跑 —— 纯
     * stop/start 后这些 daemon 无人接手，模块自写 "(❌Disabled)"
     * 描述（实测 2026-11-05 11:28 手动跑 boot-completed.sh 后恢复）。
     * 动作：只做「进程不在 → 运行该模块自带的 boot-completed.sh」
     * —— 脚本是模块作者自己写的启动逻辑，fetch 权限域不变：
     * 每个脚本都先 self-check（进程在就退出），幂等可反复执行。
     * 修复链里在两处调用：znRepair 入口（吃掉非软重启来源的死亡）
     * 和软重启调度后的 +45s 延迟任务（吃掉软重启本身的死亡）。 */
    private void reviveModuleDaemons(ILogCallback callback) {
        File local = new File(TMP, "revive_module_daemons.sh");
        boolean localOk = false;
        try {
            localOk = copyAsset(ASSET_REVIVE, local);
        } catch (Exception e) {
            log(callback, "模块守护: 资产拷贝失败 " + e);
        }
        if (!localOk || !local.isFile()) {
            log(callback, "模块守护: 复活脚本暂不可用（asset 缺失），下次修复再试");
            return;
        }
        /* 部署到 /data/adb/ghostroot 后执行 —— 延迟任务（软重启 +45s）引用
         * 的是部署路径，所以入口先保证它在场 */
        rootShell("mkdir -p " + REVIVE_DIR
                + " && cp " + local.getAbsolutePath() + " " + REVIVE_PATH
                + " && chmod 700 " + REVIVE_PATH, 10);
        ShellResult r = rootShell(REVIVE_PATH + " 2>/dev/null", 30);
        if (r.ok() && r.out != null && !r.out.trim().isEmpty()) {
            for (String line : r.out.split("\n")) {
                String l = line.trim();
                if (!l.isEmpty()) {
                    log(callback, "模块守护: " + l);
                }
            }
        } else if (!r.ok()) {
            log(callback, "模块守护: 复活脚本异常（rc/超时），下次修复再试");
        }
    }

    /** v41: pidof 计数（0=未运行）。zn-daemon 是 zygiskd 的进程名 —— 按
     * "zygiskd" grep 永远查不到，这是 2026-11-02 排查踩过的坑。 */
    private int pidCount(String name) {
        ShellResult r = rootShell("pidof " + name + " 2>/dev/null", 10);
        String o = r.out.trim();
        return o.isEmpty() ? 0 : o.split("\\s+").length;
    }

    /** v41: 解析 "key:value" 行的整数值，缺失/非法返回 -1。 */
    private static int parseFieldInt(String text, String key) {
        if (text == null) {
            return -1;
        }
        for (String line : text.split("\n")) {
            int i = line.indexOf(key + ":");
            if (i >= 0) {
                try {
                    return Integer.parseInt(line.substring(i + key.length() + 1).trim());
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

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
            /* v42: one watcher only —— 旧判定只 kill -0 pidfile 里的 pid，两个缺陷：
             * 1) 跨 boot 残留 pid 可被 PID 复用误判存活；2) pre-loop 与每轮各
             * arm 一次，同秒内两次调用都读不到对方刚要写的 pid → 双实例
             * （13:23:25 实测 pid=16828/16840 两条 LANDED、one.sh 双跑）。
             * v6 起 fire.sh 把 "pid boot_id" 写进 pidfile 并自检，这里同步校验。 */
            File pidFile = new File("/data/local/tmp/.fire.pid");
            String pidLine = readText(pidFile.getAbsolutePath()).trim();
            if (fireWatcherAlive(pidLine)) {
                log(callback, "fire watcher alive pid=" + pidLine.split("\\s+")[0]);
                return;
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

    /** v42: fire watcher 存活判定 —— pidfile 格式 "pid boot_id"（fire.sh v6 写入）。
     * 同 boot_id 且 /proc/<pid>/cmdline 含 fire.sh 才算存活；旧 v5 单 token 格式
     * 无法排除 PID 复用，一律视为失效（重新 arm 由 fire.sh 自身去重兜底）。 */
    private boolean fireWatcherAlive(String pidLine) {
        if (pidLine == null || pidLine.isEmpty()) {
            return false;
        }
        String[] parts = pidLine.split("\\s+");
        if (parts.length < 2) {
            return false;
        }
        String bootId = readText("/proc/sys/kernel/random/boot_id");
        if (bootId == null || !bootId.trim().equals(parts[1])) {
            return false;
        }
        String cmdline = readText("/proc/" + parts[0] + "/cmdline");
        return cmdline != null && cmdline.contains("fire.sh");
    }

    /** v45: fire.sh v7 落地标记 —— .fired 内容等于本 boot 的 boot_id 时，
     * 本 boot 已有一发成功落地（fire 链 LANDED 后写入）。跨 boot 的旧值
     * （旧格式 "FIRED" 或上一个 boot 的 id）不匹配，自然失效。 */
    private boolean firedThisBoot() {
        String fired = readText(FIRED_PATH);
        String bootId = readText("/proc/sys/kernel/random/boot_id");
        return fired != null && bootId != null
                && !fired.trim().isEmpty()
                && fired.trim().equals(bootId.trim());
    }

    /** v45: 跨工具门禁 —— timer 系 exploit 驱动器（98260.pid）是否在本 boot
     * 存活。pidfile 跨 boot 残留会有 PID 复用误报，因此要求 /proc/<pid>/cmdline
     * 含 "profile.conf"（该驱动的 exploit 以 --profile 参数运行）才判定存活。
     * 方向偏安全：误报只会跳过攻击（要求重启），不会误攻。 */
    private boolean foreignExploitRunning() {
        String pidLine = readText(TIMER_PID_PATH);
        if (pidLine == null || pidLine.trim().isEmpty()) {
            return false;
        }
        String pid = pidLine.trim().split("\\s+")[0];
        if (!pid.matches("\\d+")) {
            return false;
        }
        String cmdline = readText("/proc/" + pid + "/cmdline");
        return cmdline != null && cmdline.contains("profile.conf");
    }

    /** v42: ghostlock exploit 进程是否仍在运行 —— /proc 扫描（不用 pgrep -f，
     * 它会自匹配，见 fire.sh 注释）。落地后 exploit 未被 fire 链杀掉是 13:23
     * 事故的直接放大器（W2/W3 修复段继续写已落地的内核）。 */
    private boolean exploitRunning() {
        File[] procs = new File("/proc").listFiles(
                (d, n) -> n.matches("\\d+"));
        if (procs == null) {
            return false;
        }
        for (File p : procs) {
            String c = readText(new File(p, "cmdline").getAbsolutePath());
            if (c != null && c.contains("ghostlock")) {
                return true;
            }
        }
        return false;
    }

    /** v42: /data/tombstones 归档 —— dropbox 对 isPrevious 条目的回收不可靠
     * （2026-11-02 实测：09:58:50 的 init abort tombstone 拖了 3 个 boot 才进
     * dropbox；中途复位还会让崩溃现场整批丢失）。root 落地后趁通道活着把整
     * 目录拷到 /data/local/tmp/gr-tomb-<ts>/。仅 root 验证通过后调用。 */
    private void archiveTombstones(ILogCallback callback) {
        try {
            ShellResult ls = rootShell("ls -A /data/tombstones 2>/dev/null | head -3", 20);
            if (ls.timedOut || !ls.ok() || ls.out.trim().isEmpty()) {
                log(callback, "[TOMB] /data/tombstones unreadable or empty"
                        + " (exit=" + ls.exit + (ls.timedOut ? " TIMEDOUT" : "") + ") — skip");
                return;
            }
            String dir = TMP + "/gr-tomb-" + System.currentTimeMillis() / 1000;
            ShellResult cp = rootShell("mkdir -p " + dir
                    + " && cp -a /data/tombstones/. " + dir + "/ && ls " + dir + " | wc -l", 60);
            if (cp.ok()) {
                log(callback, "[TOMB] archived " + cp.out.trim() + " files -> " + dir);
            } else {
                log(callback, "[TOMB] archive FAILED (exit=" + cp.exit
                        + (cp.timedOut ? " TIMEDOUT" : "") + "): " + cp.out.trim());
            }
        } catch (Exception e) {
            log(callback, "[TOMB] archive error: " + e);
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
