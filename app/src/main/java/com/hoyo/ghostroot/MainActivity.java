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
 * - 打开 App 启动前台服务待命，并检查 Shizuku 状态
 * - 只有点击「开始执行」按钮才会提权
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMISSION = 1001;
    private static final String CLI_PATH = "/data/local/tmp/ghostlock";
    private static final String PROFILE_PATH = "/data/local/tmp/profile.bin";
    /** v46/v51: adb 自动化入口 ——
     *   `am start -a com.hoyo.ghostroot.action.ADB_RUN`    → 提权
     *   `am start -a com.hoyo.ghostroot.action.ADB_REPAIR` → ZN 一键修复（提权后）
     *   `am start -a com.hoyo.ghostroot.action.ADB_START_SHIZUKU` → root 启动 Shizuku
     * 仅接受 shell(2000)/root(0)/本应用 uid 的调用方（保持 P0 审查的防护语义：
     * 普通第三方应用带着该 action 启动会被忽略）。
     * v51: 供 scripts/gr_autoloop.sh 在提权成功后触发修复并验证结果，
     * 避免 UI 自动化（uiautomator 会被 GKD 抢占）点击按钮的脆弱性。
     * v63: 第三个入口让 root 启动 Shizuku 也能被 adb 端到端验证/排障。 */
    private static final String ACTION_ADB_RUN = "com.hoyo.ghostroot.action.ADB_RUN";
    private static final String ACTION_ADB_REPAIR = "com.hoyo.ghostroot.action.ADB_REPAIR";
    private static final String ACTION_ADB_START_SHIZUKU = "com.hoyo.ghostroot.action.ADB_START_SHIZUKU";
    private static final String ACTION_ADB_OPEN_VECTOR = "com.hoyo.ghostroot.action.ADB_OPEN_VECTOR";

    private TextView logView;
    private Button runButton;
    private Button rebootButton;
    /** v49: ZN 一键修复 —— 提权成功后手机端自愈，不依赖 PC。 */
    private Button repairButton;
    /** v62: root 启动 Shizuku —— 提权成功后用 su 直接拉起 server，
     * 不依赖 PC / 无线调试；启动完即可点「Zygisk 修复」。 */
    private Button shizukuRootButton;
    private static final String BTN_SHIZUKU_ROOT = "启动 Shizuku（root，提权后点我）";
    /** v66: 打开 Vector 管理器 —— 针对 Vector 寄生架构在 Enforcing 下
     * 点桌面独立图标报"未激活"的问题，以 root 调起 com.android.shell 寄生端。 */
    private Button openVectorButton;
    private static final String BTN_OPEN_VECTOR = "打开 Vector 管理器（root 直达）";
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
        startForegroundService(new Intent(this, GhostRootService.class)
                .setAction(GhostRootService.ACTION_START));
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1002);
        }

        Shizuku.addBinderReceivedListener(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionListener);

        append("GhostRoot v" + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ") 就绪");
        append("目标: " + CLI_PATH + " + " + PROFILE_PATH);
        append("模式: 前台服务常驻，点击按钮才执行；可在通知中停止服务");
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
     * 不检查按钮禁用态，已显示「已修复」时仍可复查）。
     * v57: 修复触发若撞上 Shizuku server 被软重启杀死的窗口，onRepairClicked
     * 会进入 ≤20s 有界等待（自动化侧每轮触发前幂等重启 server），不再
     * 静默放弃 —— 软重启后修复链自愈闭环的关键一环。 */
    private void maybeHandleAdbAction(Intent intent) {
        if (intent == null) {
            return;
        }
        final String action = intent.getAction();
        final boolean run = ACTION_ADB_RUN.equals(action);
        final boolean repair = ACTION_ADB_REPAIR.equals(action);
        final boolean startShizuku = ACTION_ADB_START_SHIZUKU.equals(action);
        final boolean openVector = ACTION_ADB_OPEN_VECTOR.equals(action);
        if (!run && !repair && !startShizuku && !openVector) {
            return;
        }
        int uid = android.os.Binder.getCallingUid();
        if (uid != android.os.Process.SHELL_UID
                && uid != android.os.Process.ROOT_UID
                && uid != android.os.Process.myUid()) {
            append("收到 " + action + " 但调用方 uid=" + uid + " 不在白名单，忽略");
            return;
        }
        append("ADB 触发 " + (run ? "执行" : repair ? "ZN 修复" : startShizuku ? "root 启动 Shizuku" : "打开 Vector")
                + "（caller uid=" + uid + "），1.5s 后开始…");
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        runButton.postDelayed(() -> {
            if (!isFinishing()) {
                if (run) {
                    onRunClicked();
                } else if (repair) {
                    onRepairClicked();
                } else if (startShizuku) {
                    onStartShizukuRootClicked();
                } else {
                    onOpenVectorClicked();
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

        /* v62: root 启动 Shizuku —— 提权成功（KSU root 在）后，用 su 直接拉起
         * Shizuku server，免去 PC/无线调试；与 Shizuku App 的「通过 Root 启动」
         * 同语义。首次点击会弹 KSU 授权框（给 GhostRoot 授权 su），允许后永久。 */
        shizukuRootButton = new Button(this);
        shizukuRootButton.setText(BTN_SHIZUKU_ROOT);
        shizukuRootButton.setEnabled(true);
        shizukuRootButton.setOnClickListener(v -> onStartShizukuRootClicked());
        root.addView(shizukuRootButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* v66: 打开 Vector 管理器（root 直达） —— 针对 Vector 寄生架构直接点桌面图标
         * 报未激活的问题，通过 Shizuku 的 shell 通道调起寄生端 Activity。 */
        openVectorButton = new Button(this);
        openVectorButton.setText(BTN_OPEN_VECTOR);
        openVectorButton.setEnabled(true);
        openVectorButton.setOnClickListener(v -> onOpenVectorClicked());
        root.addView(openVectorButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rebootButton = new Button(this);
        rebootButton.setText("重启手机（快速重试下一轮）");
        rebootButton.setOnClickListener(v -> onRebootClicked());
        root.addView(rebootButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button stopServiceButton = new Button(this);
        stopServiceButton.setText("停止前台服务并退出");
        stopServiceButton.setOnClickListener(v -> {
            stopService(new Intent(this, GhostRootService.class));
            finishAndRemoveTask();
        });
        root.addView(stopServiceButton, new LinearLayout.LayoutParams(
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
        /* v64: verdict 的 uapi/net 字段 —— uapiGuard 换装（uapi2-to-5）后
         * sepolicy.rule 本 boot 注入失败（ksud 开机注入被 uapi 不匹配挡住），
         * vector/LSPosed 在 Enforcing 下缺规则会报"selinux 异常/未激活"，
         * 软重启救不了 sepolicy，只有整机重启才能恢复。UI 据此如实提示。 */
        String uapi = "";
        String net = "";
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
                } else if ("uapi".equals(k)) {
                    uapi = v;
                } else if ("net".equals(k)) {
                    net = v;
                }
            }
            rootLanded = verdict == 0 && freshThisBoot(ts);
        }
        boolean sepolicyPending = rootLanded
                && ("uapi2-to-5".equals(uapi) || net.contains("reboot-pending"));
        /* v49: 判定过程留痕 —— 按钮状态判定链任何一环（读文件/时钟/解析）
         * 失败都会静默回到初始按钮态，logcat 是唯一可观测点 */
        android.util.Log.i("GR_UI", "refresh: vf=" + (vf == null ? "null" : vf.length() + "B")
                + " uptime=" + readUptimeSeconds() + "s rootLanded=" + rootLanded
                + " zn=" + zn + " repaired=" + (zn.startsWith("zn-ok")));
        boolean repaired = false;
        /* v56: 「已调度」≠「已修复」—— zn-softreboot-scheduled 只是安排了
         * 框架软重启等复查，Zygisk 实际未必激活（实测 17:25 boot：会话
         * 调度后 daemon 仍未起，按钮却亮「已修复」误导用户）。单独状态
         * 表达，按钮保持可点。
         * v64: 原 repairNeedsRecheck 死变量已删（从未被置 true），「已修复」
         * 按钮恒可点，见下分支注释。 */
        boolean repairScheduled = false;
        if (rootLanded) {
            if (zn.startsWith("zn-ok")) {
                repaired = true;
            } else if (zn.contains("softreboot")) {
                repairScheduled = true;
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
                    if (outcome.startsWith("zn-ok")) {
                        repaired = true;
                    } else if (outcome.contains("softreboot")) {
                        repairScheduled = true;
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
            /* v64: 「已修复」不再禁点 —— freshThisBoot 用内核 uptime 判
             * "本 boot"，框架软重启（stop/start、ksud soft-reboot）不清
             * uptime，zn-ok 留痕会跨软重启"保鲜"；但软重启后 zygote 重生，
             * Zygisk/vector 可能已重新失效，灰死按钮让用户无法再触发修复
             * （2026-11-06 实测复现）。改诚实可点态：留痕照展示，允许随时
             * 复查（修复链幂等，已 hook 时 zn-ok 直接收尾，不重复软重启）。
             * sepolicyPending 时额外提示：vector 的 selinux 异常需整机重启。 */
            repairButton.setEnabled(true);
            repairButton.setText(sepolicyPending
                    ? "✅ 已修复（sepolicy 待整机重启，vector 可能仍异常）"
                    : "✅ 已修复（本 boot）— 可再点复查");
        } else if (repairScheduled) {
            /* v56: 已调度软重启但 Zygisk 未确认激活 —— 诚实状态，可点复查 */
            repairButton.setEnabled(true);
            repairButton.setText("修复已调度：待软重启后点我复查");
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
                    /* v58: 不再无条件点亮修复按钮 —— 先落到「进行中」基态，
                     * refreshButtonStates() 再按留痕/verdict 定成败禁点，
                     * 消除 success 后的短暂可点窗口（误触来源） */
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

    /** v57: Shizuku server 有界等待 —— 提权会话的 late-load 自愈
     * （zygiskAutoActivate）会安排一次框架软重启，stop/start 会杀死 adb
     * 启动的 Shizuku server（POSTMORTEM-20261104 / commit 82e61d5）。
     * 脚本侧自 v57 起每轮触发前幂等重启 server，server 就绪需要几秒；
     * 此时 ADB_REPAIR intent 已送达本页，若直接放弃，本轮修复就在
     * 手机端静默空转（2026-11-04 17:27/17:28 两次触发实测全灭）。
     * 这里在主线程用 postDelayed 轮询（每 2s 一查、共 10 轮 = 20s）：
     * binder 就绪即继续，超时如实报放弃并恢复按钮 —— 不吞修复请求。
     * 用户决策 54 保留：仍为 Shizuku 单路径，等待 ≠ 代执行。 */
    private static final int REPAIR_SHIZUKU_WAIT_ROUNDS = 10;   // 10 × 2s = 20s
    private int repairShizukuWaitLeft = 0;

    /** v49: ZN 一键修复入口 —— 守卫与 onRunClicked 相同（Shizuku 就绪是
     * 硬前提：修复经 UserService 的 su 通道执行），区别只在 action 与按钮态。
     * v64: v58 的「已修复防误触」硬拦截废除 —— 留痕 ts 用内核 uptime 判
     * "本 boot"，框架软重启不清 uptime，软重启后 zygote 重生、留痕可能
     * 已过期（vector/toast 重新失效），此时点修复却被门禁吞掉、按钮还
     * 被 refreshButtonStates 灰死，用户无法再触发修复（2026-11-06 实测
     * 复现）。复查重跑本身安全：修复链幂等，zygote 已 hook 时
     * zygiskAutoActivate 直接 zn-ok 收尾，不会重复调度软重启。 */
    private void onRepairClicked() {
        if (GhostRootService.running) {
            GhostRootService.attach(activitySink);
            repairButton.setEnabled(false);
            repairButton.setText("修复中…");
            append("已有任务在执行中，已为你重新接上日志");
            return;
        }
        /* v58: 修复成功后的防误触——与「已提权」按钮 disabled 语义一致。
         * 以前虽然 UI 会把按钮灰掉（refreshButtonStates），但点击入口与
         * ADB_REPAIR am start 都不查持久状态，灰按钮照样能再跑一遍：
         * 复跑本身幂等无害，但重放会重新走一遍 zygiskAutoActivate 的
         * 清理/软重启调度，把不必要的状态扰动带回来。这里以「verdict
         * zn= zn-ok* 或本 boot 新鲜留痕 outcome zn-ok*」为已修复真值，
         * v64 起命中仅作日志提示、不再拒绝（软重启后留痕可能已过期，
         * 硬拦截会把用户需要的复查一并吞掉）。 */
        boolean alreadyRepaired = false;
        boolean markZnOkFresh = false;
        String vfZn = readTmpFile(VERDICT_PATH);
        if (vfZn != null) {
            long vfTs = 0;
            for (String line : vfZn.split("\n")) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if ("ts".equals(k)) {
                    try {
                        vfTs = Long.parseLong(v);
                    } catch (NumberFormatException ignored) {
                    }
                } else if ("zn".equals(k) && v.startsWith("zn-ok")) {
                    alreadyRepaired = freshThisBoot(vfTs);
                }
            }
        }
        if (!alreadyRepaired) {
            String mk = readTmpFile(ZN_REPAIR_MARK);
            if (mk != null) {
                long mkTs = 0;
                String mkOutcome = "";
                for (String line : mk.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    if ("ts".equals(k)) {
                        try {
                            mkTs = Long.parseLong(v);
                        } catch (NumberFormatException ignored) {
                        }
                    } else if ("outcome".equals(k)) {
                        mkOutcome = v;
                    }
                }
                if (mkOutcome.startsWith("zn-ok") && freshThisBoot(mkTs)) {
                    alreadyRepaired = true;
                    markZnOkFresh = true;
                }
            }
        }
        if (alreadyRepaired) {
            /* v64: 不再 return/禁点 —— 留痕可能在框架软重启后已过期
             * （zygote 重生，Zygisk/vector 重新失效），如实提示后放行复查 */
            append("ℹ 本 boot 已有修复留痕（" + (markZnOkFresh ? "znRepair 成功" : "会话 zn-ok")
                    + "）—— 允许复查重跑：软重启后 zygote 重生，留痕可能已过期");
        }
        /* v54: 回归 Shizuku 单路径（用户决策：更加保险）。v53 的 root 直连
         * 回退实测不可行：KSU 对未授权 App 在 mount ns 里 umount 了 su
         * （run-as stat ENOENT、无 avc），App 永远看不到 su 二进制，无法
         * 发起授权请求 —— 死锁；且 background 线程直调 append 会触发
         * CalledFromWrongThreadException。脚本侧 repair_via_root 不受影响。 */
        if (!shizukuInstalled()) {
            repairShizukuWaitLeft = 0;
            repairButton.setEnabled(true);
            repairButton.setText("Zygisk 修复（提权后点我）");
            append("Shizuku 未安装，无法修复");
            return;
        }
        if (!Shizuku.pingBinder()) {
            if (repairShizukuWaitLeft > 0) {
                repairShizukuWaitLeft--;
                if (repairShizukuWaitLeft == 0) {
                    append("Shizuku 在等待窗口内未就绪，本次修复放弃（server 就绪后可再点重试）");
                    repairButton.setEnabled(true);
                    repairButton.setText("Zygisk 修复（提权后点我）");
                    return;
                }
                append("  Shizuku 仍未运行，约 " + (repairShizukuWaitLeft * 2) + "s 后再查…");
                repairButton.setText("等待 Shizuku… (" + (repairShizukuWaitLeft * 2) + "s)");
                repairButton.postDelayed(this::onRepairClicked, 2000L);
                return;
            }
            repairShizukuWaitLeft = REPAIR_SHIZUKU_WAIT_ROUNDS;
            append("Shizuku 未运行，进入等待窗口（≤" + (REPAIR_SHIZUKU_WAIT_ROUNDS * 2)
                    + "s）—— 自动化侧会在每轮触发前拉起 server…");
            repairButton.setEnabled(false);
            repairButton.setText("等待 Shizuku…");
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            repairButton.postDelayed(this::onRepairClicked, 2000L);
            return;
        }
        repairShizukuWaitLeft = 0;
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

    /** v62: root 启动 Shizuku server。前置：本 boot 已提权（KSU root 在）。
     * 流程：su 探测（首次弹 KSU 授权框，等待 ≤25s）→ su nohup 拉起
     * libshizuku.so（脱离 App 进程，adb 断开不受影响）→ 轮询 binder ≤20s。
     * 注意：root 启动的 server 授权记录存 Shizuku App 数据目录，与 adb 启动
     * （存 /data/local/tmp/）不同源 —— 两种方式切换后 GhostRoot 需重新授权
     * 一次（本 App 授权已持久，切换后第一次绑定会弹一次授权）。 */
    private void onStartShizukuRootClicked() {
        if (!shizukuInstalled()) {
            append("Shizuku 未安装，无法启动");
            return;
        }
        if (Shizuku.pingBinder()) {
            append("Shizuku 已在运行，无需启动");
            return;
        }
        final android.content.pm.ApplicationInfo ai;
        try {
            ai = getPackageManager().getApplicationInfo(SHIZUKU_PACKAGE, 0);
        } catch (Exception e) {
            append("解析 Shizuku 安装路径失败: " + e);
            return;
        }
        final String binFromApi = ai.nativeLibraryDir + "/libshizuku.so";
        final String logPath = "/data/local/tmp/gr_shizuku_root.log";
        startForegroundService(new Intent(this, GhostRootService.class)
                .setAction(GhostRootService.ACTION_START));
        shizukuRootButton.setEnabled(false);
        shizukuRootButton.setText("启动中…");
        final Thread t = new Thread(() -> {
            /* v62 踩坑：后台线程内所有 UI 更新必须 runOnUiThread
             * （CalledFromWrongThreadException）。v63 沿用。 */
            try {
                ui(() -> append("----- root 启动 Shizuku -----"));

                /* 1) root 探测：循环重试覆盖 KSU 首次授权弹窗；判据用输出含
                 * uid=0 而非 exitValue —— 有的 su 实现 exit 0 但并未真提权。 */
                String id = "";
                boolean rootOk = false;
                boolean hinted = false;
                long deadline = System.currentTimeMillis() + 25_000L;
                while (System.currentTimeMillis() < deadline) {
                    id = suOutput("id");
                    if (id.contains("uid=0")) {
                        rootOk = true;
                        break;
                    }
                    /* v63 实测踩坑：首次使用若 KernelSU 没给 GhostRoot 授权 su，
                     * 屏幕上会弹授权框而这里静默等到 25s 超时，用户只看到
                     * 「启动失败」却不知道该点哪里 —— 必须立刻显式提示。 */
                    if (!hinted) {
                        hinted = true;
                        ui(() -> append("⚠ 等待 KernelSU 超级用户授权："
                                + "请在弹出的授权框点「允许」（首次使用必做）…"));
                    }
                    Thread.sleep(1000);
                }
                if (!rootOk) {
                    final String why = id.isEmpty() ? "su 无输出/被拒绝（KSU 授权？）" : id;
                    ui(() -> {
                        append("✗ root 未就绪: " + why
                                + " —— 请先「开始执行」提权，或在 KernelSU 里给 GhostRoot 授权 su");
                        resetShizukuRootButton();
                    });
                    return;
                }
                final String rootId = id;
                ui(() -> append("root 就绪: " + rootId.replace('\n', ' ')));

                /* 2) starter 路径双保险：nativeLibraryDir 在部分 ROM 上会指向
                 * 不存在的 ABI 目录，回退到 pm path 推导（gr_autoloop 已验证路径）。 */
                String bin = binFromApi;
                String chk = suOutput("[ -x '" + bin + "' ] && echo OK || echo MISSING");
                ui(() -> append("starter(nativeLibraryDir): " + binFromApi + " → " + chk));
                if (!chk.contains("OK")) {
                    String alt = suOutput("pm path " + SHIZUKU_PACKAGE
                            + " | head -1 | sed 's|^package:||; s|base.apk$|lib/arm64/libshizuku.so|'");
                    String altChk = alt.isEmpty() ? "MISSING" : suOutput("[ -x '" + alt + "' ] && echo OK || echo MISSING");
                    ui(() -> append("starter(pm path 回退): " + alt + " → " + altChk));
                    if (altChk.contains("OK")) {
                        bin = alt;
                    } else {
                        ui(() -> {
                            append("✗ 找不到可执行的 libshizuku.so，无法启动");
                            resetShizukuRootButton();
                        });
                        return;
                    }
                }

                /* 3) 关键修复：setsid 让 starter 脱离 su 会话的进程组/session。
                 * v62 用 nohup 只忽略 SIGHUP，su 会话结束时 ksud 清理进程组会
                 * 把刚拉起的 server 一起带走 —— 表现为「启动失败、server 无进程」。
                 * stdin 必须 </dev/null，并把 starter 输出落盘便于排障。 */
                final String binFinal = bin;
                String launch = suOutput("rm -f '" + logPath + "'; setsid '" + binFinal
                        + "' </dev/null >'" + logPath + "' 2>&1 & echo launched");
                ui(() -> append("已投递 starter: " + launch.replace('\n', ' ')));

                /* 4) 双判据轮询：pidof 证明 server 进程活着，pingBinder 证明
                 * 本 App 拿到了 binder。只判 pingBinder 会把「server 已起但
                 * 未绑定」误报成「启动失败」。 */
                String pid = "";
                boolean binder = false;
                for (int i = 0; i < 14; i++) {
                    Thread.sleep(2000);
                    pid = suOutput("pidof shizuku_server");
                    binder = Shizuku.pingBinder();
                    if (!pid.isEmpty() && binder) {
                        final String okPid = pid;
                        ui(() -> {
                            append("✅ Shizuku 已启动（root，pid=" + okPid + "）—— 现在可以点「Zygisk 修复」");
                            shizukuRootButton.setText("✅ Shizuku 已启动（root）");
                            shizukuRootButton.setEnabled(true);
                            refreshStatus(false);
                        });
                        return;
                    }
                    if (i == 1 || i == 6) {
                        final String p = pid;
                        final boolean b = binder;
                        ui(() -> append("等待中… server=" + (p.isEmpty() ? "无" : p)
                                + ", binder=" + b));
                    }
                }

                /* 5) 失败分支给出可执行的下一步，而不是笼统一句「失败」。 */
                String tail = suOutput("tail -25 '" + logPath + "' 2>/dev/null");
                final String p = pid;
                final boolean b = binder;
                final String diag = tail.isEmpty() ? "(starter 日志为空)" : tail.replace('\n', ' ');
                ui(() -> {
                    append("✗ 未闭环: server=" + (p.isEmpty() ? "无进程" : p)
                            + ", binder=" + b);
                    append("starter 日志: " + diag);
                    if (!p.isEmpty()) {
                        append("→ server 已起但本 App 未绑定：退出重进 GhostRoot，"
                                + "或在 Shizuku App 里给 GhostRoot 授权一次");
                    } else {
                        append("→ server 未存活：检查 KernelSU 是否允许 GhostRoot 的 su 会话创建后台进程");
                    }
                    resetShizukuRootButton();
                });
            } catch (Exception e) {
                ui(() -> {
                    append("启动 Shizuku 异常: " + e);
                    resetShizukuRootButton();
                });
            }
        }, "start-shizuku-root");
        t.setDaemon(true);
        t.start();
    }

    private void ui(Runnable r) {
        runOnUiThread(r);
    }

    private void resetShizukuRootButton() {
        shizukuRootButton.setEnabled(true);
        shizukuRootButton.setText(BTN_SHIZUKU_ROOT);
    }

    private Shizuku.UserServiceArgs vectorServiceArgs;
    private android.content.ServiceConnection vectorConnection;
    private final Runnable vectorBindTimeout = () -> {
        append("连接 Shizuku Vector 通道超时，请确认 Shizuku 正在运行且已授权");
        releaseVectorService();
    };

    private void onOpenVectorClicked() {
        if (vectorConnection != null) return;
        if (!Shizuku.pingBinder()) {
            append("Shizuku 未运行，请先启动 Shizuku，再打开 Vector 管理器");
            return;
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                append("请授予 Shizuku 权限后，再点「打开 Vector 管理器」");
                Shizuku.requestPermission(REQ_PERMISSION);
                return;
            }
            openVectorButton.setEnabled(false);
            startForegroundService(new Intent(this, GhostRootService.class)
                    .setAction(GhostRootService.ACTION_START));
            append("通过 Shizuku 检测 Vector 状态并调起管理器…");
            // 独立 tag 避免解绑 Vector 时停止正在提权或修复的 UserService。
            vectorServiceArgs = new Shizuku.UserServiceArgs(
                    new android.content.ComponentName(this, RootUserService.class))
                    .tag("vector-manager").processNameSuffix("vector")
                    .daemon(false).debuggable(false).version(42);
            vectorConnection = new android.content.ServiceConnection() {
                @Override
                public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
                    openVectorButton.removeCallbacks(vectorBindTimeout);
                    Thread worker = new Thread(() -> {
                        String result;
                        try {
                            result = IRootRunner.Stub.asInterface(binder).openVectorManager();
                        } catch (Exception e) {
                            result = "唤起 Vector 异常: " + e;
                        }
                        final String message = result;
                        ui(() -> {
                            if (!isDestroyed()) append(message);
                            releaseVectorService();
                        });
                    }, "open-vector-mgr");
                    worker.setDaemon(true);
                    worker.start();
                }

                @Override
                public void onServiceDisconnected(android.content.ComponentName name) {
                    append("Shizuku Vector 通道已断开，可重新点击尝试");
                    releaseVectorService();
                }
            };
            openVectorButton.postDelayed(vectorBindTimeout, 15_000L);
            Shizuku.bindUserService(vectorServiceArgs, vectorConnection);
        } catch (Exception e) {
            append("连接 Shizuku Vector 通道失败: " + e);
            releaseVectorService();
        }
    }

    private void releaseVectorService() {
        openVectorButton.removeCallbacks(vectorBindTimeout);
        if (vectorConnection != null) {
            try {
                Shizuku.unbindUserService(vectorServiceArgs, vectorConnection, true);
            } catch (Exception e) {
                if (!isDestroyed()) append("释放 Vector 通道失败: " + e);
            }
            vectorConnection = null;
        }
        openVectorButton.setEnabled(true);
    }

    /** 同步执行一条 su 命令并合并 stdout/stderr；带 12s 超时，绝不永久阻塞。 */
    private static String suOutput(String cmd) {
        final StringBuilder sb = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            final java.io.InputStream in = p.getInputStream();
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[2048];
                int n;
                try {
                    while ((n = in.read(buf)) > 0) {
                        sb.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (Exception ignored) {
                }
            }, "su-output");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            reader.join(600);
        } catch (Exception e) {
            return "ERR:" + e;
        }
        return sb.toString().trim();
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
        releaseVectorService();
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
