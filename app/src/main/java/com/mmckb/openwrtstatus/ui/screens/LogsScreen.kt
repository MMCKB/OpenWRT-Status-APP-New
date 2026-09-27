package com.mmckb.openwrtstatus.ui.screens

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.remote.LogsClient
import com.mmckb.openwrtstatus.data.remote.LogsData
import com.mmckb.openwrtstatus.data.remote.RouterException
import com.mmckb.openwrtstatus.ui.components.AppAlertType
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.ConnectionMonitor
import com.mmckb.openwrtstatus.ui.components.SmoothOptionSwitcher
import com.mmckb.openwrtstatus.ui.components.StackedAlertHost
import com.mmckb.openwrtstatus.ui.components.rememberAlertStackState
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 日志读取的总时长上限。 */
private const val LOGS_LOAD_TIMEOUT_MS = 45_000L

/** 统一的失败文案：超时（连接中断/无响应）给出可操作的提示，其余透出原始信息。 */
private fun logsErrText(e: Exception): String = when {
    e is TimeoutCancellationException -> "路由器连接中断或长时间无响应，请检查网络后重试。"
    e is CancellationException -> "连接已断开，操作已终止。"
    else -> e.message ?: "请稍后重试。"
}

/**
 * 日志页（LuCI admin/status/logs 的完整复刻，只读）：
 * 系统日志（logread）/ 内核日志（dmesg）两个页签，等宽字体展示，
 * 支持一键复制与刷新；需要设备开启 SSH。
 */
@Composable
fun LogsScreen(
    config: RouterConfig,
    sshEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val client = remember { LogsClient() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
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
    var logs by remember { mutableStateOf<LogsData?>(null) }
    val alertStack = rememberAlertStackState()
    val density = LocalDensity.current
    var alertTopPadding by remember { mutableStateOf(0.dp) }
    var tab by remember { mutableStateOf("syslog") }

    fun setAlert(type: AppAlertType, title: String, description: String? = null) {
        alertStack.push(type, title, description)
    }

    /** 读取前的快速检查：已断开则直接红色提示，不再发起操作。 */
    fun ensureConnected(): Boolean {
        if (ConnectionMonitor.status.value == ConnectionMonitor.Status.Offline) {
            setAlert(AppAlertType.Error, "与路由器已断开连接", "请检查手机与路由器的网络后重试。")
            return false
        }
        return true
    }

    // 跟踪读取任务；路由器断开连接时立即取消，界面即刻恢复
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
                logs = withTimeout(LOGS_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.load(config, sshOrNull) }
                }
            } catch (e: RouterException) {
                setAlert(AppAlertType.Error, "日志读取失败", logsErrText(e))
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "日志读取失败", logsErrText(e))
            } finally {
                loading = false
            }
        }
    }

    var loadedOnce by remember { mutableStateOf(false) }
    if (!loadedOnce) {
        loadedOnce = true
        load()
    }

    val currentText = when (tab) {
        "syslog" -> logs?.syslog.orEmpty()
        else -> logs?.kernel.orEmpty()
    }
    val lines = currentText.lineSequence().toList()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBackButton(onBack = onBack)
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(currentText))
                        android.widget.Toast.makeText(
                            context, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    enabled = currentText.isNotBlank()
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "复制",
                        tint = colors.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { load() }, enabled = !loading) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        tint = colors.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Text(
                "日志",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Text(
                "系统日志与内核日志",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.onGloballyPositioned { coords ->
                    // 提示栈锚定在标题区正下方
                    alertTopPadding = with(density) {
                        (coords.positionInParent().y + coords.size.height).toDp() + 8.dp
                    }
                }
            )
            Spacer(Modifier.height(10.dp))
            SmoothOptionSwitcher(
                options = listOf("syslog" to "系统日志", "kernel" to "内核日志"),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
            } else {
                val text = currentText
                if (text.isBlank()) {
                    Text(
                        "暂无日志" + if (sshOrNull == null) "（需要开启 SSH）" else "。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                } else {
                    Text(
                        "共 ${lines.size} 行",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        items(lines.size) { i ->
                            Text(
                                lines[i],
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = colors.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 悬浮提示栈：浮在内容上方（不推挤布局），位于标题区正下方
        StackedAlertHost(
            state = alertStack,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = alertTopPadding)
        )
    }
}
