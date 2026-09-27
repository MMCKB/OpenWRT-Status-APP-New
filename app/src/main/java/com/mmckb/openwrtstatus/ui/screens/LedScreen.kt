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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
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

/** 单选弹窗状态。 */
private data class LedSelectState(
    val title: String,
    val options: List<Pair<String, String>>,
    val selected: String,
    val onPick: (String) -> Unit
)

/** 触发模式多选弹窗状态（点击即切换勾选）。 */
private data class LedMultiSelectState(
    val title: String,
    val options: List<Pair<String, String>>,
    val selected: List<String>,
    val onToggle: (String) -> Unit
)

/** 编辑/添加弹窗的表单状态（独立于卡片列表，确定后写回）。 */
private data class LedEditForm(
    val name: String,
    val sysfs: String,
    val trigger: String,
    val defaultState: String,
    val inverted: Boolean,
    val interval: String,
    val delayon: String,
    val delayoff: String,
    val dev: String,
    val mode: List<String>
)

/**
 * LED 配置页（工具页入口，LuCI admin/system/leds 的完整复刻）：
 * 卡片仅展示名称、触发器与 LED 名称，编辑与添加在弹窗中完成；
 * 保存并应用后 /etc/init.d/led 重载生效。
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
    val density = LocalDensity.current
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

    // 编辑/添加弹窗：editingIndex = null 表示添加，否则为待编辑的列表下标
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var editForm by remember { mutableStateOf<LedEditForm?>(null) }

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

    fun openEdit(index: Int?) {
        val act = index?.let { actions.getOrNull(it) }
        editIndex = index
        editForm = LedEditForm(
            name = act?.name ?: "",
            sysfs = act?.sysfs ?: (ledDevices.firstOrNull()?.name ?: ""),
            trigger = act?.trigger ?: "none",
            defaultState = act?.defaultState ?: "0",
            inverted = act?.inverted ?: false,
            interval = act?.interval ?: "",
            delayon = act?.delayon ?: "",
            delayoff = act?.delayoff ?: "",
            dev = act?.dev ?: "",
            mode = act?.mode ?: emptyList()
        )
    }

    fun commitEdit() {
        val form = editForm ?: return
        val updated = LedAction(
            section = editIndex?.let { actions.getOrNull(it)?.section } ?: "",
            name = form.name.trim(),
            sysfs = form.sysfs,
            trigger = form.trigger,
            defaultState = form.defaultState,
            inverted = form.inverted,
            interval = form.interval.trim(),
            delayon = form.delayon.trim(),
            delayoff = form.delayoff.trim(),
            dev = form.dev,
            mode = form.mode
        )
        editIndex?.let { idx ->
            if (idx >= 0 && idx < actions.size) {
                actions = actions.mapIndexed { i, a -> if (i == idx) updated else a }
            }
        } ?: run {
            actions = actions + updated
        }
        editForm = null
        editIndex = null
    }

    fun requestDelete(index: Int) {
        confirmDeleteIndex = index
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
                color = colors.onSurfaceVariant,
                modifier = Modifier.onGloballyPositioned { coords ->
                    // 提示栈锚定在标题区正下方
                    alertTopPadding = with(density) {
                        (coords.positionInParent().y + coords.size.height).toDp() + 8.dp
                    }
                }
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (actions.isEmpty()) {
                        Text(
                            "尚无 LED 动作。点击下方「添加 LED 动作」创建。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    actions.forEachIndexed { index, act ->
                        LedSummaryCard(
                            act = act,
                            onEdit = { openEdit(index) },
                            onDelete = { requestDelete(index) }
                        )
                    }

                    OutlinedButton(
                        onClick = { openEdit(null) },
                        enabled = !busy && ledDevices.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("添加 LED 动作")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { save() },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (busy) "正在应用…" else "保存并应用") }
                        OutlinedButton(
                            onClick = { load() },
                            enabled = !busy
                        ) { Text("重置") }
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

        // 单选弹窗（LED / 触发器 / 设备，可从编辑弹窗中叠出）
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

        // 触发模式多选弹窗（点击即勾选/取消）
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

        // 编辑 / 添加弹窗
        editForm?.let { form ->
            AppDialog(
                title = if (editIndex != null) "编辑 LED 动作" else "添加 LED 动作",
                confirmLabel = "确定",
                confirmEnabled = form.sysfs.isNotBlank(),
                onConfirm = { commitEdit() },
                onDismiss = { editForm = null; editIndex = null }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppTextField(
                        value = form.name,
                        onValueChange = { editForm = form.copy(name = it) },
                        singleLine = true,
                        label = { Text("名称") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    LedPickRow(
                        label = "LED 名称",
                        value = form.sysfs.ifBlank { "请选择" }
                    ) {
                        selectState = LedSelectState(
                            "选择 LED",
                            ledDevices.map { it.name to it.name },
                            form.sysfs
                        ) { v -> editForm = form.copy(sysfs = v) }
                    }
                    LedPickRow(
                        label = "触发器",
                        value = LedClient.TRIGGERS.firstOrNull { it.first == form.trigger }?.second
                            ?: form.trigger
                    ) {
                        selectState = LedSelectState(
                            "选择触发器", LedClient.TRIGGERS, form.trigger
                        ) { v -> editForm = form.copy(trigger = v) }
                    }
                    Text(
                        LedClient.TRIGGER_DESCRIPTIONS[form.trigger] ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    when (form.trigger) {
                        "none" -> Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.outline, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "默认状态（亮）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            AppSwitch(
                                checked = form.defaultState == "1",
                                onCheckedChange = { v -> editForm = form.copy(defaultState = if (v) "1" else "0") },
                                modifier = Modifier.scale(0.75f)
                            )
                        }
                        "heartbeat" -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                                    .background(colors.surface)
                                    .border(1.dp, colors.outline, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "反转闪烁",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                AppSwitch(
                                    checked = form.inverted,
                                    onCheckedChange = { v -> editForm = form.copy(inverted = v) },
                                    modifier = Modifier.scale(0.75f)
                                )
                            }
                            AppTextField(
                                value = form.interval,
                                onValueChange = { editForm = form.copy(interval = it.filter { c -> c.isDigit() }.take(6)) },
                                singleLine = true,
                                label = { Text("间隔（毫秒）") },
                                placeholder = { Text("50") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        "timer" -> {
                            AppTextField(
                                value = form.delayon,
                                onValueChange = { editForm = form.copy(delayon = it.filter { c -> c.isDigit() }.take(9)) },
                                singleLine = true,
                                label = { Text("亮灯延迟（毫秒）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            AppTextField(
                                value = form.delayoff,
                                onValueChange = { editForm = form.copy(delayoff = it.filter { c -> c.isDigit() }.take(9)) },
                                singleLine = true,
                                label = { Text("灭灯延迟（毫秒）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        "netdev" -> {
                            LedPickRow(
                                label = "设备",
                                value = form.dev.ifBlank { "请选择" }
                            ) {
                                selectState = LedSelectState(
                                    "选择设备",
                                    netdevDevices.map { it to it },
                                    form.dev
                                ) { v -> editForm = form.copy(dev = v) }
                            }
                            LedPickRow(
                                label = "触发模式",
                                value = if (form.mode.isEmpty()) "未选择"
                                else form.mode.mapNotNull { m ->
                                    LedClient.NETDEV_MODES.firstOrNull { it.first == m }?.second ?: m
                                }.joinToString("、")
                            ) {
                                multiSelectState = LedMultiSelectState(
                                    "选择触发模式", LedClient.NETDEV_MODES, form.mode
                                ) { m ->
                                    val modes = form.mode.toMutableList()
                                    if (m in modes) modes.remove(m) else modes.add(m)
                                    editForm = form.copy(mode = modes)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 删除确认
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

/** 摘要卡片：仅展示名称、触发器与 LED 名称，附编辑/删除。 */
@Composable
private fun LedSummaryCard(
    act: LedAction,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            act.name.ifBlank { act.sysfs },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        LedInfoRow("LED 名称", act.sysfs)
        LedInfoRow(
            "触发器",
            LedClient.TRIGGERS.firstOrNull { it.first == act.trigger }?.second ?: act.trigger
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onEdit,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 4.dp
                )
            ) { Text("编辑") }
            OutlinedButton(
                onClick = onDelete,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 4.dp
                )
            ) { Text("删除", color = colors.error) }
        }
    }
}

@Composable
private fun LedInfoRow(label: String, value: String) {
    val colors = LocalAppColors.current
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(76.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/** 弹窗表单内的选择行（LED / 触发器 / 设备 / 触发模式）。 */
@Composable
private fun LedPickRow(label: String, value: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text("›", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
    }
}
