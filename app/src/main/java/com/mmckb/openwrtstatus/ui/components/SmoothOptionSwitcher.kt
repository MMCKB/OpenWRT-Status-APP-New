package com.mmckb.openwrtstatus.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.launch

/** smooth-option-switcher 参考实现使用的缓动曲线（cubic-bezier(0.34, 0.96, 0.6, 0.99)）。 */
val OptionSwitcherEasing = CubicBezierEasing(0.34f, 0.96f, 0.6f, 0.99f)

/**
 * SmoothOptionSwitcher（按 zhoulinhua0-star/smooth-option-switcher skill 的交互规范）：
 * 互斥选项的分段控件，选中段由一个连续滑动的实心指示器标记。
 *
 * 交互：
 * - 单击某段：指示器以参考实现的缓动曲线滑到该段（无涟漪灰底）；
 * - 长按后拖动：手指划到哪个字上，指示器就完整落到那个字上，该字
 *   同时变白、其余变灰——指示器与文字严格同步，不会出现指示器盖到
 *   一半文字才变色的情况；松手提交选择（往返可逆、等分段几何）。
 *
 * 适配当前应用的色板与字体，不引入参考实现的配色与形状。
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
    val selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    // 长按拖动在手势协程里读取/调用最新值，避免闭包捕获到过期状态。
    val currentOnSelect by rememberUpdatedState(onSelect)

    // 指示器位置，单位为「段索引」（可带小数）：拖动时 snapTo 逐帧跟随手指，
    // 松手 / 点击 / 外部选中变化时 animateTo 平滑归位到目标段。
    val indicator = remember { Animatable(selectedIndex.toFloat()) }
    LaunchedEffect(selected, options.size) {
        indicator.animateTo(selectedIndex.toFloat(), tween(300, easing = OptionSwitcherEasing))
    }

    // 拖动中手指悬停的段（文字高亮实时跟随）；null = 非拖动态。
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    val highlightIndex = dragIndex ?: selectedIndex

    BoxWithConstraints(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceVariant)
            .pointerInput(options.size) {
                fun indexAt(x: Float): Int =
                    ((x / size.width.coerceAtLeast(1)) * options.size)
                        .toInt().coerceIn(0, options.size - 1)

                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val idx = indexAt(offset.x)
                        dragIndex = idx
                        scope.launch {
                            indicator.animateTo(idx.toFloat(), tween(160, easing = OptionSwitcherEasing))
                        }
                    },
                    onDrag = { change, _ ->
                        val idx = indexAt(change.position.x)
                        if (idx != dragIndex) {
                            dragIndex = idx
                            scope.launch {
                                indicator.animateTo(idx.toFloat(), tween(180, easing = OptionSwitcherEasing))
                            }
                        }
                    },
                    onDragEnd = {
                        val idx = dragIndex
                        if (idx != null) {
                            currentOnSelect(options[idx].first)
                            scope.launch {
                                indicator.animateTo(idx.toFloat(), tween(280, easing = OptionSwitcherEasing))
                            }
                        }
                        dragIndex = null
                    },
                    onDragCancel = { dragIndex = null }
                )
            }
    ) {
        val segment = maxWidth / options.size
        // 滑动指示器：拖动时按段完整跟随手指（与文字变色同步），
        // 其余时候按选中索引平滑平移。
        Box(
            modifier = Modifier
                .offset(x = segment * indicator.value)
                .width(segment)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(colors.primary)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, (value, label) ->
                val isSelected = index == highlightIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // 不带涟漪反馈：点按只有指示器滑动与文字变色（无灰色水波）。
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(value) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}
