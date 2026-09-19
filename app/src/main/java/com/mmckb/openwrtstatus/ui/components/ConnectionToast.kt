package com.mmckb.openwrtstatus.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

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
 * 纯色胶囊提示：出现在屏幕右侧、垂直居中略偏下的位置。
 * - 「在线 → 离线」：红色，路由器线条图标右上角带小叉；
 * - 「离线 → 在线」：绿色，不带小叉。
 * 交互：从右侧滑入滑出；可向右拖动提前滑走；按住不动时胶囊停在原地
 * （自动消失计时暂停），松手后未过阈值则回弹，过阈值则滑走消失。
 */
@Composable
fun ConnectionToastHost(modifier: Modifier = Modifier) {
    val status by ConnectionMonitor.status.collectAsState()
    var toast by remember { mutableStateOf<ToastData?>(null) }
    var lastToast by remember { mutableStateOf(ToastData("路由器连接已断开", OfflineColor, true)) }
    var previous by remember { mutableStateOf(ConnectionMonitor.Status.Unknown) }
    var isHeld by remember { mutableStateOf(false) }
    val dragX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissPx = with(density) { 96.dp.toPx() }

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
    // 自动消失：按住期间暂停计时，松手后重新计时。
    LaunchedEffect(toast, isHeld) {
        if (toast != null && !isHeld) {
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
            .offset(y = 40.dp)
    ) {
        val data = lastToast
        Surface(
            shape = AppShapes.pill,
            color = data.color,
            contentColor = Color.White,
            modifier = Modifier
                .offset { IntOffset(dragX.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { isHeld = true },
                        onDrag = { change, amount ->
                            change.consume()
                            scope.launch {
                                dragX.snapTo((dragX.value + amount.x).coerceAtLeast(0f))
                            }
                        },
                        onDragEnd = {
                            isHeld = false
                            scope.launch {
                                if (abs(dragX.value) > dismissPx) {
                                    toast = null
                                    dragX.snapTo(0f)
                                } else {
                                    dragX.animateTo(
                                        0f,
                                        spring(stiffness = Spring.StiffnessMediumLow)
                                    )
                                }
                            }
                        },
                        onDragCancel = { isHeld = false }
                    )
                }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Icon(
                        Icons.Outlined.Router,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    if (data.showCross) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = null,
                            modifier = Modifier
                                .size(11.dp)
                                .align(Alignment.TopEnd)
                                .background(data.color, CircleShape)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    data.text,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
