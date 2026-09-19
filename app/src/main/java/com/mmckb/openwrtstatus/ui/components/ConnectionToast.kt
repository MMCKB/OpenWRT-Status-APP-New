package com.mmckb.openwrtstatus.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** 应用级连接状态：由主界面的轮询驱动，二级页面据此提示断连。 */
object ConnectionMonitor {
    enum class Status { Unknown, Online, Offline }

    val status = MutableStateFlow(Status.Unknown)
}

private const val TOAST_DURATION_MS = 3500L

/**
 * 右下角纯色胶囊提示：仅在与路由器「在线 → 离线」状态切换时出现，
 * 数秒后自动消失。放在应用窗口层（非系统 Toast），样式与应用一致。
 */
@Composable
fun ConnectionToastHost(modifier: Modifier = Modifier) {
    val status by ConnectionMonitor.status.collectAsState()
    var visible by remember { mutableStateOf(false) }
    var previous by remember { mutableStateOf(ConnectionMonitor.Status.Unknown) }

    LaunchedEffect(status) {
        if (previous == ConnectionMonitor.Status.Online && status == ConnectionMonitor.Status.Offline) {
            visible = true
        }
        previous = status
    }
    LaunchedEffect(visible) {
        if (visible) {
            delay(TOAST_DURATION_MS)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = AppShapes.pill,
            color = Color(0xFF323232),
            contentColor = Color.White
        ) {
            Text(
                "路由器连接已断开",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }
}
