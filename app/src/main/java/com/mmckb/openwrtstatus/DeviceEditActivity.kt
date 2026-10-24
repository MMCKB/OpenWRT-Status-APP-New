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
import androidx.lifecycle.lifecycleScope
import com.mmckb.openwrtstatus.ui.DeviceEditResult
import com.mmckb.openwrtstatus.ui.components.ConnectionToastHost
import com.mmckb.openwrtstatus.ui.loadDeviceContext
import com.mmckb.openwrtstatus.ui.rememberDeviceContext
import com.mmckb.openwrtstatus.ui.screens.DeviceEditScreen
import com.mmckb.openwrtstatus.ui.theme.OpenWrtStatusTheme
import kotlinx.coroutines.launch

/**
 * 设备添加/编辑页（二级页，独立 Activity）：系统返回手势自带 Activity 预测性返回动画。
 *
 * 只接收设备 id 与「是否新增」两个不含凭据的参数，配置本身从本地读取（见
 * [rememberDeviceContext]）——保存结果同样经 [DeviceEditResult] 进程内交接而非
 * `setResult`，凭据不会出现在任何 Intent 里。落库由主界面的 ViewModel 完成。
 */
class DeviceEditActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        val isNew = intent.getBooleanExtra(EXTRA_IS_NEW, true)

        // 旋转到横屏：正在编辑的设备交回主界面右栏内联编辑器继续编辑，本页退出。
        // 进程被杀后直接在横屏恢复时同样交接。配置是异步读的，读完再交接。
        if (savedInstanceState != null &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        ) {
            lifecycleScope.launch {
                val context = loadDeviceContext(
                    context = applicationContext,
                    deviceId = deviceId,
                    fallbackToActive = !isNew
                )
                DeviceEditResult.putHandoff(context.config, isNew)
                handOffToInline()
            }
            return
        }
        setupEdgeToEdge()
        setContent {
            OpenWrtStatusTheme {
                Box(Modifier.fillMaxSize()) {
                    val deviceContext = rememberDeviceContext(deviceId, fallbackToActive = !isNew)
                    if (deviceContext != null) {
                        val initial = deviceContext.config
                        DeviceEditScreen(
                            initial = initial,
                            isNew = isNew,
                            existingNames = deviceContext.devices
                                .filterNot { it.id == initial.id }
                                .map { it.displayName },
                            onCancel = { finish() },
                            onSave = { saved ->
                                DeviceEditResult.putSaved(saved, isNew)
                                setResult(RESULT_OK)
                                finish()
                            },
                            onDelete = {
                                DeviceEditResult.putDeleted(initial.id)
                                setResult(RESULT_OK)
                                finish()
                            }
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
        const val EXTRA_IS_NEW = "isNew"
        const val EXTRA_OPEN_INLINE = "openInline"
    }
}
