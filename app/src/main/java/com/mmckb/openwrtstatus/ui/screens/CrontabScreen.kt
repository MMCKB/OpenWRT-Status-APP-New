package com.mmckb.openwrtstatus.ui.screens

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.remote.CrontabClient
import com.mmckb.openwrtstatus.data.remote.RouterException
import com.mmckb.openwrtstatus.ui.components.AppAlertType
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppIconButton
import com.mmckb.openwrtstatus.ui.components.AppTextField
import com.mmckb.openwrtstatus.ui.components.ConnectionMonitor
import com.mmckb.openwrtstatus.ui.components.StackedAlertHost
import com.mmckb.openwrtstatus.ui.components.rememberAlertStackState
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val CRONTAB_TIMEOUT_MS = 20_000L

private fun crontabErrText(e: Exception): String = when {
    e is TimeoutCancellationException -> "路由器连接中断或长时间无响应，请检查网络后重试。"
    e is CancellationException -> "连接已断开，操作已终止。"
    e is RouterException -> e.message ?: "请稍后重试。"
    else -> e.message ?: "请稍后重试。"
}

/**
 * 计划任务页（LuCI admin/system/crontab 的完整复刻）：
 *  - 读取 /etc/crontabs/root（SSH cat，与 LuCI fs.read 同源）；
 *  - 保存：trim、CRLF→LF、补尾换行后写回，chmod 0644 + /etc/init.d/cron reload
 *    （与 LuCI fs.write + cron reload 语义一致）。
 *  全部操作需要设备开启 SSH。
 */
@Composable
fun CrontabScreen(
    config: RouterConfig,
    sshEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val client = remember { CrontabClient() }
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
    var busy by remember { mutableStateOf(false) }
    val alertStack = rememberAlertStackState()
    val density = LocalDensity.current
    var alertTopPadding by remember { mutableStateOf(0.dp) }

    var content by remember { mutableStateOf("") }
    var loadedOnce by remember { mutableStateOf(false) }

    var opJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) {
        ConnectionMonitor.status.collect {
            if (it == ConnectionMonitor.Status.Offline) opJob?.cancel()
        }
    }

    fun setAlert(type: AppAlertType, title: String, description: String? = null) {
        alertStack.push(type, title, description)
    }

    fun ensureConnected(): Boolean {
        if (ConnectionMonitor.status.value == ConnectionMonitor.Status.Offline) {
            setAlert(AppAlertType.Error, "与路由器已断开连接", "请检查手机与路由器的网络后重试。")
            return false
        }
        return true
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
                content = withTimeout(CRONTAB_TIMEOUT_MS) {
                    withContext(kotlinx.coroutines.Dispatchers.IO) { client.load(config, sshOrNull) }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    setAlert(AppAlertType.Error, "计划任务读取失败", crontabErrText(e))
                }
            } finally {
                loading = false
            }
        }
    }

    fun save() {
        if (opJob?.isActive == true) return
        if (!ensureConnected()) return
        opJob = scope.launch {
            busy = true
            try {
                withTimeout(CRONTAB_TIMEOUT_MS) {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        client.save(config, sshOrNull, content)
                    }
                }
                setAlert(AppAlertType.Success, "已保存并重载 cron")
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    setAlert(AppAlertType.Error, "计划任务保存失败", crontabErrText(e))
                }
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 0.dp)
        ) {
            // 标题区（提示栈锚定其底部）
            Column(
                modifier = Modifier.onGloballyPositioned { coords ->
                    alertTopPadding = with(density) {
                        (coords.positionInParent().y + coords.size.height).toDp() + 8.dp
                    }
                }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppBackButton(onBack = onBack)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "计划任务",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    AppIconButton(onClick = { load() }, enabled = !loading && !busy) {
                        Text(
                            "⟳",
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.onSurface
                        )
                    }
                }
                Text(
                    "系统计划任务（crontab），保存后自动重载 cron",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
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
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "这是系统计划任务（/etc/crontabs/root），定时任务在此定义。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        AppTextField(
                            value = content,
                            onValueChange = { content = it },
                            label = { Text("/etc/crontabs/root") },
                            minLines = 12,
                            maxLines = 24,
                            enabled = !busy,
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { save() },
                                enabled = !busy,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (busy) "正在保存…" else "保存并重载 cron") }
                        }
                    }
                    // 背景延伸到手势条区域
                    Spacer(Modifier.navigationBarsPadding().height(6.dp))
                }
            }
        }

        // 悬浮提示栈：锚定标题区底部；测量完成前不渲染
        if (alertTopPadding > 0.dp) {
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
}
