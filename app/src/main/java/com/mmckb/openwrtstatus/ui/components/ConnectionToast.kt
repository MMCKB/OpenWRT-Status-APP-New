package com.mmckb.openwrtstatus.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** 应用级连接状态：由主界面的轮询驱动，二级页面据此提示断连/恢复。 */
object ConnectionMonitor {
    enum class Status { Unknown, Online, Offline }

    val status = MutableStateFlow(Status.Unknown)
}

private const val TOAST_DURATION_MS = 3500L
private val OfflineColor = Color(0xFFF44336)
private val OnlineColor = Color(0xFF4CAF50)

private data class ToastData(
    val text: String,
    val color: Color,
    val showCross: Boolean
)

/**
 * 右下角纯色胶囊提示：与路由器「在线 → 离线」切换时显示红色断开胶囊
 * （路由器线条图标 + 右上角小叉），「离线 → 在线」恢复时显示绿色已连接胶囊。
 * 从右侧滑入、滑出，数秒后自动消失。
 */
@Composable
fun ConnectionToastHost(modifier: Modifier = Modifier) {
    val status by ConnectionMonitor.status.collectAsState()
    var toast by remember { mutableStateOf<ToastData?>(null) }
    var lastToast by remember { mutableStateOf(ToastData("路由器连接已断开", OfflineColor, true)) }
    var previous by remember { mutableStateOf(ConnectionMonitor.Status.Unknown) }

    LaunchedEffect(status) {
        toast = when {
            previous == ConnectionMonitor.Status.Online && status == ConnectionMonitor.Status.Offline ->
                ToastData("路由器连接已断开", OfflineColor, true)
            previous == ConnectionMonitor.Status.Offline && status == ConnectionMonitor.Status.Online ->
                ToastData("路由器已连接", OnlineColor, false)
            else -> toast
        }
        previous = status
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            lastToast = toast!!
            delay(TOAST_DURATION_MS)
            toast = null
        }
    }

    AnimatedVisibility(
        visible = toast != null,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        modifier = modifier
            .padding(end = 16.dp)
            .navigationBarsPadding()
            .padding(bottom = 12.dp)
    ) {
        val data = lastToast
        Surface(
            shape = AppShapes.pill,
            color = data.color,
            contentColor = Color.White
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Icon(
                        Icons.Outlined.Router,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    if (data.showCross) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = null,
                            modifier = Modifier
                                .size(9.dp)
                                .align(Alignment.TopEnd)
                                .background(data.color, CircleShape)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    data.text,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
