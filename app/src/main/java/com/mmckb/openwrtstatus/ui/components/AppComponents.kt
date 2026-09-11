package com.mmckb.openwrtstatus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

/**
 * A rounded card using the custom [AppShapes.card] token and the [LocalAppColors] palette.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalAppColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.card,
        color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline.copy(alpha = 0.6f)),
        tonalElevation = 1.dp,
        shadowElevation = 3.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
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
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface
        )
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * A floating, glass (liquid-glass / backdrop) bottom tab bar.
 *
 * The glass effect samples whatever content is rendered behind it via the supplied [backdrop]
 * (see [com.kyant.backdrop.backdrops.rememberLayerBackdrop] + `Modifier.layerBackdrop`).
 *
 * Effects require Android 12 (API 31) for blur/vibrancy and Android 13 (API 33) for the lens
 * refraction; on older versions the surface gracefully degrades to a translucent capsule.
 */
data class TabItem(
    val label: String,
    val icon: ImageVector
)

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

    Row(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(16.dp.toPx())
                    lens(24.dp.toPx(), 24.dp.toPx())
                },
                onDrawSurface = { drawRect(containerColor) }
            )
            .height(64.dp)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            Column(
                modifier = Modifier
                    .clip(Capsule())
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = { onTabSelected(index) }
                    )
                    .fillMaxHeight()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = if (selected) colors.accent else colors.onSurfaceVariant
                )
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) colors.accent else colors.onSurfaceVariant
                )
            }
        }
    }
}
