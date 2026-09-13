package com.mmckb.openwrtstatus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mmckb.openwrtstatus.ui.screens.AboutScreen
import com.mmckb.openwrtstatus.ui.theme.OpenWrtStatusTheme

/** 关于页（二级页，独立 Activity）：系统返回手势自带 Activity 预测性返回动画。 */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenWrtStatusTheme {
                AboutScreen(onBack = { finish() })
            }
        }
    }
}
