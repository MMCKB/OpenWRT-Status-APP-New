package com.mmckb.openwrtstatus.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * 磁贴点击起点登记处。
 *
 * 磁贴（工具页的两列磁贴 / 列表卡片、设置页的入口卡片）被点击时，把自己在窗口里的矩形
 * 记到这里；随后 AppRoot 的 openSecondary 取走（[take]）并用它作为二级页「放大展开」
 * 动画的起点。只在主线程读写：点击回调与 openSecondary 在同一次点击里先后执行。
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
