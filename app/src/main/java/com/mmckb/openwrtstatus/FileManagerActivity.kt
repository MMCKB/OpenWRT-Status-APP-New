package com.mmckb.openwrtstatus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.ui.screens.FileManagerScreen
import com.mmckb.openwrtstatus.ui.theme.OpenWrtStatusTheme

/**
 * 文件管理页（二级页，独立 Activity）：系统返回手势自带 Activity 预测性返回动画。
 * 活动设备的 SSH 连接信息由主界面通过 [EXTRA_CONFIG] 传入。
 */
class FileManagerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = intent.getSerializableExtra(EXTRA_CONFIG) as? RouterConfig ?: RouterConfig()
        val ssh = SshConfig(
            host = config.sshHost.ifBlank { config.ip },
            port = config.sshPort,
            username = config.sshUsername,
            password = config.sshPassword
        )

        setContent {
            OpenWrtStatusTheme {
                FileManagerScreen(
                    ssh = ssh,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_CONFIG = "config"
    }
}
