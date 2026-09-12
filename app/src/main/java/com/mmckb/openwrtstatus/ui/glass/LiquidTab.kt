package com.mmckb.openwrtstatus.ui.glass

/*
 * Adapted from Kyant0/AndroidLiquidGlass (project "Backdrop"), tag 1.0.6,
 * file catalog/.../components/LiquidBottomTab.kt — Apache License 2.0.
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule

/** Scale applied to the tab content while the glass pill is pressed. */
val LocalLiquidTabScale = staticCompositionLocalOf { { 1f } }

/**
 * A single tab inside the glass bar.
 *
 * [onClick] is nullable on purpose: the tab bar renders this composable twice — once visibly
 * and once in an invisible layer that only records pixels for the moving pill. The invisible
 * copy must pass `null`, otherwise its hit area sits on top of the visible layer and swallows
 * every tap (`alpha(0f)` does not disable hit testing in Compose).
 */
@Composable
fun RowScope.LiquidTab(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidTabScale.current
    val clickableModifier = if (onClick != null) {
        Modifier
            .clip(Capsule())
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
    } else {
        Modifier
    }
    Column(
        modifier
            .then(clickableModifier)
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}
