package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.mmckb.openwrtstatus.ui.components.AppShapes
import com.mmckb.openwrtstatus.ui.components.CardSectionTitle
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Width of the action strip (edit + delete) revealed by swiping a device card left. */
private val REVEAL_WIDTH = 128.dp

/**
 * Device manager: every router renders as its own card; swipe a card left to reveal
 * edit / delete actions (delete asks for confirmation). Tapping a card switches the
 * active device. The add/edit form carries the full connection setup (ubus endpoint
 * and SSH credentials merged into one section) that used to live on the settings page.
 */
@Composable
fun DevicesScreen(
    viewModel: RouterViewModel,
    modifier: Modifier = Modifier
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RouterConfig?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var openCardId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<RouterConfig?>(null) }

    val currentEditing = editing
    if (currentEditing != null) {
        DeviceEditForm(
            initial = currentEditing,
            isNew = isNew,
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

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        items(devices, key = { it.id }) { device ->
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
                modifier = Modifier.fillMaxWidth()
            ) { Text("添加设备") }
        }
        item {
            Text(
                "点击卡片切换当前设备，向左滑动卡片可编辑或删除。概览、监控与终端都作用于当前设备。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除设备") },
            text = {
                Text("确定要删除「${target.displayName}」吗？删除后需要重新添加才能连接该路由器。")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDevice(target.id)
                    openCardId = null
                    pendingDelete = null
                }) {
                    Text("删除", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/** One device card with edit/delete actions revealed by a left swipe. */
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
    val offset = remember(device.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Close again whenever the parent decides this card must not stay open.
    LaunchedEffect(revealed) {
        if (!revealed && offset.value != 0f) offset.animateTo(0f)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .matchParentSize()
                .clip(AppShapes.card)
        ) {
            SwipeAction(
                label = "编辑",
                icon = Icons.Filled.Edit,
                background = colors.primary,
                modifier = Modifier.weight(1f)
            ) { onEdit() }
            SwipeAction(
                label = "删除",
                icon = Icons.Filled.Delete,
                background = colors.error,
                modifier = Modifier.weight(1f)
            ) { onDelete() }
        }
        AppCard(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(revealPx) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offset.snapTo((offset.value + dragAmount).coerceIn(-revealPx, 0f))
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                val open = offset.value < -revealPx / 2
                                offset.animateTo(if (open) -revealPx else 0f)
                                onRevealedChanged(open)
                            }
                        }
                    )
                }
                .clickable {
                    if (offset.value != 0f) {
                        scope.launch { offset.animateTo(0f) }
                        onRevealedChanged(false)
                    } else {
                        onSelect()
                    }
                }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(if (active) colors.success else colors.onSurfaceVariant, CircleShape)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        device.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Text(
                        "${device.ip}:${device.port} · ${device.username}" + if (active) " · 当前" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SwipeAction(
    label: String,
    icon: ImageVector,
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
 * Add/edit form carrying the full device configuration: ubus endpoint and SSH
 * credentials merged into a single section.
 */
@Composable
private fun DeviceEditForm(
    initial: RouterConfig,
    isNew: Boolean,
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

    var sshEnabled by remember { mutableStateOf(initial.sshEnabled) }
    var sshHost by remember { mutableStateOf(initial.sshHost) }
    var sshPort by remember { mutableStateOf(initial.sshPort.toString()) }
    var sshUsername by remember { mutableStateOf(initial.sshUsername) }
    var sshPassword by remember { mutableStateOf(initial.sshPassword) }

    val colors = LocalAppColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
            CardSectionTitle("连接配置")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("设备名称（选填，留空显示地址）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
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

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = colors.outline)
            Spacer(Modifier.height(12.dp))

            Text(
                "SSH 远程终端与 DHCP 租约",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Spacer(Modifier.height(8.dp))
            SwitchRow("启用 SSH", sshEnabled) { sshEnabled = it }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = sshHost,
                onValueChange = { sshHost = it },
                label = { Text("SSH 主机（留空则使用路由器地址）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = sshEnabled
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = sshPort,
                onValueChange = { sshPort = it.filter { c -> c.isDigit() } },
                label = { Text("SSH 端口") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = sshEnabled
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = sshUsername,
                onValueChange = { sshUsername = it },
                label = { Text("SSH 用户名") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = sshEnabled
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = sshPassword,
                onValueChange = { sshPassword = it },
                label = { Text("SSH 密码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = sshEnabled
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "连接地址为 http(s)://地址:端口/ubus（rpcd 接口）。启用 SSH 后可在「终端」页执行命令，并在「监控」页读取租约。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) { Text("取消") }
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            ip = ip.ifBlank { "192.168.1.1" },
                            port = port.toIntOrNull()?.coerceIn(1, 65535) ?: 80,
                            username = username.ifBlank { "root" },
                            password = password,
                            useHttps = useHttps,
                            allowInsecureTls = allowInsecureTls,
                            sshEnabled = sshEnabled,
                            sshHost = sshHost,
                            sshPort = sshPort.toIntOrNull()?.coerceIn(1, 65535) ?: 22,
                            sshUsername = sshUsername.ifBlank { "root" },
                            sshPassword = sshPassword
                        )
                    )
                },
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

        Text(
            "需路由器已安装并启用 rpcd（OpenWrt 官方固件默认包含）。兼容 OpenWrt 21.02 / 22.03 / 23.05 / 24.10 / 25.12。",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 96.dp)
        )
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
