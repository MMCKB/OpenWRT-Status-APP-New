package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.AppSwitch
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.components.CardSectionTitle
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

/**
 * 添加/编辑设备的完整表单（运行在独立的 DeviceEditActivity 中）。
 * SSH 常开且与路由器配置同级内联，仅需填端口与密码（SSH 密码留空则回退路由器密码）。
 * 设备名称不允许与其他设备重复。
 */
@Composable
fun DeviceEditScreen(
    initial: RouterConfig,
    isNew: Boolean,
    existingNames: List<String>,
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
    var showPassword by remember { mutableStateOf(false) }
    var showSshPassword by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }

    val colors = LocalAppColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 2.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppBackButton(onBack = onCancel)
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
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                            tint = colors.onSurfaceVariant
                        )
                    }
                }
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
                visualTransformation = if (showSshPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showSshPassword = !showSshPassword }) {
                        Icon(
                            imageVector = if (showSshPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showSshPassword) "隐藏密码" else "显示密码",
                            tint = colors.onSurfaceVariant
                        )
                    }
                }
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
                    if (existingNames.any { it == effective }) {
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
        AppSwitch(checked = checked, onCheckedChange = onChanged)
    }
}
