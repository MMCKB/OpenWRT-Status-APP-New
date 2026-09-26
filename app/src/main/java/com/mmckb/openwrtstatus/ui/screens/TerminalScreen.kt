package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
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
 *
 * [hideOutput]：横屏双栏时隐藏左侧的输出区（输出渲染在右栏的
 * [TerminalOutputPane]），输入框与发送按钮位置保持不变。
 */
@Composable
fun TerminalScreen(
    viewModel: RouterViewModel,
    hideOutput: Boolean = false,
    bottomSpacer: androidx.compose.ui.unit.Dp = 76.dp,
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

        if (!hideOutput) {
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
        } else {
            Spacer(Modifier.weight(1f))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // 输入框与发送按钮同高同圆角。
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
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
                enabled = connected,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                modifier = Modifier.height(52.dp)
            ) { Text("发送") }
        }

        Spacer(Modifier.height(bottomSpacer))
    }
}

/** 横屏右栏的独立命令输出面板（与左侧共享同一个 SSH 会话流），铺满整个右栏。 */
@Composable
fun TerminalOutputPane(
    viewModel: RouterViewModel,
    modifier: Modifier = Modifier
) {
    val output by viewModel.terminal.output.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    LaunchedEffect(output) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    // 纯黑控制台铺满整个右栏（含状态栏/手势条区域），文字内容内侧留白。
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Text(
            text = output.ifEmpty { "未连接。在左侧点击「连接」开始 SSH 会话。" },
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = Color(0xFFE6E8EB),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = 44.dp, start = 14.dp, end = 14.dp, bottom = 14.dp)
        )
    }
}
