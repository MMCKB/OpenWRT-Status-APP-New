package com.mmckb.openwrtstatus.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmckb.openwrtstatus.DeviceEditActivity
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.ui.RouterViewModel
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.components.CardSectionTitle
import com.mmckb.openwrtstatus.ui.components.rememberTopBarPadding
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

/**
 * 设备管理（最初版形态）：单张「设备列表」卡片，每台设备一行，行内编辑/删除图标。
 * 点击行切换当前设备；删除有二次确认弹窗。
 * 编辑/添加跳转到独立的 DeviceEditActivity，结果经 ActivityResult 回传后落库。
 */
@Composable
fun DevicesScreen(
    viewModel: RouterViewModel,
    modifier: Modifier = Modifier
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<RouterConfig?>(null) }
    val context = LocalContext.current

    val editLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val deletedId = result.data?.getStringExtra(DeviceEditActivity.EXTRA_DELETE_ID)
        if (deletedId != null) {
            viewModel.deleteDevice(deletedId)
            return@rememberLauncherForActivityResult
        }
        val saved = result.data?.getSerializableExtra(DeviceEditActivity.EXTRA_SAVED) as? RouterConfig
        if (saved != null) {
            val isNew = result.data?.getBooleanExtra(DeviceEditActivity.EXTRA_IS_NEW, false) ?: false
            if (isNew) viewModel.addDevice(saved) else viewModel.updateDevice(saved)
        }
    }

    fun launchEditor(device: RouterConfig, isNew: Boolean) {
        val intent = Intent(context, DeviceEditActivity::class.java).apply {
            putExtra(DeviceEditActivity.EXTRA_DEVICE, device)
            putExtra(DeviceEditActivity.EXTRA_IS_NEW, isNew)
            putStringArrayListExtra(
                DeviceEditActivity.EXTRA_EXISTING,
                ArrayList(devices.filterNot { it.id == device.id }.map { it.displayName })
            )
        }
        editLauncher.launch(intent)
    }

    val colors = LocalAppColors.current

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = rememberTopBarPadding(), bottom = 96.dp)
    ) {
        item {
            AppCard {
                CardSectionTitle("设备列表（${devices.size}）")
                Spacer(Modifier.height(8.dp))
                if (devices.isEmpty()) {
                    Text(
                        "还没有设备。点击下方「添加设备」，填入路由器地址与账号即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                devices.forEach { device ->
                    DeviceRow(
                        device = device,
                        active = device.id == activeId,
                        onClick = { viewModel.selectDevice(device.id) },
                        onEdit = { launchEditor(device, isNew = false) },
                        onDelete = { pendingDelete = device }
                    )
                }
            }
        }
        item {
            Button(
                onClick = { launchEditor(RouterConfig(), isNew = true) },
                shape = AppShapes.card,
                modifier = Modifier.fillMaxWidth()
            ) { Text("添加设备") }
        }
        item {
            Text(
                "点击设备即可切换当前连接；概览、监控与终端都作用于当前设备。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }

    pendingDelete?.let { target ->
        AppDialog(
            title = "删除设备",
            message = "确定要删除「${target.displayName}」吗？删除后需要重新添加才能连接该路由器。",
            confirmLabel = "删除",
            confirmColor = colors.error,
            onConfirm = {
                viewModel.deleteDevice(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun DeviceRow(
    device: RouterConfig,
    active: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(if (active) colors.success else colors.onSurfaceVariant, CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                device.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Text(
                "${device.ip}:${device.port} · ${device.username}" + if (active) " · 当前" else "",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = colors.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = colors.onSurfaceVariant)
        }
    }
}
