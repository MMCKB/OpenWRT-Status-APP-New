package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.animation.core.animate
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.ui.RouterViewModel
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.components.CardSectionTitle
import com.mmckb.openwrtstatus.ui.components.PredictiveBackEasing
import com.mmckb.openwrtstatus.ui.components.rememberTopBarPadding
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

/**
 * Device manager, in its original form: one "设备列表" card with a row per router
 * (tap the row to switch the active device; inline edit / delete icons on each row).
 * The add/edit form overlays the list: during a predictive back gesture it follows the
 * Material spec (scale to 90%, fade out by the 35% threshold) while the list fades in.
 * SSH is always enabled and only asks for port and password (an empty SSH password
 * falls back to the router password). Device names must be unique across the list.
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
    var pendingDelete by remember { mutableStateOf<RouterConfig?>(null) }
    var formBackProgress by remember { mutableFloatStateOf(0f) }

    // Tells the shell to hide the tab bar while the add/edit form is open.
    LaunchedEffect(editing) {
        onSecondaryPageChanged(editing != null)
    }

    val colors = LocalAppColors.current
    val currentEditing = editing
    val formEased = PredictiveBackEasing.transform(formBackProgress)

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
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1f - 0.1f * formEased
                    scaleY = 1f - 0.1f * formEased
                    alpha = (1f - formEased / 0.35f).coerceIn(0f, 1f)
                }
        )
        androidx.activity.compose.PredictiveBackHandler {
            try {
                it.collect { event -> formBackProgress = event.progress }
                editing = null
            } catch (_: kotlinx.coroutines.CancellationException) {
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = rememberTopBarPadding(), bottom = 96.dp)
    ) {
        item {
            AppCard {
                CardSectionTitle("设备列表（${devices.size}）")
                Spacer(Modifier.height(8.dp))
                if (devices.isEmpty()) {
                    Text(
                        "还没有设备。点击下方「添加设备」，填入路由器地址与账号即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                devices.forEach { device ->
                    DeviceRow(
                        device = device,
                        active = device.id == activeId,
                        onClick = { viewModel.selectDevice(device.id) },
                        onEdit = {
                            editing = device
                            isNew = false
                        },
                        onDelete = { pendingDelete = device }
                    )
                }
            }
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
        item {
            Text(
                "点击设备即可切换当前连接；概览、监控与终端都作用于当前设备。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
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
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun DeviceRow(
    device: RouterConfig,
    active: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
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
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = colors.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = colors.onSurfaceVariant)
        }
    }
}

/**
 * Add/edit form carrying the full device configuration. SSH is always enabled and
 * inline with the router connection fields - only port and password are asked for
 * (an empty SSH password falls back to the router password). Device names must be
 * unique across the list. Scale/fade during predictive back are driven by the caller.
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

    val colors = LocalAppColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
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
