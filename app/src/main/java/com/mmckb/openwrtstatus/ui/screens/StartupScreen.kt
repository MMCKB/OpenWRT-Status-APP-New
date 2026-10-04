package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.remote.InitScript
import com.mmckb.openwrtstatus.data.remote.RouterException
import com.mmckb.openwrtstatus.data.remote.StartupClient
import com.mmckb.openwrtstatus.data.remote.StartupData
import com.mmckb.openwrtstatus.ui.components.AppAlertType
import com.mmckb.openwrtstatus.ui.components.AppIconButton
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppTextField
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

/** 读取/操作的总时长上限。 */
private const val STARTUP_LOAD_TIMEOUT_MS = 45_000L
private const val STARTUP_ACTION_TIMEOUT_MS = 30_000L

/** 统一的失败文案：超时（连接中断/无响应）给出可操作的提示，其余透出原始信息。 */
private fun startupErrText(e: Exception): String = when {
    e is TimeoutCancellationException -> "路由器连接中断或长时间无响应，请检查网络后重试。"
    e is CancellationException -> "连接已断开，操作已终止。"
    else -> e.message ?: "请稍后重试。"
}

/**
 * 启动项页（LuCI admin/system/startup 的完整复刻）：
 * 启动脚本页签：/etc/init.d 下全部可执行脚本，显示启动优先级、启用状态，
 * 支持启动/重启/重载/停止与启用/禁用（启用=开机自启，重启后生效）；
 * 本地启动脚本页签：/etc/rc.local 内容编辑。
 * 需要设备开启 SSH。
 */
@Composable
fun StartupScreen(
    config: RouterConfig,
    sshEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val client = remember { StartupClient() }
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
    var data by remember { mutableStateOf<StartupData?>(null) }
    val alertStack = rememberAlertStackState()
    val density = LocalDensity.current
    var alertTopPadding by remember { mutableStateOf(0.dp) }
    var tab by remember { mutableStateOf("init") }
    var rcLocalEdit by remember { mutableStateOf<String?>(null) }

    fun setAlert(type: AppAlertType, title: String, description: String? = null) {
        alertStack.push(type, title, description)
    }

    /** 点击操作前的快速检查：已断开则直接红色提示，不再发起操作。 */
    fun ensureConnected(): Boolean {
        if (ConnectionMonitor.status.value == ConnectionMonitor.Status.Offline) {
            setAlert(AppAlertType.Error, "与路由器已断开连接", "请检查手机与路由器的网络后重试。")
            return false
        }
        return true
    }

    // 跟踪进行中的任务；路由器断开连接时立即取消，界面即刻恢复
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
                data = withTimeout(STARTUP_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.load(config, sshOrNull) }
                }
                rcLocalEdit = data?.rcLocal
            } catch (e: RouterException) {
                setAlert(AppAlertType.Error, "启动项读取失败", startupErrText(e))
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "启动项读取失败", startupErrText(e))
            } finally {
                loading = false
            }
        }
    }

    fun runAction(name: String, action: String, successText: String) {
        if (!ensureConnected()) return
        scope.launch {
            busy = true
            try {
                withTimeout(STARTUP_ACTION_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.runAction(config, sshOrNull, name, action) }
                }
                setAlert(AppAlertType.Success, successText, "已执行 /etc/init.d/$name $action")
                load()
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "执行失败", "/etc/init.d/$name $action 执行失败")
            } finally {
                busy = false
            }
        }
    }

    fun saveRcLocal() {
        val content = rcLocalEdit ?: return
        if (!ensureConnected()) return
        scope.launch {
            busy = true
            try {
                withTimeout(STARTUP_ACTION_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.saveRcLocal(config, sshOrNull, content) }
                }
                setAlert(AppAlertType.Success, "已保存")
                load()
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "保存失败", startupErrText(e))
            } finally {
                busy = false
            }
        }
    }

    var loadedOnce by remember { mutableStateOf(false) }
    if (!loadedOnce) {
        loadedOnce = true
        load()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 12.dp)
        ) {
            // 标题区（提示栈锚定其底部；positionInParent 已含状态栏 inset）
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
                        "启动项",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    AppIconButton(onClick = { load() }, enabled = !loading && !busy) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "刷新",
                            tint = colors.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Text(
                    "启用/禁用开机自启脚本，编辑本地启动脚本",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            SmoothOptionSwitcher(
                options = listOf("init" to "启动脚本", "rc" to "本地启动脚本"),
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
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (tab == "init") {
                        item {
                            Text(
                                "在此启用或禁用已安装的启动脚本，更改在设备重启后生效。" +
                                    "⚠ 禁用 \"network\" 等必要脚本可能导致设备无法访问！",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        val scripts = data?.scripts.orEmpty()
                        if (scripts.isEmpty()) {
                            item {
                                Text(
                                    "未读取到启动脚本。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                        scripts.forEach { script ->
                            item {
                                InitScriptCard(
                                    script = script,
                                    busy = busy,
                                    onAction = { action, text -> runAction(script.name, action, text) }
                                )
                            }
                        }
                    } else {
                        item {
                            Text(
                                "这是 /etc/rc.local 的内容。在 exit 0 之前插入自定义命令，" +
                                    "它们将在开机流程末尾执行。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            AppTextField(
                                value = rcLocalEdit ?: "",
                                onValueChange = { rcLocalEdit = it },
                                label = { Text("/etc/rc.local") },
                                minLines = 10,
                                maxLines = 20,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { saveRcLocal() },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (busy) "正在保存…" else "保存") }
                        }
                    }
                }
            }
        }

        // 悬浮提示栈：浮在内容上方（不推挤布局），位于标题区正下方；
        // 首帧布局测量完成前不显示，避免提示盖住标题
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

private fun flashErrTextStatic(e: Exception): String = when {
    e is TimeoutCancellationException -> "路由器连接中断或长时间无响应，请检查网络后重试。"
    e is CancellationException -> "连接已断开，操作已终止。"
    else -> e.message ?: "请稍后重试。"
}

/** 单个启动脚本卡片：优先级 + 名称 + 启用切换 + 动作按钮。 */
@Composable
private fun InitScriptCard(
    script: InitScript,
    busy: Boolean,
    onAction: (action: String, successText: String) -> Unit
) {
    val colors = LocalAppColors.current
    // Status Accent：已启用 = 绿、已禁用 = 灰（点击底部 Accent Bar 切换启用/禁用）
    val stateLineColor = if (script.enabled) colors.success else colors.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    String.format("%02d", script.priority ?: 0),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.width(30.dp)
                )
                Text(
                    script.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
                // 状态胶囊徽章（方案 A）：淡底色 + 状态色文字
                Text(
                    if (script.enabled) "已启用" else "已禁用",
                    style = MaterialTheme.typography.labelMedium,
                    color = stateLineColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(stateLineColor.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("start" to "启动", "restart" to "重启", "reload" to "重载", "stop" to "停止")
                    .forEach { (action, label) ->
                        OutlinedButton(
                            onClick = { onAction(action, "$label 已执行") },
                            enabled = !busy,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp, vertical = 2.dp
                            )
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
            }
        }
        // 底部 Status Accent Bar：贴卡片底边、被圆角裁切；绿 = 已启用、灰 = 已禁用
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clickable(enabled = !busy) {
                    onAction(
                        if (script.enabled) "disable" else "enable",
                        if (script.enabled) "已禁用（重启后生效）" else "已启用（重启后生效）"
                    )
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(stateLineColor)
            )
        }
    }
}
