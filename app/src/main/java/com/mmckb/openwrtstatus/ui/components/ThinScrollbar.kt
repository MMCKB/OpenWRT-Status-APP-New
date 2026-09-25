package com.mmckb.openwrtstatus.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.delay

private const val SCROLLBAR_HIDE_DELAY_MS = 800L
private val SCROLLBAR_WIDTH = 4.dp
private val SCROLLBAR_MIN_THUMB = 28.dp

/**
 * 弹窗内可滚动内容区的统一容器：右侧叠加一条现代样式的细滚动条（overlay 风格），
 * 滚动时出现、停止约 0.8 秒后淡出；拇指高度按「可视区 / 全部内容」比例计算，
 * 内容不足一屏时不显示。用于 AppDialog 中所有可滚动的 Column / 长文本。
 */
@Composable
fun ThinScrollbarColumn(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    var viewportPx by remember { mutableIntStateOf(0) }
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(state)
                .onSizeChanged { viewportPx = it.height },
            verticalArrangement = verticalArrangement
        ) {
            content()
        }
        if (state.maxValue > 0 && viewportPx > 0) {
            val colors = LocalAppColors.current
            var visible by remember { mutableStateOf(true) }
            LaunchedEffect(state.isScrollInProgress) {
                if (state.isScrollInProgress) {
                    visible = true
                } else {
                    delay(SCROLLBAR_HIDE_DELAY_MS)
                    visible = false
                }
            }
            val alpha by animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = tween(180),
                label = "thinScrollbarAlpha"
            )
            val barColor = colors.onSurfaceVariant
            // matchParentSize：滚动条不参与 Box 的测高（否则会把容器撑到最大高度）。
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .alpha(alpha)
            ) {
                val trackH = size.height
                if (trackH <= 0f) return@Canvas
                val barW = SCROLLBAR_WIDTH.toPx()
                val thumbH = maxOf(
                    SCROLLBAR_MIN_THUMB.toPx(),
                    trackH * (trackH / (trackH + state.maxValue))
                )
                val progress = (state.value.toFloat() / state.maxValue).coerceIn(0f, 1f)
                drawRoundRect(
                    color = barColor.copy(alpha = 0.45f),
                    topLeft = Offset(size.width - barW, (trackH - thumbH) * progress),
                    size = Size(barW, thumbH),
                    cornerRadius = CornerRadius(barW / 2f)
                )
            }
        }
    }
}
