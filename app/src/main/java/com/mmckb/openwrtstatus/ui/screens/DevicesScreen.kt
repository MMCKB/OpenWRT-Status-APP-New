package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.ui.RouterViewModel
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.components.CardSectionTitle
import com.mmckb.openwrtstatus.ui.components.rememberTopBarPadding
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Width of the action strip (delete + edit) revealed by swiping a device card right. */
private val REVEAL_WIDTH = 128.dp

/**
 * Device manager: every router renders as its own card, the active one pinned on top.
 * Swipe a card LEFT to reveal edit / delete actions (delete asks for confirmation);
 * tap a card to make that router the active one.
 * The add/edit form carries the full connection setup; SSH is always enabled and only
 * asks for port and password (an empty SSH password falls back to the router password).
 */
@Composable
fun DevicesScreen(
    viewModel: RouterViewModel,
    onSecondaryPageChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RouterConfig?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var openCardId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<RouterConfig?>(null) }

    val currentEditing = editing

    // Tells the shell to hide the tab bar while the add/edit form is open.
    LaunchedEffect(currentEditing) {
        onSecondaryPageChanged(currentEditing != null)
    }
    if (currentEditing != null) {
        DeviceEditForm(
            initial = currentEditing,
            isNew = isNew,
            existing = devices.filterNot { it.id == currentEditing.id },
            onSave = { saved ->
                if (isNew) viewModel.addDevice(saved) else viewModel.updateDevice(saved)
                editing = null
            },
            onDelete = {
                viewModel.deleteDevice(currentEditing.id)
                editing = null
            },
            onCancel = { editing = null },
            modifier = modifier
        )
        return
    }

    val colors = LocalAppColors.current
    val sorted = remember(devices, activeId) {
        devices.sortedByDescending { it.id == activeId }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = rememberTopBarPadding(), bottom = 16.dp)
    ) {
        items(sorted, key = { it.id }) { device ->
            SwipeRevealDeviceCard(
                device = device,
                active = device.id == activeId,
                revealed = openCardId == device.id,
                onRevealedChanged = { openCardId = if (it) device.id else null },
                onSelect = { viewModel.selectDevice(device.id) },
                onEdit = {
                    editing = device
                    isNew = false
                },
                onDelete = { pendingDelete = device }
            )
        }
        item {
            Button(
                onClick = {
                    editing = RouterConfig()
                    isNew = true
                },
                shape = AppShapes.card,
                modifier = Modifier.fillMaxWidth()
            ) { Text("添加设备") }
        }
    }

    pendingDelete?.let { target ->
        AppDialog(
            title = "删除设备",
            message = "确定要删除「${target.displayName}」吗？删除后需要重新添加才能连接该路由器。",
            confirmLabel = "删除",
            confirmColor = colors.error,
            onConfirm = {
                viewModel.deleteDevice(target.id)
                openCardId = null
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** One device card with delete / edit actions revealed by a left swipe; tap switches device. */
@Composable
private fun SwipeRevealDeviceCard(
    device: RouterConfig,
    active: Boolean,
    revealed: Boolean,
    onRevealedChanged: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    val revealPx = with(density) { REVEAL_WIDTH.toPx() }
    val offset = remember(device.id) { mutableFloatStateOf(0f) }
    val settleJob = remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    // Close again whenever the parent decides this card must not stay open.
    LaunchedEffect(revealed) {
        if (!revealed && offset.value != 0f) {
            settleJob.value?.cancel()
            settleJob.value = scope.launch {
                animate(offset.value, 0f, animationSpec = spring(0.85f, 380f)) { v, _ ->
                    offset.value = v
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Fixed-width strip on the reveal side (right edge for a left swipe):
        // delete takes a narrow slice, edit gets the rest. The strip slides in from
        // beyond the right edge as the card moves away.
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(REVEAL_WIDTH)
                .clip(AppShapes.card)
                .graphicsLayer {
                    translationX = revealPx + offset.value
                }
        ) {
            SwipeAction(
                label = "删除",
                icon = Icons.Filled.Delete,
                background = colors.error,
                modifier = Modifier.width(32.dp)
            ) { onDelete() }
            SwipeAction(
                label = "编辑",
                icon = Icons.Filled.Edit,
                background = Color(0xFFFFC107),
                modifier = Modifier.weight(1f)
            ) { onEdit() }
        }
        Surface(
            shape = AppShapes.card,
            color = colors.surface,
            border = BorderStroke(1.dp, if (active) colors.primary else colors.outline),
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(device.id, revealPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { settleJob.value?.cancel() },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            offset.value = (offset.value - dragAmount).coerceIn(-revealPx, 0f)
                        },
                        onDragEnd = {
                            val open = offset.value < -revealPx / 2
                            settleJob.value = scope.launch {
                                animate(
                                    initialValue = offset.value,
                                    targetValue = if (open) -revealPx else 0f,
                                    animationSpec = spring(0.85f, 380f)
                                ) { v, _ -> offset.value = v }
                            }
                            onRevealedChanged(open)
                        }
                    )
                }
                .clickable { onSelect() }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(colors.surfaceVariant, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Router,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            device.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (active) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = AppShapes.pill,
                                color = colors.primary,
                                contentColor = colors.onPrimary
                            ) {
                                Text(
                                    "当前设备",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${device.ip}:${device.port} · ${device.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** One rounded action button inside the revealed strip. */
@Composable
private fun SwipeAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = Color.White)
            Spacer(Modifier.height(2.dp))
            Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}

/**
 * Add/edit form carrying the full device configuration. SSH is always enabled and
 * inline with the router connection fields - only port and password are asked for
 * (an empty SSH password falls back to the router password). Device names must be
 * unique across the list.
 */
@Composable
private fun DeviceEditForm(
    initial: RouterConfig,
    isNew: Boolean,
    existing: List<RouterConfig>,
    onSave: (RouterConfig) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf(initial.name) }
    var ip by remember { mutableStateOf(initial.ip) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var useHttps by remember { mutableStateOf(initial.useHttps) }
    var allowInsecureTls by remember { mutableStateOf(initial.allowInsecureTls) }
    var sshPort by remember { mutableStateOf(initial.sshPort.toString()) }
    var sshPassword by remember { mutableStateOf(initial.sshPassword) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var backProgress by remember { mutableFloatStateOf(0f) }

    val colors = LocalAppColors.current

    // Predictive back: the form tracks the gesture (shrinks/fades) and returns to the
    // list when the gesture commits; nothing happens if the gesture is cancelled.
    androidx.activity.compose.PredictiveBackHandler { events ->
        try {
            events.collect { backProgress = it.progress }
            onCancel()
        } catch (_: kotlinx.coroutines.CancellationException) {
        } finally {
            backProgress = 0f
        }
    }

    Column(
        modifier = modifier
            .graphicsLayer {
                translationX = backProgress * size.width * 0.5f
                scaleX = 1f - 0.12f * backProgress
                scaleY = 1f - 0.12f * backProgress
            }
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("返回") }
            Text(
                if (isNew) "添加设备" else "编辑设备",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
        }

        AppCard {
            CardSectionTitle("路由器配置")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameError = null
                },
                label = { Text("设备名称（选填，留空显示地址）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = nameError != null
            )
            if (nameError != null) {
                Spacer(Modifier.height(4.dp))
                Text(nameError!!, style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("路由器地址（IP 或域名）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() } },
                label = { Text("端口（LuCI 管理页面端口）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(Modifier.height(12.dp))
            SwitchRow("使用 HTTPS", useHttps) { useHttps = it }
            SwitchRow("忽略证书校验（自签名证书）", allowInsecureTls) { allowInsecureTls = it }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = sshPort,
                onValueChange = { sshPort = it.filter { c -> c.isDigit() } },
                label = { Text("SSH 端口") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = sshPassword,
                onValueChange = { sshPassword = it },
                label = { Text("SSH 密码（留空则使用路由器密码）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onCancel,
                shape = AppShapes.card,
                modifier = Modifier.weight(1f)
            ) { Text("取消") }
            Button(
                onClick = {
                    val candidate = name.trim()
                    val effective = candidate.ifBlank { ip.ifBlank { "192.168.1.1" } }
                    if (existing.any { it.displayName == effective }) {
                        nameError = "名称与其他设备重复"
                        return@Button
                    }
                    onSave(
                        initial.copy(
                            name = candidate,
                            ip = ip.ifBlank { "192.168.1.1" },
                            port = port.toIntOrNull()?.coerceIn(1, 65535) ?: 80,
                            username = username.ifBlank { "root" },
                            password = password,
                            useHttps = useHttps,
                            allowInsecureTls = allowInsecureTls,
                            sshEnabled = true,
                            sshHost = "",
                            sshPort = sshPort.toIntOrNull()?.coerceIn(1, 65535) ?: 22,
                            sshUsername = username.ifBlank { "root" },
                            sshPassword = sshPassword.ifBlank { password }
                        )
                    )
                },
                shape = AppShapes.card,
                modifier = Modifier.weight(1f)
            ) { Text("保存") }
        }

        if (!isNew) {
            TextButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("删除该设备", color = colors.error)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}
