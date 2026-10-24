package com.mmckb.openwrtstatus

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mmckb.openwrtstatus.data.local.SettingsStore
import com.mmckb.openwrtstatus.ui.components.ConnectionToastHost
import com.mmckb.openwrtstatus.ui.screens.AboutScreen
import com.mmckb.openwrtstatus.ui.theme.OpenWrtStatusTheme
import kotlinx.coroutines.launch

/** 关于页（二级页，独立 Activity）：系统返回手势自带 Activity 预测性返回动画。 */
class AboutActivity : ComponentActivity() {
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
                    // 解锁状态读本地（DataStore 异步加载）；解锁时写入并立即生效——
                    // 主界面在 aboutLauncher 回调里重读，跨 Activity 也能生效
                    val store = remember { SettingsStore(applicationContext) }
                    val scope = rememberCoroutineScope()
                    var unlocked by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { unlocked = store.isHiddenDiagUnlocked() }
                    AboutScreen(
                        onBack = { finish() },
                        hiddenDiagUnlocked = unlocked,
                        onUnlockHiddenDiag = {
                            scope.launch { store.saveHiddenDiagUnlocked(true) }
                            unlocked = true
                        }
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
        const val EXTRA_OPEN_INLINE = "openInline"
    }
}
