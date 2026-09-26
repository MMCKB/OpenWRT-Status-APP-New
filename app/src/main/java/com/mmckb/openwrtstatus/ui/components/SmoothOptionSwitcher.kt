package com.mmckb.openwrtstatus.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** smooth-option-switcher 参考实现使用的缓动曲线（cubic-bezier(0.34, 0.96, 0.6, 0.99)）。 */
val OptionSwitcherEasing = CubicBezierEasing(0.34f, 0.96f, 0.6f, 0.99f)

/**
 * SmoothOptionSwitcher（按 zhoulinhua0-star/smooth-option-switcher skill 规范的应用适配）：
 *
 * - 指示器是持久元素：状态切换不重建，动画始终从当前位置连续进行（含反向与快速反复）；
 * - 单击某段：指示器以 skill 缓动曲线滑到该段；
 * - 长按拖动：指示器中心 1:1 连续跟随手指（snapTo，无迟滞），松手提交所在段；
 * - 文字逐字渲染：每个字符的颜色由指示器当前位置连续决定——被盖住即变白，
 *   边缘扫过时逐字过渡，绝不整段到一半才变色。
 *
 * 应用侧仅适配配色 / 字体 / 圆角，不引入参考实现的配色与形状。
 */
@Composable
fun SmoothOptionSwitcher(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val n = options.size
    val selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // 持久指示器：位置以「段索引」（可带小数）表示。拖动 snapTo 逐帧跟手，
    // 松手 / 点击 / 外部选中变化时按 skill 缓动曲线平滑归位。
    val indicator = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selected, options.size) {
        indicator.animateTo(selectedIndex.toFloat(), tween(300, easing = OptionSwitcherEasing))
    }

    BoxWithConstraints(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceVariant)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        scope.launch {
                            val seg = size.width.toFloat() / n
                            indicator.snapTo(((offset.x - seg / 2f) / seg).coerceIn(0f, (n - 1).toFloat()))
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        scope.launch {
                            val seg = size.width.toFloat() / n
                            indicator.snapTo(((change.position.x - seg / 2f) / seg).coerceIn(0f, (n - 1).toFloat()))
                        }
                    },
                    onDragEnd = {
                        val idx = indicator.value.roundToInt().coerceIn(0, n - 1)
                        currentOnSelect(options[idx].first)
                        scope.launch {
                            indicator.animateTo(idx.toFloat(), tween(280, easing = OptionSwitcherEasing))
                        }
                    },
                    onDragCancel = {}
                )
            }
    ) {
        val segW = maxWidth / n
        val density = androidx.compose.ui.platform.LocalDensity.current
        val segPx = with(density) { segW.toPx() }
        val padPx = with(density) { 3.dp.toPx() }
        val textMeasurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelMedium
        val layouts = remember(options, textMeasurer) {
            options.map {
                textMeasurer.measure(AnnotatedString(it.second), labelStyle, maxLines = 1,
                    constraints = Constraints())
            }
        }

        // 1) 指示器（持久元素，GPU 平移，不重建）
        Box(
            modifier = Modifier
                .offset(x = segW * indicator.value)
                .width(segW)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(colors.primary)
        )

        // 2) 逐字标签层：每帧按指示器位置重算每个字符的颜色
        Canvas(Modifier.fillMaxSize()) {
            val f = indicator.value
            val il = f * segPx + padPx
            val ir = (f + 1) * segPx - padPx
            options.forEachIndexed { i, (_, label) ->
                val layout = layouts[i]
                val left = i * segPx + (segPx - layout.size.width) / 2f
                val top = (size.height - layout.size.height) / 2f
                // 底层：整段未选中色
                drawText(textLayoutResult = layout, color = colors.onSurfaceVariant, topLeft = Offset(left, top))
                // 逐字：按字符与指示器的覆盖比例，以选中色描画
                for (off in label.indices) {
                    val box = layout.getBoundingBox(off)
                    val charLeft = left + box.left
                    val charRight = left + box.right
                    val overlap = min(charRight, ir) - max(charLeft, il)
                    if (overlap <= 0f) continue
                    val alpha = (overlap / box.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    clipRect(left = charLeft, top = top, right = charRight, bottom = size.height) {
                        drawText(
                            textLayoutResult = layout,
                            color = colors.onPrimary,
                            alpha = alpha,
                            topLeft = Offset(left, top)
                        )
                    }
                }
            }
        }

        // 3) 点按热区（点击 → 外部选中状态变化 → 指示器按缓动曲线滑过去）
        Row(Modifier.fillMaxSize()) {
            options.forEach { (value, _) ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(value) }
                )
            }
        }
    }
}
