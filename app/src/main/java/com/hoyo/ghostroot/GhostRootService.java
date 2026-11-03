package com.hoyo.ghostroot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import rikka.shizuku.Shizuku;

/**
 * 纯手动执行服务：
 * - 不会开机自启，不会监听 Shizuku 自动触发
 * - 只有 MainActivity 的「开始执行」按钮（或通知上的手动按钮）以 ACTION_RUN 启动它时，
 *   它才绑定 Shizuku UserService 并执行提权
 * - 执行期间通过前台服务保活，日志同时推送给 Activity（sink）和通知栏
 */
public class GhostRootService extends Service {

    public static final String ACTION_RUN = "RUN";
    public static final String ACTION_REBOOT = "REBOOT";

    /** Activity 注册的日志出口（同进程，静态即可）。 v25:
     * verdict 三态（0=成功 / 1=未知 / -1=失败），未知再也不能在
     * 界面伪装成“未成功”。 */
    public interface Sink {
        void onLine(String line);

        void onDone(int code, int verdict, String detail);
    }

    public static volatile Sink sink;
    /** 供 Activity 判断是否有任务在跑。 */
    public static volatile boolean running = false;

    /* v26: Activity 重连支持。服务端保留最近日志（环形缓冲）和最后一次
     * onDone 结论；Activity 销毁重建后 onResume 里 attach() 即可回放，
     * 不再出现"新页面看不到日志 / 收不到结束 / 点开始被静默拦截"。 */
    private static final java.util.ArrayDeque<String> BUF = new java.util.ArrayDeque<>();
    private static final int BUF_MAX = 400;
    private static volatile int[] lastDone;          // {code, verdict}
    private static volatile String lastDoneDetail;
    private static volatile boolean lastDoneConsumed;

    /** Activity(重)挂接出口：回放缓冲 + 最近结论。返回是否有内容回放。 */
    public static boolean attach(Sink s) {
        sink = s;
        boolean any = false;
        synchronized (BUF) {
            for (String line : BUF) {
                try {
                    s.onLine(line);
                } catch (Throwable ignored) {
                }
                any = true;
            }
        }
        int[] done = lastDone;
        if (done != null) {
            try {
                s.onDone(done[0], done[1],
                        lastDoneDetail == null ? "" : lastDoneDetail);
            } catch (Throwable ignored) {
            }
            any = true;
            /* v32/v33: 结果只回放一次 —— 查看即消费，hasPendingResult()
             * 随之转 false，反复返回页面不再重复收到结束提示。日志缓冲
             * 仍保留（新任务开始时才清空）。 */
            lastDoneConsumed = true;
        }
        return any;
    }

    /** v32: 任务已结束但页面尚未看过结果时为 true —— MainActivity 据此
     * 在 idle 状态也重连，避免"页面销毁期间任务结束 → 重开页面漏掉结论"。 */
    public static boolean hasPendingResult() {
        return lastDone != null && !lastDoneConsumed;
    }

    private static void remember(String line) {
        synchronized (BUF) {
            BUF.addLast(line);
            while (BUF.size() > BUF_MAX) {
                BUF.removeFirst();
            }
        }
    }

    private static final String CHANNEL = "ghostroot";
    private static final int NOTIF_ID = 1;
    private static final int SUMMARY_ID = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean finished = false;
    private boolean pendingReboot = false;
    private IRootRunner runner;
    private Shizuku.UserServiceArgs serviceArgs;
    /** v32: 本次会话开始时刻（秒）。finish() 校验 .gr_verdict 的 ts 必须
     * 落在 [本会话开始-5s, 现在+5min] 内 —— 上一会话遗留的结果文件
     * （即使 1 小时新鲜度通过）不再可能被算到本次头上；未来时间同样
     * 拒绝，防时钟调整绕过有效期。 */
    private long sessionStartTs;
    /** v33: 本次会话 ID（beginRun 生成，prepareSession 下发给 usvc，
     * finish() 与 .gr_verdict 的 session= 字段精确比对）。 */
    private String sessionId = "none";

    private final ServiceConnection connection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        runner = IRootRunner.Stub.asInterface(binder);
        if (pendingReboot) {
            pendingReboot = false;
            emit("正在重启手机…");
            try {
                runner.rebootDevice();
            } catch (Throwable t) {
                emit("reboot failed: " + t);
            }
            return;
        }
        emit("root 服务已连接（uid 2000 通道就绪）");
        try {
            /* v33: 先绑定会话（usvc 清旧结果 + 记住 ID），再开跑 */
            runner.prepareSession(sessionId);
        } catch (Throwable t) {
            emit("prepareSession failed: " + t);
        }
        startRun();
    }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            runner = null;
            /* v23: the UserService can vanish for real reasons (the exploit
             * reset kills the whole runtime, or Shizuku restarts). Leaving
             * `running` set wedges the UI ("已有任务在执行中") forever and
             * swallows the reboot signal — surface it as a real outcome. */
            emit("root 服务断开（UserService 消失）");
            if (running && !finished) {
                main.post(() -> finish(-3));
            }
        }
    };

    private final ILogCallback callback = new ILogCallback.Stub() {
        @Override
        public void onLog(String line) {
            emit(line);
        }

        @Override
        public void onDone(int exitCode) {
            main.post(() -> finish(exitCode));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "GhostRoot",
                NotificationManager.IMPORTANCE_LOW));
        serviceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(this, RootUserService.class.getName()))
                .daemon(false)
                .processNameSuffix("root")
                .debuggable(false)
                .version(32);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 必须先进入前台状态（Android 14 要求）
        startForeground(NOTIF_ID, notify("手动模式：等待开始指令"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (intent != null && ACTION_RUN.equals(intent.getAction())) {
            main.post(this::beginRun);
        } else if (intent != null && ACTION_REBOOT.equals(intent.getAction())) {
            main.post(this::beginReboot);
        } else {
            // 没有明确的手动指令 → 不执行任何东西，直接退出
            emit("未收到手动执行指令，服务退出");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        /* v39: 前台服务销毁时解除 Shizuku UserService 绑定 —— 此前旧
         * connection 跨实例泄漏、长期占住已完成的 UserService 进程。
         * 用 stop=false：任务中途被系统回收时绝不主动杀掉正在执行的
         * UserService；残余进程由下一次 beginRun 的 unbind(stop=true)
         * 统一回收。 */
        try {
            Shizuku.unbindUserService(serviceArgs, connection, false);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    /** 手动触发的唯一执行入口。 */
    private void beginRun() {
        if (running) {
            /* v26: 之前是静默 return —— 通知栏按钮/重复启动会看起来"没反应" */
            emit("已有任务在执行中，忽略本次启动");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        if (finished) {
            return;
        }
        if (!Shizuku.pingBinder()) {
            emit("Shizuku 未运行，取消执行");
            abort();
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            emit("Shizuku 未授权，取消执行（请打开 GhostRoot 点按钮重新授权）");
            abort();
            return;
        }
        running = true;
        finished = false;
        /* v32: 新任务开始即作废旧缓冲与上一任务的结束结论 —— 否则页面
         * 重连 attach() 会把上一次的"成功/失败"回放给正在运行的新任务。 */
        synchronized (BUF) {
            BUF.clear();
        }
        lastDone = null;
        lastDoneDetail = null;
        lastDoneConsumed = false;
        sessionStartTs = System.currentTimeMillis() / 1000;
        sessionId = sessionStartTs + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        emit("Shizuku 就绪，绑定 root 服务… (session=" + sessionId + ")");
        try {
            /* v39 (存量问题一并修复): 先停掉已挂载的旧 UserService —— 同版本
             * bind 会复用旧进程实例，上一任务的状态（旧 callback 绑定、过程内
             * 残留状态）会被带进新任务。unbindUserService(..., stop=true) 解绑
             * 同时让 Shizuku 停掉旧进程，随后 bind 起干净的新实例。
             * 首次启动无挂载实例时此调用是无害的 no-op。 */
            Shizuku.unbindUserService(serviceArgs, connection, true);
            runner = null;
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (Throwable t) {
            emit("bind failed: " + t);
            running = false;
            abort();
        }
    }

    /** 手动触发的重启入口（失败后快速进入下一轮干净堆）。 */
    private void beginReboot() {
        if (runner != null) {
            emit("正在重启手机…");
            try {
                runner.rebootDevice();
            } catch (Throwable t) {
                emit("reboot failed: " + t);
            }
            return;
        }
        if (!Shizuku.pingBinder()) {
            emit("Shizuku 未运行，无法远程重启，请手动重启手机");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        pendingReboot = true;
        try {
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (Throwable t) {
            pendingReboot = false;
            emit("reboot bind failed: " + t);
        }
    }

    private void startRun() {
        emit("执行中（清后台 + 静置 + exploit）…");
        try {
            runner.run("/data/local/tmp/ghostlock", "/data/local/tmp/profile.bin", 15, callback);
        } catch (RemoteException e) {
            emit("run failed: " + e);
            running = false;
            abort();
        }
    }

    private void finish(int code) {
        if (finished) {
            return;
        }
        finished = true;
        /* v26: 判据对齐 PC 脚本（autoroot 的 root_ok = `su -c id` 含 uid=0）。
         * UserService 把最终结论写进 .gr_verdict，这里优先采用；拿不到时：
         *  - code=-3（UserService 消失）→ 结果未知（复位/崩溃，无终止记录）
         *  - 其余沿用内核状态启发，但"KSU 在 / enforce=0"只能算结果未知 ——
         *    没有 su 证据就不能宣称 root 成功（这正是误报的来源）。 */
        int verdict;
        String detail;
        String vf = readText("/data/local/tmp/.gr_verdict");
        ParsedVerdict pv = parseVerdict(vf);
        long now = System.currentTimeMillis() / 1000;
        boolean tsSane = pv.valid && pv.ts >= sessionStartTs && pv.ts <= now + 300;
        /* v33: 严格会话绑定 —— session 字段必须与本次下发的 ID 完全一致；
         * 时间窗口降级为辅助合理性检查。旧结果从此不可能串用。 */
        boolean sessionMatches = pv.valid && sessionId.equals(pv.session);
        if (pv.valid && sessionMatches && tsSane) {
            verdict = pv.verdict;
            detail = "exit=" + code + " round=" + pv.round + " enforce="
                    + (pv.enforce == null ? "UNKNOWN" : pv.enforce) + " ksu=" + pv.ksu
                    + (pv.su == null || pv.su.isEmpty() ? "" : " su=" + pv.su)
                    + (pv.magica == null || pv.magica.isEmpty() ? "" : " magica=" + pv.magica)
                    + (pv.vrDisarmed == null || pv.vrDisarmed.isEmpty() ? "" : " vr_disarmed=" + pv.vrDisarmed)
                    + (pv.krn == null || pv.krn.isEmpty() ? "" : " krn=" + pv.krn)
                    + (pv.env == null || pv.env.isEmpty() ? "" : " env=" + pv.env)
                    + (pv.uapi == null || pv.uapi.isEmpty() ? "" : " uapi=" + pv.uapi)
                    + (pv.zn == null || pv.zn.isEmpty() ? "" : " zygisk=" + pv.zn)
                    /* v42: fire 链收尾状态随行 —— still-running 时界面必须
                     * 提示"暂勿打开管理器"，与 RootUserService 门禁一致。 */
                    + (pv.firek == null || pv.firek.isEmpty() ? "" : " fire=" + pv.firek)
                    + " reason=" + pv.reason;
        } else if (code == -3) {
            verdict = 1;
            /* v32: 措辞修正 —— 服务断开 ≠ 复位。可能原因：Shizuku 重启、
             * 服务进程退出、通信故障、设备复位。分类只能靠 REBOOT
             * REQUESTED 标记与 bootreason props，不预设归因。 */
            detail = "exit=-3 UserService 消失，无终止记录（可能原因：Shizuku 重启/"
                    + "服务进程退出/通信故障/设备复位 —— 不判定具体原因；"
                    + "可用重启标记与 bootreason props 分类）";
        } else if (pv.valid) {
            /* v32: 结果文件存在但 ts 不属于本会话（上一会话遗留）→ 明确
             * 不采用，也不让它冒充本次结果 */
            verdict = 1;
            detail = "exit=" + code + " verdict-file-is-stale(session=" + pv.session
                    + " != 本次 " + sessionId + ") — 丢弃遗留结果，本次按未知处理";
        } else {
            String enforce = readText("/sys/fs/selinux/enforce");
            String modules = readText("/proc/modules");
            boolean ksuDecided = modules != null;
            boolean ksu = ksuDecided && modules.contains("kernelsu");
            String e = enforce == null ? null : enforce.trim();
            int enforceN = "0".equals(e) ? 0 : "1".equals(e) ? 1 : -1;
            boolean landed = ksu || enforceN == 0;
            boolean unknown = !landed && (!ksuDecided || enforceN == -1);
            /* 降级：landed ≠ root。无 su 证据只报"结果未知"。 */
            verdict = (unknown || landed) ? 1 : -1;
            detail = "exit=" + code
                    + " enforce=" + (enforceN == -1 ? "UNKNOWN" : enforceN)
                    + (modules == null ? " KSU状态不可读" : (ksu ? " KSU已加载" : " KSU未加载"))
                    + (landed ? " [landed但无su验证证据]" : "");
        }
        String verdictText = verdict == 0 ? "ROOT 成功(已验证)"
                : (verdict == 1 ? "结果未知" : "未成功");
        emit(verdictText + " " + detail);
        lastDone = new int[]{code, verdict};
        lastDoneDetail = detail;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(SUMMARY_ID, notify(verdict == 0 ? "✅ " + verdictText + "：" + detail
                : (verdict == 1 ? "❓ " + verdictText + "：" + detail : "❌ " + verdictText + "：" + detail)));
        Sink s = sink;
        if (s != null) {
            try {
                s.onDone(code, verdict, detail);
            } catch (Throwable ignored) {
            }
            /* v34: 前台直接交付也算已消费 —— 否则用户看过结束结果、切后台
             * 再回来时 hasPendingResult() 仍为真，attach() 会把同一结论和
             * 整段日志再回放一次。（通知栏是并行通道：若用户只看通知没开
             * 页面，页面重开时仍会回放一次，这是预期行为。） */
            lastDoneConsumed = true;
        }
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** v26: .gr_verdict 解析（ts/verdict/su/enforce/ksu/exit/magica/vr_disarmed/reason）。 */
    private static final class ParsedVerdict {
        boolean valid;
        long ts;
        int verdict = Integer.MIN_VALUE;
        String su;
        String enforce;
        String ksu;
        int round = -1;
        String magica;
        String vrDisarmed;
        String krn;
        String env;
        String uapi;
        String zn;
        String firek;
        String reason;
        String session;
    }

    private ParsedVerdict parseVerdict(String text) {
        ParsedVerdict pv = new ParsedVerdict();
        if (text == null || text.isEmpty()) {
            return pv;
        }
        try {
            for (String line : text.split("\n")) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                switch (k) {
                    case "ts": pv.ts = Long.parseLong(v); break;
                    case "verdict": pv.verdict = Integer.parseInt(v); break;
                    case "su": pv.su = v; break;
                    case "enforce": pv.enforce = v; break;
                    case "ksu": pv.ksu = v; break;
                    case "round": pv.round = Integer.parseInt(v); break;
                    case "magica": pv.magica = v; break;
                    case "vr_disarmed": pv.vrDisarmed = v; break;
                    case "krn": pv.krn = v; break;
                    case "env": pv.env = v; break;
                    case "uapi": pv.uapi = v; break;
                    case "zn": pv.zn = v; break;
                    case "firek": pv.firek = v; break;
                    case "session": pv.session = v; break;
                    case "reason": pv.reason = v; break;
                    default: break;
                }
            }
        } catch (NumberFormatException ignored) {
            return new ParsedVerdict();
        }
        pv.valid = (pv.verdict == 0 || pv.verdict == 1 || pv.verdict == -1) && pv.ts > 0;
        return pv;
    }

    /** 未开跑就失败 → 清理并退出。 */
    private void abort() {
        running = false;
        lastDone = new int[]{-2, -1};
        lastDoneDetail = "aborted before run (预检失败，看日志找原因)";
        Sink s = sink;
        if (s != null) {
            try {
                s.onDone(-2, -1, "aborted before run");
            } catch (Throwable ignored) {
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** 完整日志给 Activity，关键行给通知。v23: 同时镜像到 logcat ——
     * am 远程点火 / 保姆模式时 Activity 的 sink 是 null，之前所有
     * 决策行都无声消失，本次静默 bind 失败就是从这里丢的。 */
    private void emit(String line) {
        android.util.Log.i("GhostRootSvc", line);
        remember(line);
        persistSvcLine(line);
        Sink s = sink;
        if (s != null) {
            try {
                s.onLine(line);
            } catch (Throwable ignored) {
            }
        }
        if (isKeyLine(line)) {
            update(lastMeaningful(line));
        }
    }

    /** v27: 服务端事件落盘（app 私有目录，重启不丢）。中断前的 bind /
     * reboot 指令 / 断连这些行以前只进易失的 logcat，复位后取证断档。
     * 超 512KB 轮转一代。可用 `adb shell run-as com.hoyo.ghostroot
     * cat files/ghostroot-svc.log` 读取（debug 包）。 */
    private void persistSvcLine(String line) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "ghostroot-svc.log");
            if (f.length() > 512 * 1024L) {
                java.io.File old = new java.io.File(getFilesDir(), "ghostroot-svc.log.old");
                if (old.exists()) {
                    old.delete();
                }
                f.renameTo(old);
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, true)) {
                out.write(("[" + android.text.format.DateFormat.format("MM-dd HH:mm:ss",
                        System.currentTimeMillis()) + "] " + line + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            /* 证据写不进去也不能影响执行 */
        }
    }

    private boolean isKeyLine(String line) {
        return line.startsWith("[+]") || line.startsWith("[-]") || line.contains("permissive")
                || line.contains("prepare_kernel_page") || line.contains("route_done")
                || line.startsWith("uid=") || line.startsWith("exec:") || line.startsWith("pre-settle")
                || line.contains("handoff") || line.contains("kernelsu") || line.startsWith("exit=")
                || line.startsWith("Shizuku") || line.startsWith("root 服务") || line.startsWith("执行中")
                || line.startsWith("ROOT") || line.startsWith("未成功") || line.contains("failed")
                || line.startsWith("未收到") || line.startsWith("取消执行");
    }

    private String lastMeaningful(String line) {
        return line.length() > 90 ? line.substring(0, 90) : line;
    }

    private void update(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, notify(text));
    }

    private Notification notify(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Intent run = new Intent(this, GhostRootService.class).setAction(ACTION_RUN);
        PendingIntent runPi = PendingIntent.getService(this, 1, run, PendingIntent.FLAG_IMMUTABLE);
        Intent reboot = new Intent(this, GhostRootService.class).setAction(ACTION_REBOOT);
        PendingIntent rebootPi = PendingIntent.getService(this, 2, reboot, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("GhostRoot")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "开始执行", runPi).build())
                .addAction(new Notification.Action.Builder(null, "重启手机", rebootPi).build())
                .build();
    }

    private String readText(String path) {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(path)))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            /* v24: unreadable is a DISTINCT outcome — and now it carries its
             * own evidence line (path, exception class, message) instead of
             * a bare null. */
            android.util.Log.i("GhostRootSvc", "read-fail path=" + path
                    + " exc=" + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
        return sb.length() == 0 ? "" : sb.toString();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
