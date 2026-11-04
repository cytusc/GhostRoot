package com.hoyo.ghostroot;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * 纯手动模式：
 * - 打开 App 只检测 Shizuku 是否安装 / 正在运行 / 已授权，不做任何自动执行
 * - 只有点击「开始执行」按钮才会启动 GhostRootService 提权
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMISSION = 1001;
    private static final String CLI_PATH = "/data/local/tmp/ghostlock";
    private static final String PROFILE_PATH = "/data/local/tmp/profile.bin";
    /** v46/v51: adb 自动化入口 ——
     *   `am start -a com.hoyo.ghostroot.action.ADB_RUN`    → 提权
     *   `am start -a com.hoyo.ghostroot.action.ADB_REPAIR` → ZN 一键修复（提权后）
     * 仅接受 shell(2000)/root(0)/本应用 uid 的调用方（保持 P0 审查的防护语义：
     * 普通第三方应用带着该 action 启动会被忽略）。
     * v51: 供 scripts/gr_autoloop.sh 在提权成功后触发修复并验证结果，
     * 避免 UI 自动化（uiautomator 会被 GKD 抢占）点击按钮的脆弱性。 */
    private static final String ACTION_ADB_RUN = "com.hoyo.ghostroot.action.ADB_RUN";
    private static final String ACTION_ADB_REPAIR = "com.hoyo.ghostroot.action.ADB_REPAIR";

    private TextView logView;
    private Button runButton;
    private Button rebootButton;
    /** v49: ZN 一键修复 —— 提权成功后手机端自愈，不依赖 PC。 */
    private Button repairButton;
    /** v26: sink 做成字段，onResume 自动重挂 —— Activity 被销毁重建后
     * 也能继续收到日志与结束回调（服务端有缓冲回放）。 */
    private GhostRootService.Sink activitySink;

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> {
        append("Shizuku 已连接");
        refreshStatus(false);
    };
    private final Shizuku.OnBinderDeadListener binderDead =
            () -> append("Shizuku 已断开，请重新启动 Shizuku");

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQ_PERMISSION) {
                    return;
                }
                boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                append(granted ? "Shizuku 授权成功，可点击「开始执行」" : "Shizuku 授权被拒绝");
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        Shizuku.addBinderReceivedListener(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionListener);

        append("GhostRoot v" + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ") 就绪");
        append("目标: " + CLI_PATH + " + " + PROFILE_PATH);
        append("模式: 纯手动 —— 打开本应用只检查 Shizuku 状态，点击按钮才开始执行");
        refreshStatus(true);
        maybeHandleAdbAction(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeHandleAdbAction(intent);
    }

    /** v46/v51: ADB 自动化触发 —— 验证调用方 uid 后延迟执行对应按钮动作。
     * 延迟是为了让 Shizuku binder listener 先完成一次状态刷新。
     * ADB_RUN → onRunClicked()；ADB_REPAIR → onRepairClicked()（幂等修复链，
     * 不检查按钮禁用态，已显示「已修复」时仍可复查）。 */
    private void maybeHandleAdbAction(Intent intent) {
        if (intent == null) {
            return;
        }
        final String action = intent.getAction();
        final boolean run = ACTION_ADB_RUN.equals(action);
        final boolean repair = ACTION_ADB_REPAIR.equals(action);
        if (!run && !repair) {
            return;
        }
        int uid = android.os.Binder.getCallingUid();
        if (uid != android.os.Process.SHELL_UID
                && uid != android.os.Process.ROOT_UID
                && uid != android.os.Process.myUid()) {
            append("收到 " + action + " 但调用方 uid=" + uid + " 不在白名单，忽略");
            return;
        }
        append("ADB 触发" + (run ? "执行" : "ZN 修复")
                + "（caller uid=" + uid + "），1.5s 后开始…");
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        runButton.postDelayed(() -> {
            if (!isFinishing()) {
                if (run) {
                    onRunClicked();
                } else {
                    onRepairClicked();
                }
            }
        }, 1500L);
    }

    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    private boolean shizukuInstalled() {
        try {
            getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 只读状态，不做任何动作。verbose=true 时打印所有状态行。 */
    private void refreshStatus(boolean verbose) {
        if (!shizukuInstalled()) {
            if (verbose) append("Shizuku: 未安装（需要先安装 Shizuku 应用）");
            return;
        }
        boolean running = Shizuku.pingBinder();
        if (verbose) {
            append("Shizuku: 已安装, " + (running ? "运行中" : "未运行（请打开 Shizuku 用无线调试启动）"));
        }
        if (!running) {
            return;
        }
        try {
            boolean granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            if (verbose) {
                append("Shizuku 授权: " + (granted ? "已授予，可以点击「开始执行」" : "未授予，点击「开始执行」时会弹出授权"));
            } else {
                append("Shizuku 授权: " + (granted ? "已授予" : "未授予"));
            }
        } catch (Throwable t) {
            append("授权检查失败: " + t);
        }
    }

    private ScrollView buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("GhostRoot");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("手动模式：先启动 Shizuku（无线调试），确认状态为运行中，再点「开始执行」。"
                + "与 PC 端验证流程一致：每次开机只执行一次攻击，失败请重启手机后再试，切勿连续重试。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(0, dp(8), 0, dp(12));
        root.addView(hint);

        runButton = new Button(this);
        runButton.setText("开始执行");
        runButton.setEnabled(true);
        runButton.setOnClickListener(v -> onRunClicked());
        root.addView(runButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* v49: 提权成功后的 ZN 自愈链手动入口 —— 清闩锁/拉 daemon/
         * dex2oat-flags 清除（工行保活）/状态回读，全程手机端完成 */
        repairButton = new Button(this);
        repairButton.setText("Zygisk 修复（提权后点我）");
        repairButton.setEnabled(true);
        repairButton.setOnClickListener(v -> onRepairClicked());
        root.addView(repairButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rebootButton = new Button(this);
        rebootButton.setText("重启手机（快速重试下一轮）");
        rebootButton.setOnClickListener(v -> onRebootClicked());
        root.addView(rebootButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(Color.parseColor("#1B1B1B"));
        logView.setBackgroundColor(Color.parseColor("#F2F2F2"));
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setGravity(Gravity.TOP | Gravity.START);
        logView.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView container = new ScrollView(this);
        container.addView(root);
        return container;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (activitySink == null) {
            activitySink = makeSink();
        }
        /* v26: 重连正在执行的任务 —— 之前 Activity 销毁重建后，新页面
         * 看不到日志、收不到结束，再点开始还被静默拦截。 */
        if (GhostRootService.running) {
            GhostRootService.attach(activitySink);
            runButton.setEnabled(false);
            runButton.setText("运行中…");
            /* v49: 修复任务同样会被重连 —— 两按钮一并冻结，结束回调里统一恢复 */
            repairButton.setEnabled(false);
            repairButton.setText("修复中…");
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            append("已重新连接正在执行的任务（历史日志已回放）");
        } else if (GhostRootService.hasPendingResult()) {
            /* v32: 页面销毁期间任务结束的情况 —— idle 也要重连一次，
             * 否则最后一次结论在重开页面后丢失 */
            GhostRootService.attach(activitySink);
            showInterruptedBanner();
        } else {
            showInterruptedBanner();
        }
        /* v49: 按设备侧文件恢复按钮状态 —— App 被杀重开后，「已提权/
         * 已修复」不丢（本 boot 时效判定），避免"忘记修没修过"重复点击 */
        if (!GhostRootService.running) {
            refreshButtonStates();
        }
    }

    private static final String VERDICT_PATH = "/data/local/tmp/.gr_verdict";
    private static final String ZN_REPAIR_MARK = "/data/local/tmp/.gr_zn_repair";

    /** v49: 文件 ts 是否落在本 boot 内 —— 距今不超过 uptime 即本 boot 写入。
     * 与 showInterruptedBanner 的中断判定同一时钟逻辑（整机重启后 uptime
     * 归零，旧 ts 的 age 必然大于 uptime → 状态自动复位；framework 软重启
     * 不清 uptime → 状态跨软重启保留）。 */
    private boolean freshThisBoot(long ts) {
        if (ts <= 0) {
            return false;
        }
        long age = System.currentTimeMillis() / 1000 - ts;
        return age >= 0 && age <= readUptimeSeconds();
    }

    /** v49: 依据设备侧证据刷新两按钮的持久状态。
     * 提权：.gr_verdict verdict=0 且本 boot 内 → 「✅ 已提权」并禁点
     *   （exploit 每 boot 只允许一次，重复点击只会被门禁吞掉）。
     * 修复：.gr_zn_repair（znRepair 成功留痕）本 boot 内 → 「✅ 已修复」；
     *   outcome 含 softreboot 时保持可点（软重启后需复查确认）；
     *   会话自动激活成功（verdict zn=zn-ok*）同样视为已修复。
     * 任务运行中不碰按钮（运行态由点击/重连分支管理）。 */
    private void refreshButtonStates() {
        boolean rootLanded = false;
        String zn = "";
        String vf = readTmpFile(VERDICT_PATH);
        if (vf != null) {
            long ts = 0;
            int verdict = Integer.MIN_VALUE;
            for (String line : vf.split("\n")) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if ("ts".equals(k)) {
                    try {
                        ts = Long.parseLong(v);
                    } catch (NumberFormatException ignored) {
                    }
                } else if ("verdict".equals(k)) {
                    try {
                        verdict = Integer.parseInt(v);
                    } catch (NumberFormatException ignored) {
                    }
                } else if ("zn".equals(k)) {
                    zn = v;
                }
            }
            rootLanded = verdict == 0 && freshThisBoot(ts);
        }
        /* v49: 判定过程留痕 —— 按钮状态判定链任何一环（读文件/时钟/解析）
         * 失败都会静默回到初始按钮态，logcat 是唯一可观测点 */
        android.util.Log.i("GR_UI", "refresh: vf=" + (vf == null ? "null" : vf.length() + "B")
                + " uptime=" + readUptimeSeconds() + "s rootLanded=" + rootLanded
                + " zn=" + zn + " repaired=" + (zn.startsWith("zn-ok")));
        boolean repaired = false;
        boolean repairNeedsRecheck = false;
        if (rootLanded) {
            if (zn.startsWith("zn-ok")) {
                repaired = true;
            } else if (zn.contains("softreboot")) {
                repaired = true;
                repairNeedsRecheck = true;
            }
            String mk = readTmpFile(ZN_REPAIR_MARK);
            if (mk != null) {
                long ts = 0;
                String outcome = "";
                for (String line : mk.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    if ("ts".equals(k)) {
                        try {
                            ts = Long.parseLong(v);
                        } catch (NumberFormatException ignored) {
                        }
                    } else if ("outcome".equals(k)) {
                        outcome = v;
                    }
                }
                if (freshThisBoot(ts)) {
                    repaired = true;
                    if (outcome.contains("softreboot")) {
                        repairNeedsRecheck = true;
                    }
                }
            }
        }
        if (GhostRootService.running) {
            return;
        }
        if (rootLanded) {
            runButton.setEnabled(false);
            runButton.setText("✅ 已提权（本 boot，重启后可再执行）");
        } else {
            runButton.setEnabled(true);
            runButton.setText("开始执行");
        }
        if (repaired) {
            repairButton.setEnabled(repairNeedsRecheck);
            repairButton.setText(repairNeedsRecheck
                    ? "已修复（软重启后建议再点复查）"
                    : "✅ 已修复（本 boot）");
        } else {
            repairButton.setEnabled(true);
            repairButton.setText(rootLanded ? "Zygisk 修复（提权后点我）"
                    : "Zygisk 修复（需先提权）");
        }
    }

    /** v26→v31: 用 .gr_state 心跳检测"上次任务因设备重启中断"。
     * v31 (P2-5)：只对进行中阶段（preflight/attacking/verifying/cooldown）
     * 报中断——done/already-rooted 等完成态即使早于本次开机也属正常重启；
     * 另交叉核对 .gr_verdict：其 ts 不早于心跳 ts 即视为已正常收尾，
     * 不再出现"成功结束 + 用户正常重启 → 误报中断"。 */
    private void showInterruptedBanner() {
        try {
            String state = readTmpFile("/data/local/tmp/.gr_state");
            if (state == null || state.isEmpty()) {
                return;
            }
            long ts = 0;
            String phase = "?";
            String round = "?";
            for (String tok : state.trim().split("\\s+")) {
                if (tok.startsWith("ts=")) ts = Long.parseLong(tok.substring(3));
                else if (tok.startsWith("phase=")) phase = tok.substring(6);
                else if (tok.startsWith("round=")) round = tok.substring(6);
            }
            if (ts <= 0) {
                return;
            }
            /* 完成态不报中断 */
            java.util.Set<String> inFlight = new java.util.HashSet<>(java.util.Arrays.asList(
                    "preflight", "attacking", "verifying", "cooldown"));
            if (!inFlight.contains(phase)) {
                return;
            }
            /* 结果文件比心跳还新 → 上一会话其实正常收尾了 */
            String vf = readTmpFile("/data/local/tmp/.gr_verdict");
            if (vf != null) {
                for (String line : vf.split("\n")) {
                    if (line.startsWith("ts=")) {
                        long vts = Long.parseLong(line.substring(3).trim());
                        if (vts >= ts) {
                            return;
                        }
                        break;
                    }
                }
            }
            float uptime = readUptimeSeconds();
            long age = System.currentTimeMillis() / 1000 - ts;
            /* 心跳时间早于本次开机 → 中间发生过重启 → 任务被中断 */
            if (age > uptime) {
                SimpleDateFormat df = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
                append("⚠️ 上次任务于 " + df.format(new Date(ts * 1000))
                        + " 在第 " + round + " 轮(" + phase + " 阶段)后因设备重启中断。"
                        + "等 Shizuku 就绪后点「开始执行」继续（每轮都是独立抽奖，无需特别恢复）。");
            }
        } catch (Throwable ignored) {
            /* 状态文件读不到就算了，不影响主流程 */
        }
    }

    private float readUptimeSeconds() {
        /* v49: 改用 SystemClock.elapsedRealtime —— 实测 /proc/uptime 在
         * app 域被 dontaudit 静默拒绝（GR_UI 日志 uptime=0.0s，且 avc 不
         * 留痕），此前 showInterruptedBanner 的"重启中断"判定因此从未
         * 生效过。elapsedRealtime 含 deep sleep、与 uptime 同基准，
         * 无需任何权限。 */
        return android.os.SystemClock.elapsedRealtime() / 1000f;
    }

    private String readTmpFile(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(path)))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private GhostRootService.Sink makeSink() {
        return new GhostRootService.Sink() {
            @Override
            public void onLine(String line) {
                runOnUiThread(() -> append(line));
            }

            @Override
            public void onDone(int code, int verdict, String detail) {
                runOnUiThread(() -> {
                    /* v49: 修复任务的结论措辞与提权分开 —— detail 带
                     * zn-repair 标记（GhostRootService.finish 写入） */
                    if (detail != null && detail.contains("zn-repair")) {
                        append("=== " + (verdict == 0 ? "✅ Zygisk 修复完成" 
                                : "❌ 修复未成功: " + detail) + " ===");
                        if (verdict == 0) {
                            append("若安排了框架软重启，~40s 后回来再点一次「Zygisk 修复」复查终态");
                        }
                    } else {
                        String v = verdict == 0 ? "ROOT 成功(已验证)" : (verdict == 1 ? "结果未知" : "未成功");
                        append("=== 结束 exit=" + code + " " + v + " (" + detail + ") ===");
                        if (verdict == 0) {
                            /* v32: root 成功 ≠ 管理器可用 —— 按 magica 结果分流 */
                            boolean managerOk = detail.contains("magica=manager-patched")
                                    || detail.contains("magica=already-patched");
                            if (managerOk) {
                                append("✅ 现在可以打开 KernelSU 管理器（32680 版）使用了；"
                                        + "建议顺手点「Zygisk 修复」把模块链拉起来");
                            } else {
                                append("⚠️ root 成功，但管理器补丁状态未确认（"
                                        + "magica 结果见 detail）——打开管理器前先在 PC 跑 fix.sh");
                            }
                            if (code != 0) {
                                /* v28: rc 是 exploit 进程退出码，与身份验证无关 */
                                append("注: native exit=" + code + " 只是 exploit 进程退出码；"
                                        + "身份以 su -c id 的 uid=0 为准，勿以 rc 判定成败");
                            }
                        } else if (code == -2) {
                            append("⚠️ 执行前中止（预检失败）：一般是设备文件缺失或 profile md5 不匹配，看上方日志找原因");
                        } else if (verdict == 1) {
                            append("❓ 结果未知：若设备已重启，等自启后直接再来一发（结果详见通知与 logcat）");
                        } else {
                            append("提示: 点「重启手机」进入下一轮 —— 重启后 90 秒内点「开始执行」");
                        }
                    }
                    runButton.setEnabled(true);
                    runButton.setText("开始执行");
                    repairButton.setEnabled(true);
                    repairButton.setText("Zygisk 修复（提权后点我）");
                    /* v49: 按任务结果刷新持久状态 —— 成功时按钮变为
                     * 「已提权/已修复」并按需禁点，而非一律回到初始态 */
                    refreshButtonStates();
                    getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                });
            }
        };
    }

    private void onRunClicked() {
        if (GhostRootService.running) {
            /* v26: 不再只是"请等待" —— 直接重挂 sink 回放日志 */
            GhostRootService.attach(activitySink);
            runButton.setEnabled(false);
            runButton.setText("运行中…");
            append("已有任务在执行中，已为你重新接上日志");
            return;
        }
        if (!shizukuInstalled()) {
            append("Shizuku 未安装，无法执行");
            return;
        }
        if (!Shizuku.pingBinder()) {
            append("Shizuku 未运行：先打开 Shizuku 用无线调试启动，再回来点");
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            append("请求 Shizuku 授权…");
            try {
                Shizuku.requestPermission(REQ_PERMISSION);
            } catch (Throwable t) {
                append("请求授权失败: " + t);
            }
            return;
        }
        // 用户已明确点击按钮 → 手动启动服务执行（sink 复用，onResume 可重挂）
        if (activitySink == null) {
            activitySink = makeSink();
        }
        GhostRootService.sink = activitySink;
        runButton.setEnabled(false);
        runButton.setText("运行中…");
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        append("----- 开始执行（手动触发）-----");
        Intent intent = new Intent(this, GhostRootService.class).setAction(GhostRootService.ACTION_RUN);
        startForegroundService(intent);
    }

    /** v49: ZN 一键修复入口 —— 守卫与 onRunClicked 相同（Shizuku 就绪是
     * 硬前提：修复经 UserService 的 su 通道执行），区别只在 action 与按钮态。 */
    private void onRepairClicked() {
        if (GhostRootService.running) {
            GhostRootService.attach(activitySink);
            repairButton.setEnabled(false);
            repairButton.setText("修复中…");
            append("已有任务在执行中，已为你重新接上日志");
            return;
        }
        /* v54: 回归 Shizuku 单路径（用户决策：更加保险）。v53 的 root 直连
         * 回退实测不可行：KSU 对未授权 App 在 mount ns 里 umount 了 su
         * （run-as stat ENOENT、无 avc），App 永远看不到 su 二进制，无法
         * 发起授权请求 —— 死锁；且 background 线程直调 append 会触发
         * CalledFromWrongThreadException。脚本侧 repair_via_root 不受影响。 */
        if (!shizukuInstalled()) {
            append("Shizuku 未安装，无法修复");
            return;
        }
        if (!Shizuku.pingBinder()) {
            append("Shizuku 未运行：先打开 Shizuku 用无线调试启动，再回来点");
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            append("请求 Shizuku 授权…");
            try {
                Shizuku.requestPermission(REQ_PERMISSION);
            } catch (Throwable t) {
                append("请求授权失败: " + t);
            }
            return;
        }
        if (activitySink == null) {
            activitySink = makeSink();
        }
        GhostRootService.sink = activitySink;
        repairButton.setEnabled(false);
        repairButton.setText("修复中…");
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        append("----- ZN 一键修复（手动触发）-----");
        Intent intent = new Intent(this, GhostRootService.class).setAction(GhostRootService.ACTION_ZN_REPAIR);
        startForegroundService(intent);
    }

    private void onRebootClicked() {
        if (!Shizuku.pingBinder()) {
            append("Shizuku 未运行：无法远程重启，请手动重启手机");
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            append("Shizuku 未授权，先点「开始执行」完成授权");
            return;
        }
        append("通过 Shizuku 重启手机…");
        Intent intent = new Intent(this, GhostRootService.class).setAction(GhostRootService.ACTION_REBOOT);
        startForegroundService(intent);
    }

    private void append(String line) {
        logView.append("[" + clock.format(new Date()) + "] " + line + "\n");
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        /* v26: 只清自己的 sink；任务仍在跑时服务端缓冲会保留，下次
         * onResume attach() 回放。 */
        if (GhostRootService.sink == activitySink) {
            GhostRootService.sink = null;
        }
        super.onDestroy();
    }
}
