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
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.ui.components.ConnectionToastHost
import com.mmckb.openwrtstatus.ui.rememberDeviceConfig
import com.mmckb.openwrtstatus.ui.screens.PackageManagerScreen
import com.mmckb.openwrtstatus.ui.theme.OpenWrtStatusTheme

/**
 * 软件包管理页（二级页，独立 Activity）：系统返回手势自带预测性返回动画。
 * SSH 连接信息按设备 id 从本地读取（见 [rememberDeviceConfig]），不经 Intent 传递。
 */
class PackageManagerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)

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
                    val config = rememberDeviceConfig(deviceId)
                    if (config != null) {
                        val ssh = SshConfig(
                            host = config.sshHost.ifBlank { config.ip },
                            port = config.sshPort,
                            username = config.sshUsername,
                            password = config.sshPassword
                        )
                        PackageManagerScreen(
                            ssh = ssh,
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
