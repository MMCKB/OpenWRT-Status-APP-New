package com.mmckb.openwrtstatus.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.mmckb.openwrtstatus.data.local.AppThemeMode
import com.mmckb.openwrtstatus.data.local.ThemePrefs

/**
 * Application-specific color palette.
 *
 * A lightweight design system built on top of Compose Foundation (no Material3 theme
 * dependency for the app chrome). [MaterialTheme] is still wrapped underneath so that the
 * material3 input controls used across the screens (device form, dialogs) keep working.
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

/**
 * App 主题当前是否为深色（含主题设置的模式覆盖，与系统外观解耦）。
 * 玻璃层等按明暗取色的地方用它在模式覆盖时也能取对色。
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

fun lightAppColors() = AppColors(
    background = Color(0xFFFFFFFF),
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

/**
 * AMOLED 纯黑（深色模式的变体，主题设置页开关）：背景与卡片压成纯黑，
 * 中性面按灰阶逐级抬升，文字与强调色沿用深色板——OLED 屏省电，夜间观感更沉。
 */
fun amoledDarkAppColors() = AppColors(
    background = Color(0xFF000000),
    surface = Color(0xFF050505),
    surfaceVariant = Color(0xFF121212),
    onSurface = Color(0xFFE6E8EB),
    onSurfaceVariant = Color(0xFFA0A6AD),
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF0A2540),
    accent = Color(0xFF0091FF),
    success = Color(0xFF66BB6A),
    error = Color(0xFFEF5350),
    outline = Color(0xFF262626)
)

/** Shape tokens for the custom design system. */
object AppShapes {
    /** Large container card used as the primary layout unit. */
    val card = RoundedCornerShape(24.dp)
    /** Secondary blocks nested inside a card (tiles, chips). */
    val block = RoundedCornerShape(14.dp)
    val pill = RoundedCornerShape(999.dp)
}

/**
 * App 主题入口：深浅色模式与 AMOLED 由主题设置（进程级 [ThemePrefs]）决定，
 * 所有 Activity 都无参调用本函数，任一页面改设置即全部即时换色。
 *
 * 页面底色（含状态栏与手势区背后）由主题层统一绘制：竖屏二级 Activity 页此前
 * 依赖只在 onCreate 设置一次的窗口背景，主题运行时切换时底色不跟随、且与
 * 主题实际背景色有色差（纯黑 vs 深色板）；主题层绘制后底色始终正确并即时
 * 跟随切换，与 AppRoot 内联页的自绘背景行为一致（同色叠加，视觉不变）。
 * 窗口背景仅在冷启动首帧（Compose 绘制前）可见，由 setupEdgeToEdge 按主题设置。
 * 系统栏图标明暗同步跟随 App 主题（运行时切换由 SideEffect 更新）。
 */
@Composable
fun OpenWrtStatusTheme(content: @Composable () -> Unit) {
    val prefs by ThemePrefs.state.collectAsState()
    val darkTheme = when (prefs.mode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val appColors = when {
        !darkTheme -> lightAppColors()
        prefs.amoled -> amoledDarkAppColors()
        else -> darkAppColors()
    }
    val materialColorScheme = when {
        !darkTheme -> LightColorScheme
        prefs.amoled -> AmoledColorScheme
        else -> DarkColorScheme
    }

    Box(Modifier.fillMaxSize().background(appColors.background)) {
        MaterialTheme(
            colorScheme = materialColorScheme
        ) {
            CompositionLocalProvider(
                LocalAppColors provides appColors,
                LocalDarkTheme provides darkTheme,
                content = content
            )
        }
    }

    // 模式覆盖与系统外观不一致时，系统栏图标需按 App 主题重设
    //（setupEdgeToEdge 只在 onCreate 设一次，运行时切换在这里补）。
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
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

private val AmoledColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFF80CBC4),
    tertiary = Color(0xFFCE93D8),
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF121212),
    outline = Color(0xFF262626)
)
