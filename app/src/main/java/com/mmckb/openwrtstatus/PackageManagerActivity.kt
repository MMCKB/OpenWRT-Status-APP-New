package com.mmckb.openwrtstatus

import android.os.Bundle
import androidx.activity.ComponentActivity

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.ui.components.ConnectionToastHost
import com.mmckb.openwrtstatus.ui.screens.PackageManagerScreen
import com.mmckb.openwrtstatus.ui.theme.OpenWrtStatusTheme

/**
 * 软件包管理页（二级页，独立 Activity）：系统返回手势自带预测性返回动画。
 * 活动设备的 SSH 连接信息由主界面通过 [EXTRA_CONFIG] 传入。
 */
class PackageManagerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = intent.getSerializableExtra(EXTRA_CONFIG) as? RouterConfig ?: RouterConfig()
        val ssh = SshConfig(
            host = config.sshHost.ifBlank { config.ip },
            port = config.sshPort,
            username = config.sshUsername,
            password = config.sshPassword
        )

        setupEdgeToEdge()
        setContent {
            OpenWrtStatusTheme {
                Box(Modifier.fillMaxSize()) {
                    PackageManagerScreen(
                        ssh = ssh,
                        sshEnabled = config.sshEnabled,
                        onBack = { finish() }
                    )
                    ConnectionToastHost(Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }

    companion object {
        const val EXTRA_CONFIG = "config"
    }
}
