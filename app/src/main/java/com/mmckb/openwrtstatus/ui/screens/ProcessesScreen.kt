package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.remote.ProcessInfo
import com.mmckb.openwrtstatus.data.remote.ProcessSignal
import com.mmckb.openwrtstatus.data.remote.ProcessesClient
import com.mmckb.openwrtstatus.data.remote.RouterException
import com.mmckb.openwrtstatus.ui.components.AppAlertType
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.components.AppIconButton
import com.mmckb.openwrtstatus.ui.components.ConnectionMonitor
import com.mmckb.openwrtstatus.ui.components.StackedAlertOverlay
import com.mmckb.openwrtstatus.ui.components.alertAnchor
import com.mmckb.openwrtstatus.ui.components.rememberAlertAnchorState
import com.mmckb.openwrtstatus.ui.components.rememberAlertStackState
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 进程列表读取的总时长上限。 */
private const val PROCESSES_LOAD_TIMEOUT_MS = 20_000L

/** 统一的失败文案：超时（连接中断/无响应）给出可操作的提示，其余透出原始信息。 */
private fun processesErrText(e: Exception): String = when {
    e is TimeoutCancellationException -> "路由器连接中断或长时间无响应，请检查网络后重试。"
    e is CancellationException -> "连接已断开，操作已终止。"
    else -> e.message ?: "请稍后重试。"
}

/**
 * 进程页（LuCI admin/status/processes 的复刻）：
 * 列表来自 ubus `luci getProcessList`（PID / 所有者 / 命令 / CPU% / 内存%，按 PID 升序），
 * 每个进程可发送三种信号（LuCI 同款）——挂起 SIGHUP / 关闭 SIGTERM / 强制关闭 SIGKILL，
 * 经 SSH `kill` 执行，发送前弹确认框，成功后自动刷新列表。
 */
@Composable
fun ProcessesScreen(
    config: RouterConfig,
    sshEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val client = remember { ProcessesClient() }
    val sshOrNull = remember(config) {
        if (sshEnabled) {
            SshConfig(
                host = config.sshHost.ifBlank { config.ip },
                port = config.sshPort,
                username = config.sshUsername,
                password = config.sshPassword
            )
        } else null
    }

    var loading by remember { mutableStateOf(true) }
    var processes by remember { mutableStateOf<List<ProcessInfo>>(emptyList()) }
    val alertStack = rememberAlertStackState()
    val alertAnchor = rememberAlertAnchorState()
    var pendingSignal by remember { mutableStateOf<Pair<ProcessInfo, ProcessSignal>?>(null) }

    fun setAlert(type: AppAlertType, title: String, description: String? = null) {
        alertStack.push(type, title, description)
    }

    /** 操作前的快速检查：已断开则直接红色提示，不再发起操作。 */
    fun ensureConnected(): Boolean {
        if (ConnectionMonitor.status.value == ConnectionMonitor.Status.Offline) {
            setAlert(AppAlertType.Error, "与路由器已断开连接", "请检查手机与路由器的网络后重试。")
            return false
        }
        return true
    }

    // 跟踪读取/操作任务；路由器断开连接时立即取消，界面即刻恢复
    var opJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) {
        ConnectionMonitor.status.collect { status ->
            if (status == ConnectionMonitor.Status.Offline) opJob?.cancel()
        }
    }

    fun load() {
        if (opJob?.isActive == true) return
        if (!ensureConnected()) {
            loading = false
            return
        }
        opJob = scope.launch {
            loading = true
            try {
                processes = withTimeout(PROCESSES_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.load(config) }
                }
            } catch (e: RouterException) {
                setAlert(AppAlertType.Error, "进程列表读取失败", processesErrText(e))
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "进程列表读取失败", processesErrText(e))
            } finally {
                loading = false
            }
        }
    }

    fun sendSignal(proc: ProcessInfo, signal: ProcessSignal) {
        if (opJob?.isActive == true) return
        if (!ensureConnected()) return
        opJob = scope.launch {
            try {
                withTimeout(PROCESSES_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.sendSignal(sshOrNull, proc.pid, signal) }
                }
                setAlert(
                    AppAlertType.Success,
                    "信号已发送",
                    "已向 PID ${proc.pid} 发送 ${signal.label}（SIG${signal.name}）信号。"
                )
                // 释放操作槽：自身还在 opJob 协程里，不置空的话 load() 的
                // isActive 守卫会拦住刷新（LuCI 同款：发信号后重载列表）
                opJob = null
                load()
            } catch (e: RouterException) {
                setAlert(AppAlertType.Error, "信号发送失败", e.hint ?: processesErrText(e))
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "信号发送失败", processesErrText(e))
            }
        }
    }

    var loadedOnce by remember { mutableStateOf(false) }
    if (!loadedOnce) {
        loadedOnce = true
        load()
    }

    // 浏览超过一屏后，标题右侧出现「回到顶部」按钮
    val listState = rememberLazyListState()
    val showBackToTop = listState.firstVisibleItemIndex > 0 ||
        listState.firstVisibleItemScrollOffset > 300

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 0.dp)
        ) {
            // 标题区（提示栈锚定其底部；浏览后右侧出现回到顶部按钮）
            Column(
                modifier = Modifier.alertAnchor(alertAnchor)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppBackButton(onBack = onBack)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "进程",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    AppIconButton(onClick = { load() }, enabled = !loading) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "刷新",
                            tint = colors.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                AnimatedVisibility(
                    visible = showBackToTop,
                    enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.8f, animationSpec = tween(200)),
                    exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.8f, animationSpec = tween(200)),
                    modifier = Modifier.clickable { scope.launch { listState.animateScrollToItem(0) } }
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.surfaceVariant)
                            .size(30.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = "回到顶部",
                            tint = colors.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Text(
                    "系统中正在运行的进程概况和状态。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                if (!sshEnabled) {
                    Text(
                        "发送信号需要 SSH 访问：请在设备编辑页开启 SSH 并保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (processes.isEmpty()) {
                        item {
                            Text(
                                "暂无进程信息。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                    items(processes, key = { it.pid }) { proc ->
                        ProcessCard(
                            proc = proc,
                            sshEnabled = sshEnabled,
                            onSignal = { signal -> pendingSignal = proc to signal }
                        )
                    }
                    // 列表内容垫在手势 inset 之上（滚动背景延伸到手势区）
                    item {
                        Spacer(Modifier.navigationBarsPadding().height(6.dp))
                    }
                }
            }
        }

        // 悬浮提示栈：锚定标题区下方（间隙/首帧 gate 统一在 StackedAlerts 组件内）
        StackedAlertOverlay(alertStack, alertAnchor)

        // 信号确认弹窗：展示 PID / 命令 / 信号名，确认后发送
        pendingSignal?.let { (proc, signal) ->
            AppDialog(
                title = "发送信号",
                message = "向 PID ${proc.pid}（${proc.user}）发送 ${signal.label}（SIG${signal.name}）信号？\n\n" +
                    proc.command.take(80) + if (proc.command.length > 80) "…" else "",
                confirmLabel = "发送",
                confirmColor = if (signal == ProcessSignal.HUP) null else colors.error,
                onConfirm = {
                    val target = pendingSignal
                    pendingSignal = null
                    if (target != null) sendSignal(target.first, target.second)
                },
                onDismiss = { pendingSignal = null }
            )
        }
    }
}

/** 单个进程卡片：首行 PID / 所有者 / CPU% / 内存%，命令等宽字体换行，底部信号按钮。 */
@Composable
private fun ProcessCard(
    proc: ProcessInfo,
    sshEnabled: Boolean,
    onSignal: (ProcessSignal) -> Unit
) {
    val colors = LocalAppColors.current
    AppCard(contentPadding = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                proc.pid.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = colors.primary
            )
            Spacer(Modifier.width(10.dp))
            Text(
                proc.user,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                "CPU ${proc.cpuPercent}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "内存 ${proc.memPercent}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            proc.command,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (sshEnabled) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SignalButton("挂起", colors.primary, colors.onPrimary) { onSignal(ProcessSignal.HUP) }
                SignalButton("关闭", colors.error, colors.onPrimary) { onSignal(ProcessSignal.TERM) }
                SignalButton("强制关闭", colors.error, colors.onPrimary) { onSignal(ProcessSignal.KILL) }
            }
        }
    }
}

/** 紧凑信号按钮（高度 32dp，高于默认内边距压缩）。 */
@Composable
private fun SignalButton(
    label: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        modifier = Modifier.heightIn(min = 32.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
