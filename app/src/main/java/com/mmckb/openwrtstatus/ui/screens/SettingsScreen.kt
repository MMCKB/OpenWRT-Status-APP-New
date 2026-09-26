package com.mmckb.openwrtstatus.ui.screens

import androidx.activity.ComponentActivity
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
import androidx.compose.material3.Text
import com.mmckb.openwrtstatus.ui.components.AppSwitch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmckb.openwrtstatus.notify.AppNotifier
import com.mmckb.openwrtstatus.ui.RouterViewModel
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.rememberTopBarPadding
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

/**
 * Settings offers the realtime speed-notification toggle and a single "关于" entry;
 * the version, repository and license details live behind the latter.
 */
@Composable
fun SettingsScreen(
    viewModel: RouterViewModel,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val connNotify by viewModel.connNotifyEnabled.collectAsStateWithLifecycle()
    val toolsGrid by viewModel.toolsGridEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                        "连接状态通知",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Text(
                        "路由器连接或断开时发送通知，已连接通知实时显示连接时长",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                AppSwitch(
                    checked = connNotify,
                    onCheckedChange = { enabled ->
                        viewModel.setConnectionNotifyEnabled(enabled)
                        if (enabled) (context as? ComponentActivity)?.let {
                            AppNotifier.requestPermission(it)
                        }
                    }
                )
            }
        }
        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "工具页两列磁贴",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Text(
                        "开启后工具页以两列磁贴显示；关闭为列表卡片",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                AppSwitch(
                    checked = toolsGrid,
                    onCheckedChange = { viewModel.setToolsGridEnabled(it) }
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
