package com.mmckb.openwrtstatus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.ui.components.ConnectionToastHost
import com.mmckb.openwrtstatus.ui.screens.DeviceEditScreen
import com.mmckb.openwrtstatus.ui.theme.OpenWrtStatusTheme

/**
 * 设备添加/编辑页（二级页，独立 Activity）：系统返回手势自带 Activity 预测性返回动画。
 * 保存/删除通过 setResult 把结果回传给主界面，由主界面的 ViewModel 落库。
 */
class DeviceEditActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = intent.getSerializableExtra(EXTRA_DEVICE) as? RouterConfig ?: RouterConfig()
        val isNew = intent.getBooleanExtra(EXTRA_IS_NEW, true)
        val existingNames = intent.getStringArrayListExtra(EXTRA_EXISTING) ?: arrayListOf()

        enableEdgeToEdge()
        setContent {
            OpenWrtStatusTheme {
                Box(Modifier.fillMaxSize()) {
                    DeviceEditScreen(
                        initial = initial,
                        isNew = isNew,
                        existingNames = existingNames,
                        onCancel = { finish() },
                        onSave = { saved ->
                            setResult(RESULT_OK, Intent().apply {
                                putExtra(EXTRA_SAVED, saved)
                                putExtra(EXTRA_IS_NEW, isNew)
                            })
                            finish()
                        },
                        onDelete = {
                            setResult(RESULT_OK, Intent().apply {
                                putExtra(EXTRA_DELETE_ID, initial.id)
                            })
                            finish()
                        }
                    )
                    ConnectionToastHost(Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }

    companion object {
        const val EXTRA_DEVICE = "device"
        const val EXTRA_IS_NEW = "isNew"
        const val EXTRA_EXISTING = "existingNames"
        const val EXTRA_SAVED = "saved"
        const val EXTRA_DELETE_ID = "deleteId"
    }
}
