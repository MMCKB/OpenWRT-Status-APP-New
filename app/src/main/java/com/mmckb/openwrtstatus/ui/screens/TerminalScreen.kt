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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmckb.openwrtstatus.data.ssh.SshTerminal
import com.mmckb.openwrtstatus.ui.RouterViewModel
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.components.rememberTopBarPadding
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

/**
 * Remote shell over SSH. The connection lives in the top bar (connect / disconnect
 * button); everything below is a rounded pure-black terminal area with the command
 * input sitting above the floating tab bar.
 */
@Composable
fun TerminalScreen(
    viewModel: RouterViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.terminal.state.collectAsStateWithLifecycle()
    val output by viewModel.terminal.output.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    var input by remember { mutableStateOf("") }
    val colors = LocalAppColors.current
    val connected = state is SshTerminal.State.Connected

    LaunchedEffect(output) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(top = rememberTopBarPadding())
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state is SshTerminal.State.Failed) {
            Text(
                (state as SshTerminal.State.Failed).message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black, AppShapes.card)
                .padding(14.dp)
        ) {
            Text(
                text = output.ifEmpty { "未连接。点击右上角「连接」开始 SSH 会话。" },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = Color(0xFFE6E8EB),
                modifier = Modifier.verticalScroll(scrollState)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text("输入命令后回车", style = MaterialTheme.typography.bodySmall)
                },
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (input.isNotBlank()) {
                        viewModel.sendCommand(input.trim())
                        input = ""
                    }
                },
                enabled = connected
            ) { Text("发送") }
        }

        // Keeps the input clear of the floating glass tab pill.
        Spacer(Modifier.height(76.dp))
    }
}
