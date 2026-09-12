package com.mmckb.openwrtstatus.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.ui.RouterViewModel
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.CardSectionTitle
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

/**
 * Device manager: lists every configured router, switches the active device with a tap
 * and offers add / edit / delete. The edit form carries the full connection setup
 * (ubus endpoint, poll interval, demo mode and SSH credentials) that used to live on
 * the settings page; the settings tab now only keeps the about section.
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

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        item {
            AppCard {
                CardSectionTitle("设备列表（${devices.size}）")
                Spacer(Modifier.height(8.dp))
                if (devices.isEmpty()) {
                    Text(
                        "还没有设备。点击下方「添加设备」，填入路由器地址与账号即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalAppColors.current.onSurfaceVariant
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
                        onDelete = { viewModel.deleteDevice(device.id) }
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
                modifier = Modifier.fillMaxWidth()
            ) { Text("添加设备") }
        }
        item {
            Text(
                "点击设备即可切换当前连接；概览、监控与终端都作用于当前设备。",
                style = MaterialTheme.typography.bodySmall,
                color = LocalAppColors.current.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
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
 * Add/edit form carrying the full device configuration (formerly the settings page).
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
    var useMock by remember { mutableStateOf(initial.useMock) }
    var refreshInterval by remember { mutableStateOf(initial.refreshIntervalSec.toString()) }

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
            CardSectionTitle("路由器连接")
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
            SwitchRow("演示模式（使用模拟数据）", useMock) { useMock = it }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = refreshInterval,
                onValueChange = { refreshInterval = it.filter { c -> c.isDigit() } },
                label = { Text("刷新间隔（秒，2-60）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "连接地址为 http(s)://地址:端口/ubus（rpcd 接口）。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }

        AppCard {
            CardSectionTitle("SSH 远程终端")
            Spacer(Modifier.height(12.dp))
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
                "启用后可在「终端」页执行命令，并在「监控」页读取 /tmp/dhcp.leases 租约。",
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
                            useMock = useMock,
                            refreshIntervalSec = refreshInterval.toIntOrNull()?.coerceIn(2, 60) ?: 5,
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
