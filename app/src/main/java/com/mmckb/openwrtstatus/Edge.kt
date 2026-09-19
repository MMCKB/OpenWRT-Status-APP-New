package com.mmckb.openwrtstatus

import android.app.Activity
import android.os.Build
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat

/**
 * 按 edge-to-edge 官方规范统一设置系统栏：
 * - enableEdgeToEdge：状态栏/导航栏透明，图标颜色随主题自动切换；
 * - API 29+ 关闭导航栏对比度强制，保证手势条区域完全透明（无边框设计）。
 */
fun Activity.setupEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = androidx.activity.SystemBarStyle.auto(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT
        ),
        navigationBarStyle = androidx.activity.SystemBarStyle.auto(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT
        )
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars =
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}
