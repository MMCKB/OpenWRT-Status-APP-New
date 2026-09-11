package com.mmckb.openwrtstatus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Application-specific color palette.
 *
 * A lightweight design system built on top of Compose Foundation (no Material3 theme
 * dependency for the app chrome). [MaterialTheme] is still wrapped underneath so that the
 * material3 input controls used by [com.mmckb.openwrtstatus.ui.screens.SettingsScreen]
 * keep working.
 */
data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val accent: Color,
    val success: Color,
    val error: Color,
    val outline: Color
)

val LocalAppColors = staticCompositionLocalOf { lightAppColors() }

fun lightAppColors() = AppColors(
    background = Color(0xFFF4F6FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF1F6),
    onSurface = Color(0xFF1A1C1E),
    onSurfaceVariant = Color(0xFF565B62),
    primary = Color(0xFF1565C0),
    onPrimary = Color(0xFFFFFFFF),
    accent = Color(0xFF0088FF),
    success = Color(0xFF2E7D32),
    error = Color(0xFFC62828),
    outline = Color(0xFFD8DEE6)
)

fun darkAppColors() = AppColors(
    background = Color(0xFF0F1115),
    surface = Color(0xFF1A1D23),
    surfaceVariant = Color(0xFF262A31),
    onSurface = Color(0xFFE6E8EB),
    onSurfaceVariant = Color(0xFFA0A6AD),
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF0A2540),
    accent = Color(0xFF0091FF),
    success = Color(0xFF66BB6A),
    error = Color(0xFFEF5350),
    outline = Color(0xFF33383F)
)

/** Shape tokens for the custom design system. */
object AppShapes {
    /** Large container card used as the primary layout unit. */
    val card = RoundedCornerShape(24.dp)
    /** Secondary blocks nested inside a card (tiles, chips). */
    val block = RoundedCornerShape(14.dp)
    val pill = RoundedCornerShape(999.dp)
}

@Composable
fun OpenWrtStatusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val materialColorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val appColors = if (darkTheme) darkAppColors() else lightAppColors()

    MaterialTheme(
        colorScheme = materialColorScheme
    ) {
        CompositionLocalProvider(
            LocalAppColors provides appColors,
            content = content
        )
    }
}

// Material3 fallback schemes kept so material3 components (Settings inputs, dialogs) render.
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    secondary = Color(0xFF00897B),
    tertiary = Color(0xFF6A1B9A)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFF80CBC4),
    tertiary = Color(0xFFCE93D8)
)
