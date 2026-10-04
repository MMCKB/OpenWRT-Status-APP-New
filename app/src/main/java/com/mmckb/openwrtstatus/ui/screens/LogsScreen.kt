package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import com.mmckb.openwrtstatus.ui.components.AppIconButton
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.components.AppSwitch
import com.mmckb.openwrtstatus.ui.components.AppTextField
import com.mmckb.openwrtstatus.ui.components.ConnectionMonitor
import com.mmckb.openwrtstatus.ui.components.SmoothOptionSwitcher
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

/** 日志读取的总时长上限。 */
private const val LOGS_LOAD_TIMEOUT_MS = 45_000L

/** 统一的失败文案：超时（连接中断/无响应）给出可操作的提示，其余透出原始信息。 */
private fun logsErrText(e: Exception): String = when {
    e is TimeoutCancellationException -> "路由器连接中断或长时间无响应，请检查网络后重试。"
    e is CancellationException -> "连接已断开，操作已终止。"
    else -> e.message ?: "请稍后重试。"
}

private data class LogsSelectState(
    val title: String,
    val options: List<Pair<String, String>>,
    val selected: String,
    val onPick: (String) -> Unit
)

/** syslog 的 facility 名称（与 logread 输出一致）。 */
private val SYSLOG_FACILITIES = listOf(
    "kern", "user", "mail", "daemon", "auth", "syslog", "lpr", "news", "uucp",
    "cron", "authpriv", "ftp", "ntp", "logaudit", "logalert",
    "local0", "local1", "local2", "local3", "local4", "local5", "local6", "local7"
)

private val SYSLOG_SEVERITIES = listOf(
    "emerg", "alert", "crit", "err", "warn", "notice", "info", "debug"
)

/**
 * 日志页（LuCI admin/status/logs = status/syslog + status/dmesg 的完整复刻，只读）：
 * 系统日志（logread）/ 内核日志（dmesg -r）两个页签；按 LuCI 提供设施/级别/标签/文本
 * 过滤（各带「非」反转）与「最新在前」排序、等宽字体展示、一键复制与下载；
 * 需要设备开启 SSH。
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
    val alertAnchor = rememberAlertAnchorState()
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

    // ---- 过滤状态（语义与 LuCI LogreadBox / dmesg 视图一致） ----
    var sysFacility by remember { mutableStateOf("any") }
    var sysFacilityNot by remember { mutableStateOf(false) }
    var sysSeverity by remember { mutableStateOf("any") }
    var sysSeverityNot by remember { mutableStateOf(false) }
    var sysTag by remember { mutableStateOf("") }
    var sysText by remember { mutableStateOf("") }
    var sysTextNot by remember { mutableStateOf(false) }
    var sysNewestFirst by remember { mutableStateOf(false) }

    var kernSeverity by remember { mutableStateOf("") }
    var kernSeverityNot by remember { mutableStateOf(false) }
    var kernText by remember { mutableStateOf("") }
    var kernTextNot by remember { mutableStateOf(false) }
    var kernNewestFirst by remember { mutableStateOf(false) }

    val syslogFiltered = remember(
        logs, sysFacility, sysFacilityNot, sysSeverity, sysSeverityNot,
        sysTag, sysText, sysTextNot, sysNewestFirst
    ) {
        val list = logs?.syslog.orEmpty().filter { e ->
            val line = "${e.time} ${e.facility}.${e.severity} ${e.tag}: ${e.msg}"
            val facOk = sysFacility == "any" || e.facility == sysFacility
            val sevOk = sysSeverity == "any" || e.severity == sysSeverity
            val txtOk = sysText.isBlank() || line.contains(sysText, ignoreCase = true)
            val fac = if (sysFacilityNot) !facOk else facOk
            val sev = if (sysSeverityNot) !sevOk else sevOk
            val txt = if (sysTextNot) !txtOk else txtOk
            fac && sev && txt
        }
        if (sysNewestFirst) list.asReversed() else list
    }

    val kernelFiltered = remember(
        logs, kernSeverity, kernSeverityNot, kernText, kernTextNot, kernNewestFirst
    ) {
        val list = logs?.kernel.orEmpty().filter { line ->
            val sevOk = when {
                kernSeverity.isBlank() -> true
                line.severity == null -> true // 续行始终保留（LuCI 同款）
                else -> line.severity >= (kernSeverity.toIntOrNull() ?: 0)
            }
            val sev = if (kernSeverityNot) !sevOk else sevOk
            val txtOk = kernText.isBlank() || line.text.contains(kernText, ignoreCase = true)
            val txt = if (kernTextNot) !txtOk else txtOk
            sev && txt
        }
        if (kernNewestFirst) list.asReversed() else list
    }

    val syslogText = syslogFiltered.joinToString("\n") {
        "${it.time} ${it.facility}.${it.severity} ${it.tag}: ${it.msg}"
    }
    val kernelText = kernelFiltered.joinToString("\n") { it.text }
    val currentText = if (tab == "syslog") syslogText else kernelText

    val downloadLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null && currentText.isNotBlank()) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(currentText.toByteArray(Charsets.UTF_8))
                }
                android.widget.Toast.makeText(context, "已下载", android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.widget.Toast.makeText(context, "下载失败：${it.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    var selectState by remember { mutableStateOf<LogsSelectState?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 0.dp)
        ) {
            // 标题区（提示栈锚定返回键行底部）
            Column(
                modifier = Modifier.alertAnchor(alertAnchor)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppBackButton(onBack = onBack)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "日志",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    AppIconButton(
                        onClick = {
                            val name = if (tab == "syslog") "syslog.txt" else "kernel.txt"
                            downloadLauncher.launch(name)
                        },
                        enabled = !loading
                    ) {
                        Icon(
                            Icons.Filled.FileDownload,
                            contentDescription = "下载",
                            tint = colors.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    AppIconButton(
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
                    AppIconButton(onClick = { load() }, enabled = !loading) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "刷新",
                            tint = colors.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Text(
                    "系统日志与内核日志",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
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
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (tab == "syslog") {
                        LogsFilterCard {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LogFilterSelectRow(
                                    label = "设施",
                                    value = if (sysFacility == "any") "任意" else sysFacility,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    selectState = LogsSelectState(
                                        "选择设施",
                                        listOf("any" to "任意") + SYSLOG_FACILITIES.map { it to it },
                                        sysFacility
                                    ) { v -> sysFacility = v }
                                }
                                LogFilterSelectRow(
                                    label = "级别",
                                    value = if (sysSeverity == "any") "任意" else sysSeverity,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    selectState = LogsSelectState(
                                        "选择级别",
                                        listOf("any" to "任意") + SYSLOG_SEVERITIES.map { it to it },
                                        sysSeverity
                                    ) { v -> sysSeverity = v }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LogTextRow(
                                    value = sysTag,
                                    onValueChange = { sysTag = it },
                                    label = "标签",
                                    modifier = Modifier.weight(1f)
                                )
                                LogTextRow(
                                    value = sysText,
                                    onValueChange = { sysText = it },
                                    label = "文本",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            LogNotBox(
                                LogNotEntry("排除设施（命中的行不显示）", sysFacilityNot) { sysFacilityNot = it },
                                LogNotEntry("排除级别（命中的行不显示）", sysSeverityNot) { sysSeverityNot = it },
                                LogNotEntry("排除文本（命中的行不显示）", sysTextNot) { sysTextNot = it }
                            )
                            LogSwitchRow("最新在前", sysNewestFirst) { sysNewestFirst = it }
                        }
                    } else {
                        LogsFilterCard {
                            LogFilterSelectRow(
                                label = "级别",
                                value = if (kernSeverity.isBlank()) "默认" else kernSeverity
                            ) {
                                selectState = LogsSelectState(
                                    "选择级别",
                                    listOf(
                                        "" to "默认",
                                        "1" to "1 告警",
                                        "2" to "2 严重",
                                        "3" to "3 错误",
                                        "4" to "4 警告",
                                        "5" to "5 通知",
                                        "6" to "6 信息",
                                        "7" to "7 调试"
                                    ),
                                    kernSeverity
                                ) { v -> kernSeverity = v }
                            }
                            LogTextRow(
                                value = kernText,
                                onValueChange = { kernText = it },
                                label = "文本包含"
                            )
                            LogNotBox(
                                LogNotEntry("排除级别（命中的行不显示）", kernSeverityNot) { kernSeverityNot = it },
                                LogNotEntry("排除文本（命中的行不显示）", kernTextNot) { kernTextNot = it }
                            )
                            LogSwitchRow("最新在前", kernNewestFirst) { kernNewestFirst = it }
                        }
                    }

                    val text = if (tab == "syslog") syslogText else kernelText
                    val lines = text.lineSequence().toList()

                    // 日志框：标题行（行数 + 过滤开关）与内容同一卡片，节省纵向空间
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "共 ${lines.size} 行",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        if (lines.isEmpty()) {
                            Text(
                                "无匹配的日志条目。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        } else {
                            lines.forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.onSurface
                                )
                            }
                            // 卡片背景延伸到手势条区域，内容在其内滚动
                            Spacer(
                                Modifier.navigationBarsPadding().height(6.dp)
                            )
                        }
                    }
                }
            }
        }

        // 悬浮提示栈：锚定返回键行下方（间隙/首帧 gate 统一在 StackedAlerts 组件内）
        StackedAlertOverlay(alertStack, alertAnchor)

        selectState?.let { sel ->
            AppDialog(
                title = sel.title,
                confirmLabel = "关闭",
                dismissLabel = "",
                onConfirm = { selectState = null },
                onDismiss = { selectState = null }
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    sel.options.forEach { (value, label) ->
                        val isSelected = value == sel.selected
                        Text(
                            text = if (isSelected) "● $label" else label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) colors.primary else colors.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sel.onPick(value)
                                    selectState = null
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsFilterCard(content: @Composable () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

/** 排除（非）条目：标签 + 开关。 */
private class LogNotEntry(
    val label: String,
    val checked: Boolean,
    val onChange: (Boolean) -> Unit
)

/** 排除（非）子框：包在过滤大卡内，集中展示各字段的排除开关。 */
@Composable
private fun LogNotBox(vararg entries: LogNotEntry) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outline, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            "排除",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = colors.onSurfaceVariant
        )
        entries.forEach { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
                AppSwitch(checked = e.checked, onCheckedChange = e.onChange, modifier = Modifier.scale(0.6f))
            }
        }
    }
}

@Composable
private fun LogFilterSelectRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceVariant)
            .border(1.dp, colors.primary.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        // 明显的下拉指示：主色 ▾，让用户一眼看出可以点选
        Text("▾", style = MaterialTheme.typography.titleSmall, color = colors.primary)
    }
}

@Composable
private fun LogTextRow(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        modifier = modifier
    )
}

@Composable
private fun LogSwitchRow(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        AppSwitch(checked = checked, onCheckedChange = onChanged, modifier = Modifier.scale(0.75f))
    }
}
