package com.mmckb.openwrtstatus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mmckb.openwrtstatus.ui.AppRoot
import com.mmckb.openwrtstatus.ui.theme.OpenWrtStatusTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge per the Android guide: draw behind system bars, handle insets
        // with Compose modifiers (statusBarsPadding / navigationBarsPadding / imePadding).
        enableEdgeToEdge()
        setContent {
            OpenWrtStatusTheme {
                AppRoot()
            }
        }
    }
}
