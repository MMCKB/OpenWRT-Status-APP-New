package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Width of the action strip (delete + edit) revealed by swiping a device card right. */
private val REVEAL_WIDTH = 128.dp

/**
 * Device manager: every router renders as its own card, the active one pinned on top.
 * Swipe a card RIGHT to reveal delete / edit actions (delete asks for confirmation).
 * Cards are not clickable - switching happens from the dashboard device picker.
 * The add/edit form carries the full connection setup; SSH is always enabled and only
 * asks for port and password (host/username follow the router connection).
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
    val sorted = remember(devices, activeId) {
        devices.sortedByDescending { it.id == activeId }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        items(sorted, key = { it.id }) { device ->
            SwipeRevealDeviceCard(
                device = device,
                active = device.id == activeId,
                revealed = openCardId == device.id,
                onRevealedChanged = { openCardId = if (it) device.id else null },
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

/** One device card with delete / edit actions revealed by a right swipe. */
@Composable
private fun SwipeRevealDeviceCard(
    device: RouterConfig,
    active: Boolean,
    revealed: Boolean,
    onRevealedChanged: (Boolean) -> Unit,
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
                label = "删除",
                icon = Icons.Filled.Delete,
                background = colors.error,
                modifier = Modifier.weight(1f)
            ) { onDelete() }
            SwipeAction(
                label = "编辑",
                icon = Icons.Filled.Edit,
                background = colors.primary,
                modifier = Modifier.weight(1f)
            ) { onEdit() }
        }
        Surface(
            shape = AppShapes.card,
            color = colors.surface,
            border = BorderStroke(1.dp, colors.primary),
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(revealPx) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offset.snapTo((offset.value + dragAmount).coerceIn(0f, revealPx))
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                val open = offset.value > revealPx / 2
                                offset.animateTo(if (open) revealPx else 0f)
                                onRevealedChanged(open)
                            }
                        }
                    )
                }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(colors.surfaceVariant, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Router,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp)
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
                    Spacer(Modifier.height(2.dp))
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
 * inline with the router connection fields - only port and password are asked for.
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
    var sshPort by remember { mutableStateOf(initial.sshPort.toString()) }
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
            CardSectionTitle("路由器配置")
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
                label = { Text("SSH 密码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "连接地址为 http(s)://地址:端口/ubus（rpcd 接口）；SSH 登录使用上方的用户名，主机与路由器地址相同。",
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
                            sshEnabled = true,
                            sshHost = "",
                            sshPort = sshPort.toIntOrNull()?.coerceIn(1, 65535) ?: 22,
                            sshUsername = username.ifBlank { "root" },
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
