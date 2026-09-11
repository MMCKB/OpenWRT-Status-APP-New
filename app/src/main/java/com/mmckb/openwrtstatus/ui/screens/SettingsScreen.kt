package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.CardSectionTitle
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    config: RouterConfig,
    onSave: (RouterConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var ip by remember { mutableStateOf(config.ip) }
    var port by remember { mutableStateOf(config.port.toString()) }
    var username by remember { mutableStateOf(config.username) }
    var password by remember { mutableStateOf(config.password) }
    var useHttps by remember { mutableStateOf(config.useHttps) }
    var allowInsecureTls by remember { mutableStateOf(config.allowInsecureTls) }
    var useMock by remember { mutableStateOf(config.useMock) }
    var refreshInterval by remember { mutableStateOf(config.refreshIntervalSec.toString()) }

    var sshEnabled by remember { mutableStateOf(config.sshEnabled) }
    var sshHost by remember { mutableStateOf(config.sshHost) }
    var sshPort by remember { mutableStateOf(config.sshPort.toString()) }
    var sshUsername by remember { mutableStateOf(config.sshUsername) }
    var sshPassword by remember { mutableStateOf(config.sshPassword) }

    val colors = LocalAppColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AppCard {
            CardSectionTitle("路由器连接")
            Spacer(Modifier.height(12.dp))
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
                "启用后可在「终端」页执行命令，并在「设备」页读取 /tmp/dhcp.leases 租约。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }

        Button(
            onClick = {
                val portInt = port.toIntOrNull()?.coerceIn(1, 65535) ?: 80
                val interval = refreshInterval.toIntOrNull()?.coerceIn(2, 60) ?: 5
                val sshPortInt = sshPort.toIntOrNull()?.coerceIn(1, 65535) ?: 22
                onSave(
                    RouterConfig(
                        ip = ip.ifBlank { "192.168.1.1" },
                        port = portInt,
                        username = username.ifBlank { "root" },
                        password = password,
                        useHttps = useHttps,
                        allowInsecureTls = allowInsecureTls,
                        useMock = useMock,
                        refreshIntervalSec = interval,
                        sshEnabled = sshEnabled,
                        sshHost = sshHost,
                        sshPort = sshPortInt,
                        sshUsername = sshUsername.ifBlank { "root" },
                        sshPassword = sshPassword
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存")
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
