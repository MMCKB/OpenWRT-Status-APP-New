package com.mmckb.openwrtstatus

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mmckb.openwrtstatus.ui.components.ConnectionToastHost
import com.mmckb.openwrtstatus.ui.screens.ThemeSettingsScreen
import com.mmckb.openwrtstatus.ui.theme.OpenWrtStatusTheme

/** 主题设置页（二级页，独立 Activity）：深浅色模式与 AMOLED 纯黑。 */
class ThemeSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 旋转到横屏：二级页交回主界面右栏内联渲染（左侧出一级页），本页退出。
        // 进程被杀后直接在横屏恢复时同样交接。
        if (savedInstanceState != null &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        ) {
            handOffToInline()
            return
        }
        setupEdgeToEdge()
        setContent {
            OpenWrtStatusTheme {
                Box(Modifier.fillMaxSize()) {
                    ThemeSettingsScreen(onBack = { finish() })
                    ConnectionToastHost(Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }

    private fun handOffToInline() {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN_INLINE, true))
        finish()
    }

    companion object {
        const val EXTRA_OPEN_INLINE = "openInline"
    }
}
