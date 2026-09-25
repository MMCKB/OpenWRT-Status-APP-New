package com.mmckb.openwrtstatus.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

/** smooth-option-switcher 参考实现使用的缓动曲线（cubic-bezier(0.34, 0.96, 0.6, 0.99)）。 */
val OptionSwitcherEasing = CubicBezierEasing(0.34f, 0.96f, 0.6f, 0.99f)

/**
 * SmoothOptionSwitcher（按 zhoulinhua0-star/smooth-option-switcher skill 的交互规范）：
 * 互斥选项的分段控件，选中段由一个连续滑动的实心指示器标记，
 * 切换使用参考实现的缓动曲线，往返可逆、等分段几何、文字颜色随选中状态过渡。
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
    val selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val progress by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = tween(300, easing = OptionSwitcherEasing),
        label = "optionSwitcherIndicator"
    )
    BoxWithConstraints(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceVariant)
    ) {
        val segment = maxWidth / options.size
        // 滑动指示器：随选中索引平移一个段宽。
        Box(
            modifier = Modifier
                .offset(x = segment * progress)
                .width(segment)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(colors.primary)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(value) },
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
