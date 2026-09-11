package com.mmckb.openwrtstatus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mmckb.openwrtstatus.ui.AppRoot
import com.mmckb.openwrtstatus.ui.theme.OpenWrtStatusTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenWrtStatusTheme {
                AppRoot()
            }
        }
    }
}
