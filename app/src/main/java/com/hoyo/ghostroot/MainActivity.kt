package com.hoyo.ghostroot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hoyo.ghostroot.ui.ButtonState
import com.hoyo.ghostroot.ui.GhostRootScreen
import com.hoyo.ghostroot.ui.GhostRootTheme
import com.hoyo.ghostroot.ui.GhostRootUiState
import com.hoyo.ghostroot.ui.InterruptedBanner
import com.hoyo.ghostroot.ui.LogEntry
import com.hoyo.ghostroot.ui.LogLevel
import com.hoyo.ghostroot.ui.ShizukuState
import com.hoyo.ghostroot.ui.ShizukuStatus
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 纯手动模式：
 * - 打开 App 启动前台服务待命，并检查 Shizuku 状态
 * - 只有点击「开始执行」按钮才会提权
 * - Jetpack Compose UI 前端全面重构，业务逻辑与执行通道 100% 保持一致
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "GR_UI"
        private const val REQ_PERMISSION = 1001
        private const val CLI_PATH = "/data/local/tmp/ghostlock"
        private const val PROFILE_PATH = "/data/local/tmp/profile.bin"

        private const val ACTION_ADB_RUN = "com.hoyo.ghostroot.action.ADB_RUN"
        private const val ACTION_ADB_REPAIR = "com.hoyo.ghostroot.action.ADB_REPAIR"
        private const val ACTION_ADB_START_SHIZUKU = "com.hoyo.ghostroot.action.ADB_START_SHIZUKU"
        private const val ACTION_ADB_OPEN_LSPOSED = "com.hoyo.ghostroot.action.ADB_OPEN_LSPOSED"

        private const val BTN_SHIZUKU_ROOT = "启动 Shizuku（root，提权后点我）"
        private const val BTN_OPEN_LSPOSED = "打开 LSPosed 管理器（root 直达）"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val VERDICT_PATH = "/data/local/tmp/.gr_verdict"
        private const val ZN_REPAIR_MARK = "/data/local/tmp/.gr_zn_repair"

        private const val REPAIR_SHIZUKU_WAIT_ROUNDS = 10 // 10 × 2s = 20s
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val logIdGen = AtomicLong(1L)

    private var repairShizukuWaitLeft = 0
    private var activitySink: GhostRootService.Sink? = null

    private var lsposedServiceArgs: Shizuku.UserServiceArgs? = null
    private var lsposedConnection: ServiceConnection? = null
    private val lsposedBindTimeout = Runnable {
        append("连接 Shizuku LSPosed 通道超时，请确认 Shizuku 正在运行且已授权")
        releaseLsposedService()
    }

    // Reactive Compose UI State
    private var uiState by mutableStateOf(
        GhostRootUiState(
            appVersion = BuildConfig.VERSION_NAME,
            buildCode = BuildConfig.VERSION_CODE,
            targetCli = CLI_PATH,
            targetProfile = PROFILE_PATH
        )
    )

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        append("Shizuku 已连接")
        refreshStatus(false)
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        append("Shizuku 已断开，请重新启动 Shizuku")
        refreshStatus(false)
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQ_PERMISSION) return@OnRequestPermissionResultListener
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        append(if (granted) "Shizuku 授权成功，可点击「开始执行」" else "Shizuku 授权被拒绝")
        refreshStatus(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GhostRootTheme {
                GhostRootScreen(
                    state = uiState,
                    onRunClick = { onRunClicked() },
                    onRepairClick = { onRepairClicked() },
                    onStartShizukuRootClick = { onStartShizukuRootClicked() },
                    onOpenLsposedClick = { onOpenLsposedClicked() },
                    onRebootClick = { onRebootClicked() },
                    onRequestShizukuPermission = { requestShizukuPermission() },
                    onStopServiceAndExit = { onStopServiceAndExit() },
                    onClearLogs = { onClearLogs() },
                    onCopyLogs = { onCopyLogs() },
                    onToggleAutoScroll = {
                        uiState = uiState.copy(isAutoScroll = !uiState.isAutoScroll)
                    }
                )
            }
        }

        startForegroundService(
            Intent(this, GhostRootService::class.java).setAction(GhostRootService.ACTION_START)
        )

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
        }

        Shizuku.addBinderReceivedListener(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        append("GhostRoot v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) 就绪")
        append("目标: $CLI_PATH + $PROFILE_PATH")
        append("模式: 前台服务常驻，点击按钮才执行；可在通知中停止服务")
        refreshStatus(true)
        maybeHandleAdbAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeHandleAdbAction(intent)
    }

    private fun maybeHandleAdbAction(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val run = ACTION_ADB_RUN == action
        val repair = ACTION_ADB_REPAIR == action
        val startShizuku = ACTION_ADB_START_SHIZUKU == action
        val openLsposed = ACTION_ADB_OPEN_LSPOSED == action

        if (!run && !repair && !startShizuku && !openLsposed) return

        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID && uid != Process.myUid()) {
            append("收到 $action 但调用方 uid=$uid 不在白名单，忽略")
            return
        }

        val actionDesc = when {
            run -> "执行"
            repair -> "ZN 修复"
            startShizuku -> "root 启动 Shizuku"
            else -> "打开 LSPosed"
        }
        append("ADB 触发 $actionDesc（caller uid=$uid），1.5s 后开始…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mainHandler.postDelayed({
            if (!isFinishing) {
                when {
                    run -> onRunClicked()
                    repair -> onRepairClicked()
                    startShizuku -> onStartShizukuRootClicked()
                    else -> onOpenLsposedClicked()
                }
            }
        }, 1500L)
    }

    private fun shizukuInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun refreshStatus(verbose: Boolean) {
        if (!shizukuInstalled()) {
            if (verbose) append("Shizuku: 未安装（需要先安装 Shizuku 应用）")
            uiState = uiState.copy(
                shizukuState = ShizukuState(
                    status = ShizukuStatus.NOT_INSTALLED,
                    summary = "未安装 Shizuku，请先安装并启动"
                )
            )
            return
        }

        val running = Shizuku.pingBinder()
        if (verbose) {
            append("Shizuku: 已安装, " + if (running) "运行中" else "未运行（请打开 Shizuku 用无线调试启动）")
        }

        if (!running) {
            uiState = uiState.copy(
                shizukuState = ShizukuState(
                    status = ShizukuStatus.NOT_RUNNING,
                    summary = "已安装但未运行，请打开无线调试启动，或提权后通过 Root 启动"
                )
            )
            return
        }

        try {
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            if (verbose) {
                append("Shizuku 授权: " + if (granted) "已授予，可以点击「开始执行」" else "未授予，点击「开始执行」时会弹出授权")
            } else {
                append("Shizuku 授权: " + if (granted) "已授予" else "未授予")
            }

            uiState = uiState.copy(
                shizukuState = ShizukuState(
                    status = if (granted) ShizukuStatus.AUTHORIZED else ShizukuStatus.UNAUTHORIZED,
                    summary = if (granted) "Shizuku 服务正常运行，已获得权限" else "Shizuku 服务正在运行，等待授权"
                )
            )
        } catch (t: Throwable) {
            append("授权检查失败: $t")
        }
    }

    private fun requestShizukuPermission() {
        if (!shizukuInstalled()) {
            append("Shizuku 未安装，无法授权")
            return
        }
        if (!Shizuku.pingBinder()) {
            append("Shizuku 未运行，请先启动 Shizuku 服务")
            return
        }
        try {
            Shizuku.requestPermission(REQ_PERMISSION)
        } catch (t: Throwable) {
            append("请求 Shizuku 授权失败: $t")
        }
    }

    override fun onResume() {
        super.onResume()
        if (activitySink == null) {
            activitySink = makeSink()
        }

        if (GhostRootService.running) {
            GhostRootService.attach(activitySink)
            uiState = uiState.copy(
                runButton = ButtonState("运行中…", false, inProgress = true),
                repairButton = ButtonState("修复中…", false, inProgress = true)
            )
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            append("已重新连接正在执行的任务（历史日志已回放）")
        } else if (GhostRootService.hasPendingResult()) {
            showInterruptedBanner()
            GhostRootService.attach(activitySink)
        } else {
            showInterruptedBanner()
        }

        if (!GhostRootService.running) {
            refreshButtonStates()
        }
    }

    private fun freshThisBoot(ts: Long): Boolean {
        if (ts <= 0) return false
        val age = System.currentTimeMillis() / 1000 - ts
        return age >= 0 && age <= readUptimeSeconds()
    }

    private fun refreshButtonStates() {
        var rootLanded = false
        var zn = ""
        var uapi = ""
        var net = ""
        val vf = readTmpFile(VERDICT_PATH)
        if (vf != null) {
            var ts = 0L
            var verdict = Int.MIN_VALUE
            for (line in vf.split("\n")) {
                val eq = line.indexOf('=')
                if (eq < 0) continue
                val k = line.substring(0, eq).trim()
                val v = line.substring(eq + 1).trim()
                when (k) {
                    "ts" -> ts = v.toLongOrNull() ?: 0L
                    "verdict" -> verdict = v.toIntOrNull() ?: Int.MIN_VALUE
                    "zn" -> zn = v
                    "uapi" -> uapi = v
                    "net" -> net = v
                }
            }
            rootLanded = (verdict == 0 && freshThisBoot(ts))
        }

        val sepolicyPending = rootLanded && ("uapi2-to-5" == uapi || net.contains("reboot-pending"))
        Log.i(TAG, "refresh: vf=${vf?.length ?: 0}B uptime=${readUptimeSeconds()}s rootLanded=$rootLanded zn=$zn repaired=${zn.startsWith("zn-ok")}")

        var repaired = false
        var repairScheduled = false
        if (rootLanded) {
            if (zn.startsWith("zn-ok")) {
                repaired = true
            } else if (zn.contains("softreboot")) {
                repairScheduled = true
            }
            val mk = readTmpFile(ZN_REPAIR_MARK)
            if (mk != null) {
                var ts = 0L
                var outcome = ""
                for (line in mk.split("\n")) {
                    val eq = line.indexOf('=')
                    if (eq < 0) continue
                    val k = line.substring(0, eq).trim()
                    val v = line.substring(eq + 1).trim()
                    if ("ts" == k) {
                        ts = v.toLongOrNull() ?: 0L
                    } else if ("outcome" == k) {
                        outcome = v
                    }
                }
                if (freshThisBoot(ts)) {
                    if (outcome.startsWith("zn-ok")) {
                        repaired = true
                    } else if (outcome.contains("softreboot")) {
                        repairScheduled = true
                    }
                }
            }
        }

        if (GhostRootService.running) return

        val newRunBtn = if (rootLanded) {
            ButtonState("✅ 已提权（本 boot，重启后可再执行）", false)
        } else {
            ButtonState("开始执行", true)
        }

        val newRepairBtn = if (repaired) {
            val text = if (sepolicyPending) {
                "✅ 已修复（sepolicy 待整机重启，LSPosed 可能仍异常）"
            } else {
                "✅ 已修复（本 boot）— 可再点复查"
            }
            ButtonState(text, true)
        } else if (repairScheduled) {
            ButtonState("修复已调度：待软重启后点我复查", true)
        } else {
            ButtonState(if (rootLanded) "Zygisk 修复（提权后点我）" else "Zygisk 修复（需先提权）", true)
        }

        uiState = uiState.copy(
            rootLanded = rootLanded,
            znStatus = zn,
            sepolicyPending = sepolicyPending,
            runButton = newRunBtn,
            repairButton = newRepairBtn
        )
    }

    private fun showInterruptedBanner() {
        try {
            val state = readTmpFile("/data/local/tmp/.gr_state") ?: return
            if (state.isEmpty()) return

            var ts = 0L
            var phase = "?"
            var round = "?"
            for (tok in state.trim().split("\\s+".toRegex())) {
                when {
                    tok.startsWith("ts=") -> ts = tok.substring(3).toLongOrNull() ?: 0L
                    tok.startsWith("phase=") -> phase = tok.substring(6)
                    tok.startsWith("round=") -> round = tok.substring(6)
                }
            }
            if (ts <= 0) return

            val inFlight = setOf("preflight", "attacking", "verifying", "cooldown")
            if (!inFlight.contains(phase)) return

            val vf = readTmpFile("/data/local/tmp/.gr_verdict")
            if (vf != null) {
                for (line in vf.split("\n")) {
                    if (line.startsWith("ts=")) {
                        val vts = line.substring(3).trim().toLongOrNull() ?: 0L
                        if (vts >= ts) return
                        break
                    }
                }
            }

            val uptime = readUptimeSeconds()
            val age = System.currentTimeMillis() / 1000 - ts
            if (age > uptime) {
                val df = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                val msg = "上次任务于 ${df.format(Date(ts * 1000))} 在第 $round 轮($phase 阶段)后因设备重启中断。" +
                        "等 Shizuku 就绪后点「开始执行」继续（每轮都是独立抽奖，无需特别恢复）。"
                uiState = uiState.copy(
                    interruptedBanner = InterruptedBanner(
                        visible = true,
                        timeStr = df.format(Date(ts * 1000)),
                        round = round,
                        phase = phase,
                        message = msg
                    )
                )
                append("⚠️ $msg")
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun readUptimeSeconds(): Float {
        return SystemClock.elapsedRealtime() / 1000f
    }

    private fun readTmpFile(path: String): String? {
        return try {
            BufferedReader(InputStreamReader(FileInputStream(path), StandardCharsets.UTF_8)).use { r ->
                val sb = StringBuilder()
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    sb.append(line).append('\n')
                }
                sb.toString()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun makeSink(): GhostRootService.Sink {
        return object : GhostRootService.Sink {
            override fun onLine(line: String) {
                runOnUiThread { append(line) }
            }

            override fun onDone(code: Int, verdict: Int, detail: String?) {
                runOnUiThread {
                    if (detail != null && detail.contains("zn-repair")) {
                        append("=== " + (if (verdict == 0) "✅ Zygisk 修复完成" else "❌ 修复未成功: $detail") + " ===")
                        if (verdict == 0) {
                            append("若安排了框架软重启，~40s 后回来再点一次「Zygisk 修复」复查终态")
                        }
                    } else {
                        val v = if (verdict == 0) "ROOT 成功(已验证)" else (if (verdict == 1) "结果未知" else "未成功")
                        append("=== 结束 exit=$code $v ($detail) ===")
                        if (verdict == 0) {
                            val managerOk = detail?.let {
                                it.contains("magica=manager-patched") || it.contains("magica=already-patched")
                            } == true
                            if (managerOk) {
                                append("✅ 现在可以打开 KernelSU 管理器（32680 版）使用了；建议顺手点「Zygisk 修复」把模块链拉起来")
                            } else {
                                append("⚠️ root 成功，但管理器补丁状态未确认（magica 结果见 detail）——打开管理器前先在 PC 跑 fix.sh")
                            }
                            if (code != 0) {
                                append("注: native exit=$code 只是 exploit 进程退出码；身份以 su -c id 的 uid=0 为准，勿以 rc 判定成败")
                            }
                        } else if (code == -2) {
                            append("⚠️ 执行前中止（预检失败）：一般是设备文件缺失或 profile md5 不匹配，看上方日志找原因")
                        } else if (verdict == 1) {
                            append("❓ 结果未知：若设备已重启，等自启后直接再来一发（结果详见通知与 logcat）")
                        } else {
                            append("提示: 点「重启手机」进入下一轮 —— 重启后 90 秒内点「开始执行」")
                        }
                    }

                    uiState = uiState.copy(
                        runButton = ButtonState("开始执行", true),
                        repairButton = ButtonState("Zygisk 修复（提权后点我）", true)
                    )
                    refreshButtonStates()
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    private fun onRunClicked() {
        if (GhostRootService.running) {
            GhostRootService.attach(activitySink)
            uiState = uiState.copy(
                runButton = ButtonState("运行中…", false, inProgress = true)
            )
            append("已有任务在执行中，已为你重新接上日志")
            return
        }
        if (!shizukuInstalled()) {
            append("Shizuku 未安装，无法执行")
            return
        }
        if (!Shizuku.pingBinder()) {
            append("Shizuku 未运行：先打开 Shizuku 用无线调试启动，再回来点")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            append("请求 Shizuku 授权…")
            try {
                Shizuku.requestPermission(REQ_PERMISSION)
            } catch (t: Throwable) {
                append("请求授权失败: $t")
            }
            return
        }

        if (activitySink == null) {
            activitySink = makeSink()
        }
        GhostRootService.sink = activitySink
        uiState = uiState.copy(
            runButton = ButtonState("运行中…", false, inProgress = true)
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        append("----- 开始执行（手动触发）-----")
        val intent = Intent(this, GhostRootService::class.java).setAction(GhostRootService.ACTION_RUN)
        startForegroundService(intent)
    }

    private fun onRepairClicked() {
        if (GhostRootService.running) {
            GhostRootService.attach(activitySink)
            uiState = uiState.copy(
                repairButton = ButtonState("修复中…", false, inProgress = true)
            )
            append("已有任务在执行中，已为你重新接上日志")
            return
        }

        var alreadyRepaired = false
        var markZnOkFresh = false
        val vfZn = readTmpFile(VERDICT_PATH)
        if (vfZn != null) {
            var vfTs = 0L
            for (line in vfZn.split("\n")) {
                val eq = line.indexOf('=')
                if (eq < 0) continue
                val k = line.substring(0, eq).trim()
                val v = line.substring(eq + 1).trim()
                if ("ts" == k) {
                    vfTs = v.toLongOrNull() ?: 0L
                } else if ("zn" == k && v.startsWith("zn-ok")) {
                    alreadyRepaired = freshThisBoot(vfTs)
                }
            }
        }
        if (!alreadyRepaired) {
            val mk = readTmpFile(ZN_REPAIR_MARK)
            if (mk != null) {
                var mkTs = 0L
                var mkOutcome = ""
                for (line in mk.split("\n")) {
                    val eq = line.indexOf('=')
                    if (eq < 0) continue
                    val k = line.substring(0, eq).trim()
                    val v = line.substring(eq + 1).trim()
                    if ("ts" == k) {
                        mkTs = v.toLongOrNull() ?: 0L
                    } else if ("outcome" == k) {
                        mkOutcome = v
                    }
                }
                if (mkOutcome.startsWith("zn-ok") && freshThisBoot(mkTs)) {
                    alreadyRepaired = true
                    markZnOkFresh = true
                }
            }
        }
        if (alreadyRepaired) {
            append("ℹ 本 boot 已有修复留痕（" + (if (markZnOkFresh) "znRepair 成功" else "会话 zn-ok") + "）—— 允许复查重跑：软重启后 zygote 重生，留痕可能已过期")
        }

        if (!shizukuInstalled()) {
            repairShizukuWaitLeft = 0
            uiState = uiState.copy(repairButton = ButtonState("Zygisk 修复（提权后点我）", true))
            append("Shizuku 未安装，无法修复")
            return
        }
        if (!Shizuku.pingBinder()) {
            if (repairShizukuWaitLeft > 0) {
                repairShizukuWaitLeft--
                if (repairShizukuWaitLeft == 0) {
                    append("Shizuku 在等待窗口内未就绪，本次修复放弃（server 就绪后可再点重试）")
                    uiState = uiState.copy(repairButton = ButtonState("Zygisk 修复（提权后点我）", true))
                    return
                }
                append("  Shizuku 仍未运行，约 ${repairShizukuWaitLeft * 2}s 后再查…")
                uiState = uiState.copy(
                    repairButton = ButtonState("等待 Shizuku… (${repairShizukuWaitLeft * 2}s)", false, inProgress = true)
                )
                mainHandler.postDelayed({ onRepairClicked() }, 2000L)
                return
            }
            repairShizukuWaitLeft = REPAIR_SHIZUKU_WAIT_ROUNDS
            append("Shizuku 未运行，进入等待窗口（≤${REPAIR_SHIZUKU_WAIT_ROUNDS * 2}s）—— 自动化侧会在每轮触发前拉起 server…")
            uiState = uiState.copy(
                repairButton = ButtonState("等待 Shizuku…", false, inProgress = true)
            )
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            mainHandler.postDelayed({ onRepairClicked() }, 2000L)
            return
        }

        repairShizukuWaitLeft = 0
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            append("请求 Shizuku 授权…")
            try {
                Shizuku.requestPermission(REQ_PERMISSION)
            } catch (t: Throwable) {
                append("请求授权失败: $t")
            }
            return
        }

        if (activitySink == null) {
            activitySink = makeSink()
        }
        GhostRootService.sink = activitySink
        uiState = uiState.copy(
            repairButton = ButtonState("修复中…", false, inProgress = true)
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        append("----- ZN 一键修复（手动触发）-----")
        val intent = Intent(this, GhostRootService::class.java).setAction(GhostRootService.ACTION_ZN_REPAIR)
        startForegroundService(intent)
    }

    private fun onStartShizukuRootClicked() {
        if (!shizukuInstalled()) {
            append("Shizuku 未安装，无法启动")
            return
        }
        if (Shizuku.pingBinder()) {
            append("Shizuku 已在运行，无需启动")
            return
        }

        val ai = try {
            packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
        } catch (e: Exception) {
            append("解析 Shizuku 安装路径失败: $e")
            return
        }
        val binFromApi = ai.nativeLibraryDir + "/libshizuku.so"
        val logPath = "/data/local/tmp/gr_shizuku_root.log"

        startForegroundService(Intent(this, GhostRootService::class.java).setAction(GhostRootService.ACTION_START))
        uiState = uiState.copy(
            shizukuRootButton = ButtonState("启动中…", false, inProgress = true)
        )

        val t = Thread({
            try {
                ui { append("----- root 启动 Shizuku -----") }

                var id = ""
                var rootOk = false
                var hinted = false
                val deadline = System.currentTimeMillis() + 25_000L
                while (System.currentTimeMillis() < deadline) {
                    id = suOutput("id")
                    if (id.contains("uid=0")) {
                        rootOk = true
                        break
                    }
                    if (!hinted) {
                        hinted = true
                        ui {
                            append("⚠ 等待 KernelSU 超级用户授权：请在弹出的授权框点「允许」（首次使用必做）…")
                        }
                    }
                    Thread.sleep(1000)
                }

                if (!rootOk) {
                    val why = if (id.isEmpty()) "su 无输出/被拒绝（KSU 授权？）" else id
                    ui {
                        append("✗ root 未就绪: $why —— 请先「开始执行」提权，或在 KernelSU 里给 GhostRoot 授权 su")
                        resetShizukuRootButton()
                    }
                    return@Thread
                }

                val rootId = id
                ui { append("root 就绪: " + rootId.replace('\n', ' ')) }

                var bin = binFromApi
                val chk = suOutput("[ -x '$bin' ] && echo OK || echo MISSING")
                ui { append("starter(nativeLibraryDir): $binFromApi → $chk") }
                if (!chk.contains("OK")) {
                    val alt = suOutput("pm path $SHIZUKU_PACKAGE | head -1 | sed 's|^package:||; s|base.apk$|lib/arm64/libshizuku.so|'")
                    val altChk = if (alt.isEmpty()) "MISSING" else suOutput("[ -x '$alt' ] && echo OK || echo MISSING")
                    ui { append("starter(pm path 回退): $alt → $altChk") }
                    if (altChk.contains("OK")) {
                        bin = alt
                    } else {
                        ui {
                            append("✗ 找不到可执行的 libshizuku.so，无法启动")
                            resetShizukuRootButton()
                        }
                        return@Thread
                    }
                }

                val binFinal = bin
                val launch = suOutput("rm -f '$logPath'; setsid '$binFinal' </dev/null >'$logPath' 2>&1 & echo launched")
                ui { append("已投递 starter: " + launch.replace('\n', ' ')) }

                var pid = ""
                var binder = false
                for (i in 0 until 14) {
                    Thread.sleep(2000)
                    pid = suOutput("pidof shizuku_server")
                    binder = Shizuku.pingBinder()
                    if (pid.isNotEmpty() && binder) {
                        val okPid = pid
                        ui {
                            append("✅ Shizuku 已启动（root，pid=$okPid）—— 现在可以点「Zygisk 修复」")
                            uiState = uiState.copy(
                                shizukuRootButton = ButtonState("✅ Shizuku 已启动（root）", true)
                            )
                            refreshStatus(false)
                        }
                        return@Thread
                    }
                    if (i == 1 || i == 6) {
                        val p = pid
                        val b = binder
                        ui { append("等待中… server=" + (if (p.isEmpty()) "无" else p) + ", binder=" + b) }
                    }
                }

                val tail = suOutput("tail -25 '$logPath' 2>/dev/null")
                val p = pid
                val b = binder
                val diag = if (tail.isEmpty()) "(starter 日志为空)" else tail.replace('\n', ' ')
                ui {
                    append("✗ 未闭环: server=" + (if (p.isEmpty()) "无进程" else p) + ", binder=" + b)
                    append("starter 日志: $diag")
                    if (p.isNotEmpty()) {
                        append("→ server 已起但本 App 未绑定：退出重进 GhostRoot，或在 Shizuku App 里给 GhostRoot 授权一次")
                    } else {
                        append("→ server 未存活：检查 KernelSU 是否允许 GhostRoot 的 su 会话创建后台进程")
                    }
                    resetShizukuRootButton()
                }
            } catch (e: Exception) {
                ui {
                    append("启动 Shizuku 异常: $e")
                    resetShizukuRootButton()
                }
            }
        }, "start-shizuku-root")
        t.isDaemon = true
        t.start()
    }

    private fun ui(r: () -> Unit) {
        runOnUiThread(r)
    }

    private fun resetShizukuRootButton() {
        uiState = uiState.copy(
            shizukuRootButton = ButtonState(BTN_SHIZUKU_ROOT, true)
        )
    }

    private fun onOpenLsposedClicked() {
        if (lsposedConnection != null) return
        if (!Shizuku.pingBinder()) {
            append("Shizuku 未运行，请先启动 Shizuku，再打开 LSPosed 管理器")
            return
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                append("请授予 Shizuku 权限后，再点「打开 LSPosed 管理器」")
                Shizuku.requestPermission(REQ_PERMISSION)
                return
            }

            uiState = uiState.copy(
                openLsposedButton = ButtonState("调起中…", false, inProgress = true)
            )
            startForegroundService(
                Intent(this, GhostRootService::class.java).setAction(GhostRootService.ACTION_START)
            )
            append("通过 Shizuku 检测 LSPosed 状态并调起管理器…")

            val args = Shizuku.UserServiceArgs(ComponentName(this, RootUserService::class.java))
                .tag("lsposed-manager")
                .processNameSuffix("lsposed")
                .daemon(false)
                .debuggable(false)
                .version(46)
            lsposedServiceArgs = args

            lsposedConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    mainHandler.removeCallbacks(lsposedBindTimeout)
                    val worker = Thread({
                        val result = try {
                            IRootRunner.Stub.asInterface(binder).openLsposedManager()
                        } catch (e: Exception) {
                            "唤起 LSPosed 异常: $e"
                        }
                        ui {
                            if (!isDestroyed) append(result)
                            releaseLsposedService()
                        }
                    }, "open-lsposed-mgr")
                    worker.isDaemon = true
                    worker.start()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    append("Shizuku LSPosed 通道已断开，可重新点击尝试")
                    releaseLsposedService()
                }
            }

            mainHandler.postDelayed(lsposedBindTimeout, 15_000L)
            Shizuku.bindUserService(args, lsposedConnection!!)
        } catch (e: Exception) {
            append("连接 Shizuku LSPosed 通道失败: $e")
            releaseLsposedService()
        }
    }

    private fun releaseLsposedService() {
        mainHandler.removeCallbacks(lsposedBindTimeout)
        val conn = lsposedConnection
        val args = lsposedServiceArgs
        if (conn != null && args != null) {
            try {
                Shizuku.unbindUserService(args, conn, true)
            } catch (e: Exception) {
                if (!isDestroyed) append("释放 LSPosed 通道失败: $e")
            }
            lsposedConnection = null
        }
        uiState = uiState.copy(
            openLsposedButton = ButtonState(BTN_OPEN_LSPOSED, true)
        )
    }

    private fun onRebootClicked() {
        if (!Shizuku.pingBinder()) {
            append("Shizuku 未运行：无法远程重启，请手动重启手机")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            append("Shizuku 未授权，先点「开始执行」完成授权")
            return
        }
        append("通过 Shizuku 重启手机…")
        val intent = Intent(this, GhostRootService::class.java).setAction(GhostRootService.ACTION_REBOOT)
        startForegroundService(intent)
    }

    private fun onStopServiceAndExit() {
        stopService(Intent(this, GhostRootService::class.java))
        finishAndRemoveTask()
    }

    private fun onClearLogs() {
        uiState = uiState.copy(logs = emptyList())
        Toast.makeText(this, "控制台已清空", Toast.LENGTH_SHORT).show()
    }

    private fun onCopyLogs() {
        val allLogs = uiState.logs.joinToString("\n") { "[${it.time}] ${it.text}" }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("GhostRoot Logs", allLogs)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "日志已复制到剪贴板 (${uiState.logs.size} 行)", Toast.LENGTH_SHORT).show()
    }

    private fun append(line: String) {
        val timeStr = clock.format(Date())
        val level = when {
            line.contains("✅") || line.contains("成功") || line.contains("exit=0") || line.contains("PASS") -> LogLevel.SUCCESS
            line.contains("⚠️") || line.contains("⚠") || line.contains("等待") || line.contains("未知") -> LogLevel.WARNING
            line.contains("❌") || line.contains("✗") || line.contains("ERR") || line.contains("失败") || line.contains("拒绝") -> LogLevel.ERROR
            line.contains("===") || line.contains("-----") -> LogLevel.HEADER
            else -> LogLevel.INFO
        }

        val entry = LogEntry(
            id = logIdGen.getAndIncrement(),
            time = timeStr,
            text = line,
            level = level
        )
        // Keep up to 600 log items in memory
        val newLogs = (uiState.logs + entry).takeLast(600)
        uiState = uiState.copy(logs = newLogs)
    }

    private fun suOutput(cmd: String): String {
        val sb = java.lang.StringBuilder()
        try {
            val pb = ProcessBuilder("su", "-c", cmd)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val input = p.inputStream
            val reader = Thread({
                val buf = ByteArray(2048)
                var n: Int
                try {
                    while (input.read(buf).also { n = it } > 0) {
                        sb.append(String(buf, 0, n, StandardCharsets.UTF_8))
                    }
                } catch (ignored: Exception) {
                }
            }, "su-output")
            reader.isDaemon = true
            reader.start()
            if (!p.waitFor(12, TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
            reader.join(600)
        } catch (e: Exception) {
            return "ERR:$e"
        }
        return sb.toString().trim()
    }

    override fun onDestroy() {
        releaseLsposedService()
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        if (GhostRootService.sink == activitySink) {
            GhostRootService.sink = null
        }
        super.onDestroy()
    }
}
