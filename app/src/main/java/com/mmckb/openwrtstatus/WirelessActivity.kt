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
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.ui.components.ConnectionToastHost
import com.mmckb.openwrtstatus.ui.screens.WirelessScreen
import com.mmckb.openwrtstatus.ui.theme.OpenWrtStatusTheme

/**
 * 无线设置页（二级页，独立 Activity）：系统返回手势自带预测性返回动画。
 * 旋转到横屏时交回主界面右栏内联渲染（同其他二级页的交接模式）。
 */
class WirelessActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = intent.getSerializableExtra(EXTRA_CONFIG) as? RouterConfig ?: RouterConfig()

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
                    WirelessScreen(
                        config = config,
                        onBack = { finish() }
                    )
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
        const val EXTRA_CONFIG = "config"
        const val EXTRA_OPEN_INLINE = "openInline"
    }
}
