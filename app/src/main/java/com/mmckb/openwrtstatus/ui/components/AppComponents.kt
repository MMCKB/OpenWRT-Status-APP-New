package com.mmckb.openwrtstatus.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.mmckb.openwrtstatus.ui.glass.DampedDragAnimation
import com.mmckb.openwrtstatus.ui.glass.InteractiveHighlight
import com.mmckb.openwrtstatus.ui.glass.LiquidTab
import com.mmckb.openwrtstatus.ui.glass.LocalLiquidTabScale
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * The primary layout unit: a large rounded card with generous padding and a hairline border.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalAppColors.current
    // Flat card: separation comes from the border only, matching the RN predecessor
    // (no tonal/shadow elevation, so cards never cast a grey halo on the background).
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.card,
        color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            content = content
        )
    }
}

/** Section heading used inside an [AppCard] to split information into clear zones. */
@Composable
fun CardSectionTitle(text: String, trailing: @Composable (() -> Unit)? = null) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A labelled value block nested inside a card. */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalAppColors.current.onSurface
) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .clip(AppShapes.block)
            .background(colors.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

/** Minimal line chart used by the monitoring screen. */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = LocalAppColors.current.accent,
    maxValue: Float? = null
) {
    if (values.size < 2) {
        val colors = LocalAppColors.current
        Text(
            "采集中… (${values.size}/2)",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = modifier
        )
        return
    }
    Canvas(modifier = modifier.fillMaxWidth().height(44.dp)) {
        val top = (maxValue ?: (values.maxOrNull() ?: 1f)).coerceAtLeast(1e-6f)
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val ratio = (value / top).coerceIn(0f, 1f)
            val y = size.height - ratio * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/**
 * A simple top app bar that draws a solid background from [LocalAppColors] and exposes a
 * [RowScope] for trailing actions (e.g. a refresh button).
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(60.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (navigationIcon != null) {
            navigationIcon()
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

data class TabItem(
    val label: String,
    val icon: ImageVector
)

/** Width of a single tab; the bar keeps a compact fixed size instead of filling the screen. */
private val TAB_WIDTH = 56.dp

/**
 * A floating liquid-glass tab bar reproducing the interaction of the official
 * Kyant0/AndroidLiquidGlass catalog: press-and-drag the pill with a damped spring,
 * a touch-following specular highlight, and lens / shadow / inner-shadow reactions.
 *
 * The bar is sized to its content (`TAB_WIDTH * tabs.size`) so it stays compact and centred
 * rather than stretching across the screen.
 *
 * Effects require Android 12 (API 31) for blur/vibrancy and Android 13 (API 33) for lens and
 * the shader highlight; older versions degrade to a translucent capsule without crashing.
 */
@Composable
fun FloatingTabBar(
    backdrop: Backdrop,
    tabs: List<TabItem>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val isLight = !isSystemInDarkTheme()
    val containerColor = if (isLight) {
        Color(0xFFFAFAFA).copy(alpha = 0.42f)
    } else {
        Color(0xFF14171C).copy(alpha = 0.42f)
    }
    val accent = colors.accent
    val density = LocalDensity.current

    val barWidth = TAB_WIDTH * tabs.size
    val tabWidth: Dp = (barWidth - 12.dp) / tabs.size
    val tabWidthPx = with(density) { tabWidth.toPx() }

    val animationScope = rememberCoroutineScope()

    // Deliberately NOT keyed on selectedIndex: keying would swap the underlying
    // MutableIntState object on every external tab change, while the snapshotFlow below
    // (launched once) keeps observing the original object. Taps would then stop emitting
    // after the first switch (first tap fine, later taps dead). External changes are
    // synced in via LaunchedEffect(selectedIndex) instead.
    var currentIndex by remember { mutableIntStateOf(selectedIndex) }

    val offsetAnimation = remember { Animatable(0f) }
    val panelOffset by remember(density, tabWidthPx) {
        derivedStateOf {
            val fraction = (offsetAnimation.value / tabWidthPx).fastCoerceIn(-1f, 1f)
            with(density) { 4.dp.toPx() * sign(fraction) * EaseOut.transform(abs(fraction)) }
        }
    }

    // Records the bar content so the moving pill can sample it.
    val tabsBackdrop = rememberLayerBackdrop()

    val dampedDragAnimation = remember(animationScope, tabs.size, tabWidthPx) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(tabs.size - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            onDragStarted = {},
            onDragStopped = {
                val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabs.size - 1)
                currentIndex = targetIndex
                animateToValue(targetIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                updateValue(
                    (targetValue + dragAmount.x / tabWidthPx)
                        .fastCoerceIn(0f, (tabs.size - 1).toFloat())
                )
                animationScope.launch {
                    offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                }
            }
        )
    }

    // Sync external selection changes (e.g. AppRoot jumping back to 概览 after saving settings).
    LaunchedEffect(selectedIndex) {
        currentIndex = selectedIndex
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { currentIndex }
            .drop(1)
            .collectLatest { index ->
                dampedDragAnimation.animateToValue(index.toFloat())
                onTabSelected(index)
            }
    }

    val interactiveHighlight = remember(animationScope, tabWidthPx) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { size, _ ->
                Offset(
                    (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset,
                    size.height / 2f
                )
            }
        )
    }

    Box(contentAlignment = Alignment.CenterStart, modifier = modifier.width(barWidth)) {
        // 1) The glass bar itself.
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        blur(8.dp.toPx())
                        lens(24.dp.toPx(), 24.dp.toPx())
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(58.dp)
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == currentIndex
                LiquidTab(onClick = { currentIndex = index }) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (selected) accent else colors.onSurfaceVariant
                    )
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) accent else colors.onSurfaceVariant
                    )
                }
            }
        }

        CompositionLocalProvider(
            LocalLiquidTabScale provides { lerp(1f, 1.2f, dampedDragAnimation.pressProgress) }
        ) {
            // 2) Invisible copy that records the bar content, sampled by the moving pill.
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            blur(8.dp.toPx())
                            lens(24.dp.toPx() * progress, 24.dp.toPx() * progress)
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier)
                    .height(52.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accent)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    // Recording layer: pixels only, must not intercept touches.
                    LiquidTab(onClick = null) {
                        Icon(imageVector = tab.icon, contentDescription = null)
                        Text(tab.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // 3) The moving, pressable pill.
        Box(
            Modifier
                .padding(horizontal = 6.dp)
                .graphicsLayer {
                    translationX = dampedDragAnimation.value * tabWidthPx + panelOffset
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(10.dp.toPx() * progress, 14.dp.toPx() * progress, chromaticAberration = true)
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                    },
                    shadow = {
                        Shadow(alpha = dampedDragAnimation.pressProgress)
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(radius = 8.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(
                            if (isLight) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .height(52.dp)
                .width(tabWidth)
        )
    }
}

/**
 * App-styled modal dialog: the same flat bordered card language as [AppCard] instead of
 * the Material3 AlertDialog look. Hosted in a raw [androidx.compose.ui.window.Dialog] so
 * only the visual style is ours.
 *
 * Pass [content] for custom bodies (input fields, scrollable previews); otherwise
 * [message] is shown. An empty [dismissLabel] hides the dismiss button.
 */
@Composable
fun AppDialog(
    title: String,
    message: String = "",
    confirmLabel: String = "确定",
    dismissLabel: String = "取消",
    confirmColor: Color? = null,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val colors = LocalAppColors.current
    var backProgress by remember { mutableStateOf(0f) }
    Dialog(onDismissRequest = onDismiss) {
        // 预测性返回：手势中跟随系统规范缩放淡出（一级页面返回同款动效），取消则回弹，提交则关闭。
        PredictiveBackHandler {
            try {
                it.collect { event -> backProgress = event.progress }
                backProgress = 1f
                onDismiss()
            } catch (_: CancellationException) {
                backProgress = 0f
            }
        }
        val p = PredictiveBackEasing.transform(backProgress).coerceIn(0f, 1f)
        Surface(
            shape = RoundedCornerShape(24.dp + 20.dp * p),
            color = colors.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline),
            modifier = Modifier
                .widthIn(min = 280.dp, max = 360.dp)
                .graphicsLayer {
                    scaleX = 1f - 0.1f * p
                    scaleY = 1f - 0.1f * p
                    alpha = 1f - 0.4f * p
                }
        ) {
            Column(Modifier.padding(22.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                if (content != null) {
                    content()
                } else {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (dismissLabel.isNotEmpty()) {
                        TextButton(onClick = onDismiss) {
                            Text(dismissLabel, color = colors.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                        Text(
                            confirmLabel,
                            color = if (confirmEnabled) (confirmColor ?: colors.primary) else colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 统一的二级页返回按钮：系统返回箭头图标，替代各页各自的文字“返回”。 */
@Composable
fun AppBackButton(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    IconButton(onClick = onBack) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            tint = colors.onSurface
        )
    }
}

/** Top inset consumed by the translucent blurred top bar: status bar + bar height + gap. */
@Composable
fun rememberTopBarPadding(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp + 12.dp

/**
 * Interpolator mandated by the Material predictive back spec (0.1, 0.1, 0, 1) - matches
 * the SystemUI back animation interpolator so in-app previews track the gesture the
 * same way system surfaces do.
 */
val PredictiveBackEasing: CubicBezierEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
