package com.mmckb.openwrtstatus.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 磁贴点击起点登记处。
 *
 * 磁贴（工具页的两列磁贴 / 列表卡片、设置页的入口卡片）被点击时，把自己在窗口里的矩形
 * 记到这里；随后 AppRoot 的 openSecondary 取走（[take]）并作为「放大展开」的起点。
 * 只在主线程读写：点击回调与 openSecondary 在同一次点击里先后执行。
 */
class TileExpandOrigin {
    private var rect: Rect? = null

    fun record(bounds: Rect) {
        rect = bounds
    }

    /** 取走并清空；非磁贴入口（没有登记过）返回 null。 */
    fun take(): Rect? = rect.also { rect = null }
}

val LocalTileExpandOrigin = compositionLocalOf { TileExpandOrigin() }

private class CoordinatesHolder {
    var value: LayoutCoordinates? = null
}

/**
 * 可点击磁贴：行为等同 [clickable]，额外在点击瞬间登记自己的窗口矩形，
 * 供二级页从这块磁贴的位置放大展开。放在 clip 之后、其余样式之前即可。
 */
@Composable
fun Modifier.tileExpandClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val origin = LocalTileExpandOrigin.current
    val holder = remember { CoordinatesHolder() }
    return this
        .onGloballyPositioned { holder.value = it }
        .clickable(enabled = enabled) {
            holder.value?.takeIf { it.isAttached }?.let { origin.record(it.boundsInRoot()) }
            onClick()
        }
}

private const val OPEN_MS = 560
private const val CLOSE_MS = 480
private val ExpandEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/**
 * 磁贴放大展开的动画状态：[progress] 0 = 磁贴大小，1 = 全屏。
 *
 * 与「添加设备」展开层同一套做法——页面内容始终按整屏布局、从不缩放，只在绘制阶段按进度
 * 裁出「磁贴矩形 → 全屏」的圆角窗口，动画帧内零重组、零重测量。
 * 动画挂在传入的 [scope]（AppRoot 的 rootScope）上，不随手势回调被取消而中断。
 */
class TileExpandState(private val scope: CoroutineScope) {
    val progress = Animatable(0f)

    /** 展开层整体淡入：磁贴上的文字不会在第一帧被纯色瞬间盖掉。 */
    val fade = Animatable(0f)

    var isOpen by mutableStateOf(false)
        private set

    var origin by mutableStateOf(Rect.Zero)
        private set

    private var openJob: Job? = null
    private var closeJob: Job? = null
    private var reboundJob: Job? = null

    fun open(bounds: Rect) {
        if (isOpen) return
        origin = bounds
        isOpen = true
        openJob = scope.launch {
            progress.snapTo(0f)
            fade.snapTo(0f)
            // 先让页面完成首次组合（此时窗口还只有磁贴大小、没有任何位移，卡一下也看不出来），
            // 再开始动画：重页面的首次组合不会吃掉动画开头。
            withFrameNanos { }
            withFrameNanos { }
            launch { fade.animateTo(1f, tween(150)) }
            progress.animateTo(1f, tween(OPEN_MS, easing = ExpandEasing))
        }
    }

    /** 收起回磁贴；已在收起时忽略（从当前位置继续，不重播）。 */
    fun close(releaseVelocity: Float = 0f) {
        if (!isOpen || closeJob?.isActive == true) return
        openJob?.cancel()
        reboundJob?.cancel()
        closeJob = scope.launch {
            fade.snapTo(1f)
            progress.animateTo(
                0f,
                // 静止全屏（按钮/返回键）走固定曲线；手势中途松手用弹簧接住松手速度。
                if (progress.value >= 0.999f) tween<Float>(CLOSE_MS, easing = ExpandEasing)
                else spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
                initialVelocity = releaseVelocity.coerceIn(-3f, 0f)
            )
            isOpen = false
        }
    }

    /** 无动画关闭（旋转交接等）。 */
    fun snapClose() {
        openJob?.cancel()
        closeJob?.cancel()
        reboundJob?.cancel()
        isOpen = false
        scope.launch {
            progress.snapTo(0f)
            fade.snapTo(0f)
        }
    }

    /** 手势接管：停掉进行中的开/关/回弹动画。 */
    fun interrupt() {
        openJob?.cancel()
        closeJob?.cancel()
        closeJob = null
        reboundJob?.cancel()
        reboundJob = null
    }

    suspend fun dragTo(value: Float) {
        fade.snapTo(1f)
        progress.snapTo(value.coerceIn(0f, 1f))
    }

    /** 手势取消：弹回全屏。 */
    fun rebound(velocity: Float) {
        if (closeJob?.isActive == true) return
        reboundJob = scope.launch {
            progress.animateTo(
                1f,
                spring(stiffness = Spring.StiffnessMediumLow),
                initialVelocity = velocity.coerceIn(-3f, 3f)
            )
        }
    }
}

/**
 * 磁贴放大展开层：[state] 打开时盖在主界面上，窗口从磁贴矩形生长到全屏，
 * 磁贴色渐变为页面底色，[content]（二级页）随进度淡入并从下方浮上来。
 * 返回手势 / 返回键：窗口沿原路缩回磁贴；页面自己的返回处理（先于本层组合）优先。
 */
@Composable
fun TileExpandOverlay(
    state: TileExpandState,
    background: Color,
    tileColor: Color,
    cornerRadius: Dp,
    content: @Composable BoxScope.() -> Unit
) {
    if (!state.isOpen) return
    val density = LocalDensity.current
    val startCorner = with(density) { cornerRadius.toPx() }
    val liftPx = with(density) { 28.dp.toPx() }
    val origin = state.origin
    // 复用同一个 Path，避免每帧新建（变量名刻意避开 DrawScope.clipPath()）
    val windowPath = remember { Path() }

    // 预测性返回：跟手把窗口缩向磁贴。进度从手势开始时的当前值相对跟踪，
    // 提交后从当前位置继续收起，取消则弹回全屏。
    // 放在页面内容之前组合 = 优先级低于页面自己的返回处理（如文件管理逐级返回上级目录）。
    PredictiveBackHandler { events ->
        var base = Float.NaN
        var p0 = 0f
        var lastMs = 0L
        var lastP = 0f
        var releaseV = 0f
        try {
            events.collect { ev ->
                state.interrupt()
                val nowMs = android.os.SystemClock.elapsedRealtime()
                val p = PredictiveBackEasing.transform(ev.progress).coerceIn(0f, 1f)
                if (base.isNaN()) {
                    base = state.progress.value
                    p0 = p
                }
                val target = (base - (p - p0)).coerceIn(0f, 1f)
                if (lastMs > 0L) {
                    val instV = (target - lastP) / (nowMs - lastMs).coerceAtLeast(1L) * 1000f
                    releaseV = if (releaseV == 0f) instV else releaseV + (instV - releaseV) * 0.45f
                }
                lastMs = nowMs
                lastP = target
                state.dragTo(target)
            }
            state.close(releaseV.coerceIn(-3f, 0f))
        } catch (_: kotlinx.coroutines.CancellationException) {
            state.rebound(releaseV)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = state.progress.value.coerceIn(0f, 1f)
                // 展开初期随 fade 渐入、收起末段随进度渐隐：磁贴上的文字平滑过渡
                alpha = minOf(state.fade.value, p / 0.1f).coerceIn(0f, 1f)
            }
            .drawWithContent {
                val p = state.progress.value.coerceIn(0f, 1f)
                val corner = lerp(startCorner, 0f, p)
                windowPath.reset()
                windowPath.addRoundRect(
                    RoundRect(
                        lerp(origin.left, 0f, p),
                        lerp(origin.top, 0f, p),
                        lerp(origin.right, size.width, p),
                        lerp(origin.bottom, size.height, p),
                        corner,
                        corner
                    )
                )
                clipPath(windowPath) { this@drawWithContent.drawContent() }
            }
            // 裁剪窗口内的不透明底：中间进度时磁贴色层 alpha < 1，没有它会透出背后的主界面。
            .background(background)
            // 展开层盖住整窗：吃掉点击，不让空白处的点击穿透到下面的磁贴。
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        // 磁贴色 → 页面色
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = (1f - state.progress.value / 0.55f).coerceIn(0f, 1f) }
                .background(tileColor)
        )
        // 页面内容：晚一点出现（进度 0.4 起淡入），从下方浮上来
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val c = ((state.progress.value - 0.4f) / 0.4f).coerceIn(0f, 1f)
                    alpha = c
                    translationY = (1f - c) * liftPx
                }
        ) {
            content()
        }
    }
}
