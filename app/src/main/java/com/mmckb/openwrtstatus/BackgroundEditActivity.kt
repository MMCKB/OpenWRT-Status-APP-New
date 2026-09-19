package com.mmckb.openwrtstatus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mmckb.openwrtstatus.ui.screens.BackgroundEditScreen
import com.mmckb.openwrtstatus.ui.theme.AppBackground
import com.mmckb.openwrtstatus.ui.theme.OpenWrtStatusTheme

/**
 * 背景调整页（二级页，独立 Activity）：系统返回手势自带预测性返回动画；
 * 直接返回即放弃未应用的调整。
 */
class BackgroundEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenWrtStatusTheme {
                AppBackground {
                    BackgroundEditScreen(
                        onBack = { finish() },
                        onApplied = { finish() },
                        onRemoved = { finish() }
                    )
                }
            }
        }
    }
}
