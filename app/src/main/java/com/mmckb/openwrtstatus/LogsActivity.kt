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
import com.mmckb.openwrtstatus.ui.rememberDeviceConfig
import com.mmckb.openwrtstatus.ui.screens.LogsScreen
import com.mmckb.openwrtstatus.ui.theme.OpenWrtStatusTheme

/**
 * 日志页（二级页，独立 Activity）：系统返回手势自带预测性返回动画。
 * 连接配置按设备 id 从本地读取（见 [rememberDeviceConfig]），不经 Intent 传递。
 */
class LogsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)

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
                    val config = rememberDeviceConfig(deviceId)
                    if (config != null) {
                        LogsScreen(
                            config = config,
                            sshEnabled = config.sshEnabled,
                            onBack = { finish() }
                        )
                    }
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
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_OPEN_INLINE = "openInline"
    }
}
