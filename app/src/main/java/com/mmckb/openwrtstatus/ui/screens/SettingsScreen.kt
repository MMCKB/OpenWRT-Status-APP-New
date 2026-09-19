package com.mmckb.openwrtstatus.ui.screens

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmckb.openwrtstatus.BackgroundEditActivity
import com.mmckb.openwrtstatus.notify.AppNotifier
import com.mmckb.openwrtstatus.ui.RouterViewModel
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.rememberTopBarPadding
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import com.mmckb.openwrtstatus.ui.theme.copyPickedImageToBackground

/**
 * Settings offers the custom background, the realtime speed-notification toggle and
 * a single "关于" entry.
 */
@Composable
fun SettingsScreen(
    viewModel: RouterViewModel,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val speedNotify by viewModel.speedNotificationEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 背景图状态：选择/调整后返回时通过刷新键重读。
    var bgRefresh by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableIntStateOf(0)
    }
    val store = androidx.compose.runtime.remember { com.mmckb.openwrtstatus.data.local.SettingsStore(context) }
    val bgEnabled = store.isBackgroundEnabled() && bgRefresh >= 0
    val bgExists = store.hasBackgroundImage() && bgRefresh >= 0

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && copyPickedImageToBackground(context, uri)) {
            bgRefresh++
            context.startActivity(Intent(context, BackgroundEditActivity::class.java))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = rememberTopBarPadding())
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "自定义背景图",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Text(
                        if (bgExists) "已选择背景图，可继续调整或应用" else "选择一张图片作为应用背景",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Switch(
                    checked = bgEnabled,
                    onCheckedChange = { enabled ->
                        store.saveBackgroundEnabled(enabled)
                        bgRefresh++
                    },
                    enabled = bgExists
                )
            }
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("选择图片") }
                TextButton(
                    onClick = { context.startActivity(Intent(context, BackgroundEditActivity::class.java)) },
                    enabled = bgExists
                ) { Text("模糊与调整") }
            }
        }
        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "实时网速通知",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Text(
                        "在系统通知栏以 Live Update 实时显示当前设备的上下行网速",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Switch(
                    checked = speedNotify,
                    onCheckedChange = { enabled ->
                        viewModel.setSpeedNotificationEnabled(enabled)
                        if (enabled) (context as? ComponentActivity)?.let {
                            AppNotifier.requestPermission(it)
                        }
                    }
                )
            }
        }
        AppCard(modifier = Modifier.clip(AppShapes.card).clickable(onClick = onOpenAbout)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "关于",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Text(
                        "版本、开源许可与项目信息",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}

