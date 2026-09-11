package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmckb.openwrtstatus.data.ssh.SshTerminal
import com.mmckb.openwrtstatus.ui.RouterViewModel
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.CardSectionTitle
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

private val QUICK_COMMANDS = listOf(
    "ubus call system info",
    "cat /tmp/dhcp.leases",
    "df -h",
    "free",
    "uptime"
)

/**
 * Remote shell over SSH. Commands are written to a persistent PTY shell channel, so
 * state such as the working directory is preserved between commands.
 */
@Composable
fun TerminalScreen(
    viewModel: RouterViewModel,
    modifier: Modifier = Modifier
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val state by viewModel.terminal.state.collectAsStateWithLifecycle()
    val output by viewModel.terminal.output.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(output) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StatusCard(
            state = state,
            target = "${config.sshUsername}@${config.sshHost.ifBlank { config.ip }}:${config.sshPort}",
            enabled = config.sshEnabled,
            onConnect = { viewModel.connectSsh() },
            onDisconnect = { viewModel.disconnectSsh() }
        )

        AppCard(modifier = Modifier.weight(1f, fill = false)) {
            CardSectionTitle("会话输出")
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(LocalAppColors.current.surfaceVariant, AppShapes.block)
                    .padding(12.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = output.ifEmpty { "未连接。请在「设置」中启用 SSH 并填写凭据后点击连接。" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = LocalAppColors.current.onSurface
                )
            }
        }

        AppCard {
            CardSectionTitle("快捷命令")
            Spacer(Modifier.height(10.dp))
            QUICK_COMMANDS.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { cmd ->
                        Button(
                            onClick = { viewModel.sendCommand(cmd) },
                            modifier = Modifier.weight(1f),
                            enabled = state is SshTerminal.State.Connected
                        ) {
                            Text(cmd, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("输入命令后回车") }
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (input.isNotBlank()) {
                            viewModel.sendCommand(input.trim())
                            input = ""
                        }
                    },
                    enabled = state is SshTerminal.State.Connected
                ) { Text("发送") }
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: SshTerminal.State,
    target: String,
    enabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val colors = LocalAppColors.current
    val (label, color) = when (state) {
        is SshTerminal.State.Connected -> "已连接" to colors.success
        is SshTerminal.State.Connecting -> "连接中…" to colors.accent
        is SshTerminal.State.Failed -> "失败" to colors.error
        else -> "未连接" to colors.onSurfaceVariant
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                Text(target, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (state is SshTerminal.State.Failed) {
            Spacer(Modifier.height(8.dp))
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        if (!enabled) {
            Spacer(Modifier.height(8.dp))
            Text(
                "SSH 未启用，请在「设置」中开启并填写主机与凭据。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onConnect,
                modifier = Modifier.weight(1f),
                enabled = enabled && state !is SshTerminal.State.Connected && state !is SshTerminal.State.Connecting
            ) { Text("连接") }
            Button(
                onClick = onDisconnect,
                modifier = Modifier.weight(1f),
                enabled = state is SshTerminal.State.Connected
            ) { Text("断开") }
        }
    }
}
