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

    private TextView logView;
    private Button runButton;
    private Button rebootButton;
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
        try {
            String s = readTmpFile("/proc/uptime").trim();
            int sp = s.indexOf(' ');
            return Float.parseFloat(sp > 0 ? s.substring(0, sp) : s);
        } catch (Throwable t) {
            return 0f;
        }
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
                    String v = verdict == 0 ? "ROOT 成功(已验证)" : (verdict == 1 ? "结果未知" : "未成功");
                    append("=== 结束 exit=" + code + " " + v + " (" + detail + ") ===");
                    if (verdict == 0) {
                        /* v32: root 成功 ≠ 管理器可用 —— 按 magica 结果分流 */
                        boolean managerOk = detail.contains("magica=manager-patched")
                                || detail.contains("magica=already-patched");
                        if (managerOk) {
                            append("✅ 现在可以打开 KernelSU 管理器（32680 版）使用了");
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
                    runButton.setEnabled(true);
                    runButton.setText("开始执行");
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
