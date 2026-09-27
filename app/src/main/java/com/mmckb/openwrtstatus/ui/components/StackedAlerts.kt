package com.mmckb.openwrtstatus.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 相邻两层错开的露出高度：下层卡片在上层下方露出的边缘宽度。 */
private val LAYER_PEEK = 12.dp

/** 提示类型：成功（绿）/ 警告与进行中（黄）/ 错误（红）。 */
enum class AppAlertType { Info, Success, Warning, Error }

/** 一条悬浮提示。栈顶（最新）在第 3 层，[exiting] = 正在播放消失动画。 */
data class AlertItem(
    val id: Long,
    val type: AppAlertType,
    val title: String,
    val description: String? = null,
    val exiting: Boolean = false
)

/**
 * 提示栈状态：反复触发时堆叠，最多保留 3 层（丢最旧的）；
 * 消失链从第 3 层（最新）开始逐层推进到第 1 层，每层间隔逐渐加快；
 * 再次触发会重置消失链（新提示获得完整的首段停留时间）。
 */
class AlertStackState(private val scope: kotlinx.coroutines.CoroutineScope) {

    var items by mutableStateOf(emptyList<AlertItem>())
        private set

    private var nextId = 1L
    private var chainJob: Job? = null

    fun push(type: AppAlertType, title: String, description: String? = null) {
        // 丢弃正在退出动画中的条目（其消失链已被本次触发重置），再叠加新提示
        items = (listOf(AlertItem(nextId++, type, title, description)) + items.filterNot { it.exiting })
            .take(MAX_LAYERS)
        startDismissChain()
    }

    private fun startDismissChain() {
        chainJob?.cancel()
        chainJob = scope.launch {
            var step = 0
            while (items.isNotEmpty()) {
                delay(DISMISS_STEPS[step.coerceAtMost(DISMISS_STEPS.lastIndex)])
                if (items.isEmpty()) break
                items = items.mapIndexed { index, item ->
                    if (index == 0) item.copy(exiting = true) else item
                }
                delay(EXIT_ANIM_MS)
                items = items.drop(1)
                step++
            }
        }
    }

    companion object {
        /** 栈内最多 3 层。 */
        const val MAX_LAYERS = 3

        /** 消失链各步停留：第 3 层 → 第 2 层 → 第 1 层，逐层加快。 */
        val DISMISS_STEPS = longArrayOf(2500L, 1600L, 1000L)

        /** 单层退出动画时长。 */
        const val EXIT_ANIM_MS = 280L
    }
}

@Composable
fun rememberAlertStackState(): AlertStackState {
    val scope = rememberCoroutineScope()
    return remember { AlertStackState(scope) }
}

/**
 * 悬浮提示栈：三条提示像一叠卡片堆在同一位置——最新（第 3 层）完整盖在最上面，
 * 旧卡片向下错开露出一条边缘；消失从第 3 层到第 1 层连续加速，
 * 上层收起时下层平滑上移补位。进入/堆叠/退出均有动画。
 * 宿主不拦截触摸（卡片本身无点击处理，触摸穿透到下层内容）。
 */
@Composable
fun StackedAlertHost(
    state: AlertStackState,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        // 从最旧到最新绘制，最新的一张盖在最上层（第 3 层）
        state.items.asReversed().forEachIndexed { revIndex, item ->
            val layer = state.items.lastIndex - revIndex // 最新 = 0（最上层）
            key(item.id) {
                var entered by remember(item.id) { mutableStateOf(false) }
                LaunchedEffect(item.id) { entered = true }
                // 每层向下错开 [LAYER_PEEK] 露边；层级变化（上层消失）时平滑上移补位
                val topOffset by animateDpAsState(
                    targetValue = LAYER_PEEK * layer,
                    animationSpec = tween(260),
                    label = "alertLayerOffset"
                )
                AnimatedVisibility(
                    visible = entered && !item.exiting,
                    enter = fadeIn(tween(220)) +
                        slideInVertically(tween(280)) { -it },
                    exit = fadeOut(tween(240)) +
                        slideOutVertically(tween(240)) { -it } +
                        shrinkVertically(tween(240))
                ) {
                    Box(Modifier.padding(top = topOffset)) {
                        StackedAlertCard(item)
                    }
                }
            }
        }
    }
}

/**
 * 单条提示卡片（横幅版加高：16dp 垂直内边距、20dp 图标）：
 * 纯色粉红背景，类型通过图标与标题颜色表达——绿 = 成功、黄 = 警告/进行中、红 = 错误。
 */
@Composable
private fun StackedAlertCard(item: AlertItem) {
    val colors = LocalAppColors.current
    val dark = isSystemInDarkTheme()
    val accent = when (item.type) {
        AppAlertType.Success -> colors.success
        AppAlertType.Warning, AppAlertType.Info ->
            if (dark) Color(0xFFFBBF24) else Color(0xFFA16207)
        AppAlertType.Error -> colors.error
    }
    val background = Color(0xFFFFC9D5)
    val borderColor = Color(0xFFF7A8BE)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = when (item.type) {
                AppAlertType.Info -> Icons.Filled.Info
                AppAlertType.Success -> Icons.Filled.CheckCircle
                AppAlertType.Warning -> Icons.Filled.Warning
                AppAlertType.Error -> Icons.Filled.ErrorOutline
            },
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent
            )
            if (!item.description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent.copy(alpha = 0.85f)
                )
            }
        }
    }
}
