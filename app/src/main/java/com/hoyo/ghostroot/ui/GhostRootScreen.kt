package com.hoyo.ghostroot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GhostRootScreen(
    state: GhostRootUiState,
    onRunClick: () -> Unit,
    onRepairClick: () -> Unit,
    onStartShizukuRootClick: () -> Unit,
    onOpenLsposedClick: () -> Unit,
    onRebootClick: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onStopServiceAndExit: () -> Unit,
    onClearLogs: () -> Unit,
    onCopyLogs: () -> Unit,
    onToggleAutoScroll: () -> Unit
) {
    var showRebootConfirmDialog by remember { mutableStateOf(false) }
    var showExitConfirmDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Auto-scroll when new logs arrive and auto-scroll is enabled
    LaunchedEffect(state.logs.size, state.isAutoScroll) {
        if (state.isAutoScroll && state.logs.isNotEmpty()) {
            listState.animateScrollToItem(state.logs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "GhostRoot",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = "v${state.appVersion} (${state.buildCode})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showExitConfirmDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "停止服务并退出",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Interrupted / Crash Alert Banner
            if (state.interruptedBanner.visible) {
                InterruptedBannerCard(state.interruptedBanner)
            }

            // 2. Status & Bridge Overview Card
            StatusOverviewCard(
                shizukuState = state.shizukuState,
                rootLanded = state.rootLanded,
                znStatus = state.znStatus,
                sepolicyPending = state.sepolicyPending,
                onRequestShizukuPermission = onRequestShizukuPermission
            )

            // 3. Action Deck (Execution & Tools)
            ActionDeck(
                state = state,
                onRunClick = onRunClick,
                onRepairClick = onRepairClick,
                onStartShizukuRootClick = onStartShizukuRootClick,
                onOpenLsposedClick = onOpenLsposedClick,
                onRebootClick = { showRebootConfirmDialog = true }
            )

            // 4. Live Terminal Console
            TerminalConsole(
                logs = state.logs,
                listState = listState,
                isAutoScroll = state.isAutoScroll,
                onClearLogs = onClearLogs,
                onCopyLogs = onCopyLogs,
                onToggleAutoScroll = onToggleAutoScroll,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }

    // Reboot Dialog
    if (showRebootConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRebootConfirmDialog = false },
            icon = { Icon(Icons.Default.RestartAlt, contentDescription = null, tint = AccentRed) },
            title = { Text("确认重启手机？") },
            text = { Text("将通过 Shizuku 发起系统重启，用于清理内核上下文并快速进入下一轮提权测试。") },
            confirmButton = {
                Button(
                    onClick = {
                        showRebootConfirmDialog = false
                        onRebootClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                ) {
                    Text("立即重启")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Exit Dialog
    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            icon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = AccentRed) },
            title = { Text("停止服务并退出？") },
            text = { Text("将停止 GhostRoot 前台守护服务并关闭当前任务。") },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirmDialog = false
                        onStopServiceAndExit()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                ) {
                    Text("停止并退出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun StatusOverviewCard(
    shizukuState: ShizukuState,
    rootLanded: Boolean,
    znStatus: String,
    sepolicyPending: Boolean,
    onRequestShizukuPermission: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Shizuku Bridge Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val (statusColor, label) = when (shizukuState.status) {
                        ShizukuStatus.AUTHORIZED -> Pair(AccentGreen, "Shizuku 已连接并授权")
                        ShizukuStatus.UNAUTHORIZED -> Pair(AccentAmber, "Shizuku 未授权")
                        ShizukuStatus.NOT_RUNNING -> Pair(AccentAmber, "Shizuku 未运行")
                        ShizukuStatus.NOT_INSTALLED -> Pair(AccentRed, "Shizuku 未安装")
                    }

                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )

                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (shizukuState.status == ShizukuStatus.UNAUTHORIZED) {
                    FilledTonalButton(
                        onClick = onRequestShizukuPermission,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("授权", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Summary text
            Text(
                text = shizukuState.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), thickness = 0.8.dp)

            // Kernel / Root & Sepolicy badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Root badge
                StatusBadge(
                    label = if (rootLanded) "ROOT: 已提权 (本 boot)" else "ROOT: 未提权",
                    isActive = rootLanded,
                    activeColor = AccentGreen,
                    inactiveColor = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )

                // ZN badge
                val znRepaired = znStatus.startsWith("zn-ok")
                StatusBadge(
                    label = when {
                        sepolicyPending -> "SELinux: 待整机重启"
                        znRepaired -> "Zygisk: 已修复"
                        znStatus.contains("softreboot") -> "Zygisk: 已调度软重启"
                        else -> "Zygisk: 待修复"
                    },
                    isActive = znRepaired,
                    activeColor = if (sepolicyPending) AccentAmber else AccentPurple,
                    inactiveColor = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun StatusBadge(
    label: String,
    isActive: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (isActive) activeColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            0.8.dp,
            if (isActive) activeColor.copy(alpha = 0.4f) else inactiveColor.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isActive) activeColor else inactiveColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isActive) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun InterruptedBannerCard(banner: InterruptedBanner) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = AccentAmber.copy(alpha = 0.15f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, AccentAmber.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = AccentAmber,
                modifier = Modifier.size(20.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "检测到上次任务被重启中断",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AccentAmber
                )
                Text(
                    text = banner.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun ActionDeck(
    state: GhostRootUiState,
    onRunClick: () -> Unit,
    onRepairClick: () -> Unit,
    onStartShizukuRootClick: () -> Unit,
    onOpenLsposedClick: () -> Unit,
    onRebootClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Hero Primary Button (开始执行 / 运行中 / 已提权)
        val isRunning = state.runButton.inProgress || state.runButton.text.contains("运行中")
        val isRooted = state.rootLanded && !isRunning
        
        Button(
            onClick = onRunClick,
            enabled = state.runButton.enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRooted) AccentGreen else MaterialTheme.colorScheme.primary,
                disabledContainerColor = if (isRooted) AccentGreen.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else if (isRooted) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text(
                    text = state.runButton.text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Secondary Tools (Zygisk 修复, 启动 Shizuku root, 打开 LSPosed)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Zygisk Repair Button
            val repairInProgress = state.repairButton.inProgress || state.repairButton.text.contains("修复中") || state.repairButton.text.contains("等待")
            FilledTonalButton(
                onClick = onRepairClick,
                enabled = state.repairButton.enabled,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (repairInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.8.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        text = state.repairButton.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Shizuku Root Button
            val shizukuStarting = state.shizukuRootButton.inProgress || state.shizukuRootButton.text.contains("启动中")
            FilledTonalButton(
                onClick = onStartShizukuRootClick,
                enabled = state.shizukuRootButton.enabled,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (shizukuStarting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.8.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        text = state.shizukuRootButton.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Bottom row: LSPosed Manager + Quick Reboot
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onOpenLsposedClick,
                enabled = state.openLsposedButton.enabled,
                modifier = Modifier
                    .weight(1.2f)
                    .height(42.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        text = state.openLsposedButton.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            OutlinedButton(
                onClick = onRebootClick,
                enabled = state.rebootButton.enabled,
                modifier = Modifier
                    .weight(0.8f)
                    .height(42.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AccentRed
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        text = "重启手机",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun TerminalConsole(
    logs: List<LogEntry>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isAutoScroll: Boolean,
    onClearLogs: () -> Unit,
    onCopyLogs: () -> Unit,
    onToggleAutoScroll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF090D14)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF212836))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Terminal Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF101724))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "控制台日志",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFE2E8F0)
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF1E293B)
                    ) {
                        Text(
                            text = "${logs.size} 行",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                // Action buttons: Copy, Clear, AutoScroll
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Auto-scroll toggle
                    IconButton(
                        onClick = onToggleAutoScroll,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerticalAlignBottom,
                            contentDescription = "自动滚底",
                            tint = if (isAutoScroll) AccentCyan else Color(0xFF64748B),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Copy logs
                    IconButton(
                        onClick = onCopyLogs,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制日志",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Clear logs
                    IconButton(
                        onClick = onClearLogs,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ClearAll,
                            contentDescription = "清屏",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Log Console Body
            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(items = logs, key = { it.id }) { entry ->
                        val textColor = when (entry.level) {
                            LogLevel.SUCCESS -> AccentGreen
                            LogLevel.WARNING -> AccentAmber
                            LogLevel.ERROR -> AccentRed
                            LogLevel.HEADER -> AccentCyan
                            LogLevel.INFO -> Color(0xFFCBD5E1)
                        }

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "[${entry.time}] ",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = Color(0xFF64748B)
                                )
                            )
                            Text(
                                text = entry.text,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    fontWeight = if (entry.level == LogLevel.HEADER || entry.level == LogLevel.SUCCESS) FontWeight.SemiBold else FontWeight.Normal,
                                    color = textColor
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
