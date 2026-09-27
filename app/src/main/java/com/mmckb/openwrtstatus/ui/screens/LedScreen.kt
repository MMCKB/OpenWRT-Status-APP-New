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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.remote.LedAction
import com.mmckb.openwrtstatus.data.remote.LedClient
import com.mmckb.openwrtstatus.data.remote.LedDevice
import com.mmckb.openwrtstatus.data.remote.RouterException
import com.mmckb.openwrtstatus.ui.components.AppAlertType
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.components.AppSwitch
import com.mmckb.openwrtstatus.ui.components.AppTextField
import com.mmckb.openwrtstatus.ui.components.ConnectionMonitor
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

/** 应用类操作（uci apply + 确认窗口）的总时长上限。 */
private const val LED_APPLY_TIMEOUT_MS = 100_000L
/** 读取类操作的总时长上限。 */
private const val LED_LOAD_TIMEOUT_MS = 30_000L

/** 统一的失败文案：超时（连接中断/无响应）给出可操作的提示，其余透出原始信息。 */
private fun ledErrText(e: Exception): String = when {
    e is TimeoutCancellationException -> "路由器连接中断或长时间无响应，请检查网络后重试。"
    e is CancellationException -> "连接已断开，操作已终止。"
    else -> e.message ?: "请稍后重试。"
}

private data class LedSelectState(
    val title: String,
    val options: List<Pair<String, String>>,
    val selected: String,
    val onPick: (String) -> Unit
)

/** netdev 触发模式的多选弹窗状态（点击即切换勾选）。 */
private data class LedMultiSelectState(
    val title: String,
    val options: List<Pair<String, String>>,
    val selected: List<String>,
    val onToggle: (String) -> Unit
)

/**
 * LED 配置页（工具页入口，LuCI admin/system/leds 的完整复刻）：
 * 以卡片管理 /etc/config/system 的 led 段——名称、LED（sysfs）、触发器
 * （常灭/常亮/自定义闪烁/心跳/网络设备活动）及其专属选项，支持添加/删除，
 * 保存并应用后 system 服务重载生效。
 */
@Composable
fun LedScreen(
    config: RouterConfig,
    sshEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val client = remember { LedClient() }
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
    val density = androidx.compose.ui.platform.LocalDensity.current
    var alertTopPadding by remember { mutableStateOf(0.dp) }

    fun setAlert(type: AppAlertType, title: String, description: String? = null) {
        alertStack.push(type, title, description)
    }

    /** 点击保存/应用前的快速检查：已断开则直接红色提示，不再发起操作。 */
    fun ensureConnected(): Boolean {
        if (ConnectionMonitor.status.value == ConnectionMonitor.Status.Offline) {
            setAlert(AppAlertType.Error, "与路由器已断开连接", "请检查手机与路由器的网络后重试。")
            return false
        }
        return true
    }

    // 跟踪进行中的操作；路由器断开连接时立即取消，按钮即刻恢复并弹红色提示
    var opJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) {
        ConnectionMonitor.status.collect { status ->
            if (status == ConnectionMonitor.Status.Offline) opJob?.cancel()
        }
    }

    var actions by remember { mutableStateOf<List<LedAction>>(emptyList()) }
    var deletedSections by remember { mutableStateOf<List<String>>(emptyList()) }
    var ledDevices by remember { mutableStateOf<List<LedDevice>>(emptyList()) }
    var netdevDevices by remember { mutableStateOf<List<String>>(emptyList()) }

    var selectState by remember { mutableStateOf<LedSelectState?>(null) }
    var multiSelectState by remember { mutableStateOf<LedMultiSelectState?>(null) }
    var confirmDeleteIndex by remember { mutableStateOf<Int?>(null) }

    fun load() {
        scope.launch {
            loading = true
            var hadError: String? = null
            try {
                val (acts, devices) = withTimeout(LED_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        client.loadLedActions(config) to client.loadLeds(config)
                    }
                }
                actions = acts
                deletedSections = emptyList()
                ledDevices = devices
                netdevDevices = withTimeout(LED_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.listNetdevDevices(config) }
                }
            } catch (e: Exception) {
                hadError = ledErrText(e)
            }
            if (hadError != null) setAlert(AppAlertType.Error, "LED 配置读取失败", hadError)
            loading = false
        }
    }

    var loadedOnce by remember { mutableStateOf(false) }
    if (!loadedOnce) {
        loadedOnce = true
        load()
    }

    fun save() {
        if (!ensureConnected()) return
        scope.launch {
            busy = true
            try {
                withTimeout(LED_APPLY_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        client.applyLeds(
                            config, actions, deletedSections.toList(), sshOrNull
                        ) { phase -> setAlert(AppAlertType.Info, phase) }
                    }
                }
                setAlert(AppAlertType.Success, "已保存并应用")
                load()
            } catch (e: RouterException) {
                setAlert(
                    AppAlertType.Error, "保存失败",
                    if (e.ubusCode == 6) "本固件限制了无 SSH 的配置修改，请先在设备编辑页开启 SSH。"
                    else ledErrText(e)
                )
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "保存失败", ledErrText(e))
            } finally {
                busy = false
            }
        }
    }

    fun updateAction(index: Int, transform: (LedAction) -> LedAction) {
        actions = actions.mapIndexed { i, a -> if (i == index) transform(a) else a }
    }

    fun toggleMode(index: Int, mode: String) {
        updateAction(index) { act ->
            val modes = act.mode.toMutableList()
            if (mode in modes) modes.remove(mode) else modes.add(mode)
            act.copy(mode = modes)
        }
    }

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
            }
            Text(
                "LED 配置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Text(
                "自定义设备 LED 的触发行为",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
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
                        .verticalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (actions.isEmpty()) {
                        AdmDescText("尚无 LED 动作。点击下方「添加 LED 动作」创建。")
                    }
                    actions.forEachIndexed { index, act ->
                        LedActionCard(
                            act = act,
                            ledNames = ledDevices.map { it.name },
                            netdevDevices = netdevDevices,
                            busy = busy,
                            onSelect = { title, options, current, onPick ->
                                selectState = LedSelectState(title, options, current, onPick)
                            },
                            onSelectModes = { current, onToggle ->
                                multiSelectState = LedMultiSelectState(
                                    "选择触发模式", LedClient.NETDEV_MODES, current, onToggle
                                )
                            },
                            onToggleMode = { m -> toggleMode(index, m) },
                            onDelete = { confirmDeleteIndex = index },
                            onChange = { transform -> updateAction(index, transform) }
                        )
                        if (index != actions.lastIndex) {
                            HorizontalDivider(color = colors.outline, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            actions = actions + LedAction(
                                section = "",
                                name = "",
                                sysfs = ledDevices.firstOrNull()?.name ?: "",
                                trigger = "none",
                                defaultState = "0",
                                inverted = false,
                                interval = "",
                                delayon = "",
                                delayoff = "",
                                dev = "",
                                mode = emptyList()
                            )
                        },
                        enabled = !busy && ledDevices.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("添加 LED 动作")
                    }
                    Button(
                        onClick = { save() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "正在应用…" else "保存并应用") }
                    OutlinedButton(
                        onClick = { load() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("重置") }
                    Spacer(Modifier.height(4.dp))
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

        // 单选对话框（LED / 触发器 / 设备）
        selectState?.let { sel ->
            AppDialog(
                title = sel.title,
                confirmLabel = "关闭",
                dismissLabel = "",
                onConfirm = { selectState = null },
                onDismiss = { selectState = null }
            ) {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
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

        // 触发模式多选对话框（点击即勾选/取消）
        multiSelectState?.let { sel ->
            AppDialog(
                title = sel.title,
                confirmLabel = "完成",
                dismissLabel = "",
                onConfirm = { multiSelectState = null },
                onDismiss = { multiSelectState = null }
            ) {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                ) {
                    sel.options.forEach { (value, label) ->
                        val isSelected = value in sel.selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { sel.onToggle(value) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isSelected) "☑ $label" else "☐ $label",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) colors.primary else colors.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 删除 LED 动作确认
        confirmDeleteIndex?.let { index ->
            AppDialog(
                title = "删除 LED 动作",
                message = "确定删除该 LED 动作？删除后需「保存并应用」才会生效。",
                confirmLabel = "删除",
                confirmColor = colors.error,
                onConfirm = {
                    val act = actions.getOrNull(index)
                    if (act != null) {
                        if (act.section.isNotBlank()) {
                            deletedSections = deletedSections + act.section
                        }
                        actions = actions.filterIndexed { i, _ -> i != index }
                    }
                    confirmDeleteIndex = null
                },
                onDismiss = { confirmDeleteIndex = null }
            )
        }
    }
}

@Composable
private fun AdmDescText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalAppColors.current.onSurfaceVariant
    )
}

/** 单个 LED 动作卡片：选项与 LuCI leds.js + led-trigger 插件一一对应。 */
@Composable
private fun LedActionCard(
    act: LedAction,
    ledNames: List<String>,
    netdevDevices: List<String>,
    busy: Boolean,
    onSelect: (title: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) -> Unit,
    onSelectModes: (current: List<String>, onToggle: (String) -> Unit) -> Unit,
    onToggleMode: (String) -> Unit,
    onDelete: () -> Unit,
    onChange: ((LedAction) -> LedAction) -> Unit
) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (act.section.isBlank()) "新 LED 动作"
                else act.name.ifBlank { "LED 动作 ${act.section}" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                "删除",
                style = MaterialTheme.typography.labelLarge,
                color = colors.error,
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy, onClick = onDelete)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        AppTextField(
            value = act.name,
            onValueChange = { v -> onChange { it.copy(name = v) } },
            singleLine = true,
            label = { Text("名称") },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        val sysfsLabel = act.sysfs.ifBlank { "请选择" }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .clickable(enabled = !busy) {
                    onSelect(
                        "选择 LED",
                        ledNames.map { it to it },
                        act.sysfs
                    ) { v -> onChange { it.copy(sysfs = v) } }
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("LED 名称", style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
            Text(sysfsLabel, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("›", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .clickable(enabled = !busy) {
                    onSelect(
                        "选择触发器",
                        LedClient.TRIGGERS,
                        act.trigger
                    ) { v -> onChange { it.copy(trigger = v) } }
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("触发器", style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
            Text(
                LedClient.TRIGGERS.firstOrNull { it.first == act.trigger }?.second ?: act.trigger,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text("›", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        }
        Text(
            LedClient.TRIGGER_DESCRIPTIONS[act.trigger] ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        when (act.trigger) {
            "none" -> LedSettingRow(
                label = "默认状态",
                description = "触发器为「常灭」时 LED 的默认亮灭",
                checked = act.defaultState == "1",
                enabled = !busy
            ) { v -> onChange { it.copy(defaultState = if (v) "1" else "0") } }
            "heartbeat" -> {
                LedSettingRow(
                    label = "反转闪烁",
                    description = "常亮常灭反转，随系统活动闪烁",
                    checked = act.inverted,
                    enabled = !busy
                ) { v -> onChange { it.copy(inverted = v) } }
                AppTextField(
                    value = act.interval,
                    onValueChange = { v -> onChange { it.copy(interval = v.filter { c -> c.isDigit() }.take(6)) } },
                    singleLine = true,
                    label = { Text("间隔（毫秒）") },
                    placeholder = { Text("50") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            "timer" -> {
                AppTextField(
                    value = act.delayon,
                    onValueChange = { v -> onChange { it.copy(delayon = v.filter { c -> c.isDigit() }.take(9)) } },
                    singleLine = true,
                    label = { Text("亮灯延迟（毫秒）") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                AppTextField(
                    value = act.delayoff,
                    onValueChange = { v -> onChange { it.copy(delayoff = v.filter { c -> c.isDigit() }.take(9)) } },
                    singleLine = true,
                    label = { Text("灭灯延迟（毫秒）") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            "netdev" -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.outline, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .clickable(enabled = !busy) {
                            onSelect(
                                "选择设备",
                                netdevDevices.map { it to it },
                                act.dev
                            ) { v -> onChange { it.copy(dev = v) } }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("设备", style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
                    Text(act.dev.ifBlank { "请选择" }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("›", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.outline, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .clickable(enabled = !busy) { onSelectModes(act.mode, onToggleMode) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("触发模式", style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
                    Text(
                        if (act.mode.isEmpty()) "未选择"
                        else act.mode.mapNotNull { m -> LedClient.NETDEV_MODES.firstOrNull { it.first == m }?.second ?: m }
                            .joinToString("、"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("›", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}
