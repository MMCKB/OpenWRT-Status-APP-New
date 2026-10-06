package com.mmckb.openwrtstatus

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import com.mmckb.openwrtstatus.data.local.ThemePrefs

/**
 * 按 edge-to-edge 官方规范统一设置系统栏：
 * - enableEdgeToEdge：状态栏/导航栏透明；
 * - 系统栏图标明暗跟随 **App 主题**（主题设置里可脱离系统外观），而非系统日夜模式；
 * - API 29+ 关闭导航栏对比度强制，保证手势条区域完全透明（无边框设计）；
 * - 窗口背景按 App 主题设置——夜间资源（values-night）跟随的是系统外观，
 *   App 深色而系统浅色时冷启动首帧会闪白，这里直接压成主题底色消除。
 *
 * 同时预热主题设置（进程内首次调用同步读 DataStore，后续命中内存），
 * 保证首个 Activity 的首帧就是用户所选主题。
 */
fun androidx.activity.ComponentActivity.setupEdgeToEdge() {
    ThemePrefs.ensureLoaded(this)
    val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val appDark = ThemePrefs.isDarkTheme(systemDark)
    val barStyle = if (appDark) {
        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
    } else {
        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
    }
    enableEdgeToEdge(
        statusBarStyle = barStyle,
        navigationBarStyle = barStyle
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars =
            !appDark
    }
    window.setBackgroundDrawable(
        ColorDrawable(if (appDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    )
}
