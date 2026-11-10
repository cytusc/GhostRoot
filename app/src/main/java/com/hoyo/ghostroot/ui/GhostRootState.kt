package com.hoyo.ghostroot.ui

enum class LogLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
    HEADER
}

data class LogEntry(
    val id: Long,
    val time: String,
    val text: String,
    val level: LogLevel = LogLevel.INFO
)

enum class ShizukuStatus {
    NOT_INSTALLED,
    NOT_RUNNING,
    UNAUTHORIZED,
    AUTHORIZED
}

data class ShizukuState(
    val status: ShizukuStatus = ShizukuStatus.NOT_INSTALLED,
    val summary: String = "正在检测 Shizuku 状态…"
)

data class ButtonState(
    val text: String,
    val enabled: Boolean,
    val inProgress: Boolean = false
)

data class InterruptedBanner(
    val visible: Boolean = false,
    val timeStr: String = "",
    val round: String = "",
    val phase: String = "",
    val message: String = ""
)

data class GhostRootUiState(
    val appVersion: String = "",
    val buildCode: Int = 0,
    val targetCli: String = "/data/local/tmp/ghostlock",
    val targetProfile: String = "/data/local/tmp/profile.bin",
    val shizukuState: ShizukuState = ShizukuState(),
    val rootLanded: Boolean = false,
    val znStatus: String = "",
    val sepolicyPending: Boolean = false,
    val interruptedBanner: InterruptedBanner = InterruptedBanner(),
    val runButton: ButtonState = ButtonState("开始执行", true),
    val repairButton: ButtonState = ButtonState("Zygisk 修复（需先提权）", true),
    val shizukuRootButton: ButtonState = ButtonState("启动 Shizuku（root，提权后点我）", true),
    val openLsposedButton: ButtonState = ButtonState("打开 LSPosed 管理器（root 直达）", true),
    val rebootButton: ButtonState = ButtonState("重启手机（快速重试下一轮）", true),
    val logs: List<LogEntry> = emptyList(),
    val isAutoScroll: Boolean = true
)
