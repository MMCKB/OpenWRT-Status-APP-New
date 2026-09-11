package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
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
    var useMock by remember { mutableStateOf(config.useMock) }
    var refreshInterval by remember { mutableStateOf(config.refreshIntervalSec.toString()) }
    val colors = LocalAppColors.current

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("路由器地址 (IP)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() } },
            label = { Text("端口") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("使用 HTTPS", modifier = Modifier.weight(1f))
            Switch(checked = useHttps, onCheckedChange = { useHttps = it })
        }
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("演示模式（使用模拟数据，无需真实路由器）", modifier = Modifier.weight(1f))
            Switch(checked = useMock, onCheckedChange = { useMock = it })
        }
        OutlinedTextField(
            value = refreshInterval,
            onValueChange = { refreshInterval = it.filter { c -> c.isDigit() } },
            label = { Text("刷新间隔 (秒, 2-60)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        Button(
            onClick = {
                val portInt = port.toIntOrNull()?.coerceIn(1, 65535) ?: 80
                val interval = refreshInterval.toIntOrNull()?.coerceIn(2, 60) ?: 5
                onSave(
                    RouterConfig(
                        ip = ip.ifBlank { "192.168.1.1" },
                        port = portInt,
                        username = username.ifBlank { "root" },
                        password = password,
                        useHttps = useHttps,
                        useMock = useMock,
                        refreshIntervalSec = interval
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "需路由器已安装 luci-rpc（OpenWrt 官方源默认包含）。兼容 OpenWrt 23.05 / 24.10 / 25.12。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
}
