package com.mmckb.openwrtstatus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.mmckb.openwrtstatus.ui.components.AppTopBar
import com.mmckb.openwrtstatus.ui.components.FloatingTabBar
import com.mmckb.openwrtstatus.ui.components.TabItem
import com.mmckb.openwrtstatus.ui.screens.AboutScreen
import com.mmckb.openwrtstatus.ui.screens.DashboardScreen
import com.mmckb.openwrtstatus.ui.screens.DevicesScreen
import com.mmckb.openwrtstatus.ui.screens.MonitorScreen
import com.mmckb.openwrtstatus.ui.screens.TerminalScreen
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

private const val TAB_DASHBOARD = 0
private const val TAB_DEVICES = 1
private const val TAB_MONITOR = 2
private const val TAB_TERMINAL = 3
private const val TAB_SETTINGS = 4

@Composable
fun AppRoot(viewModel: RouterViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(TAB_DASHBOARD) }
    val config by viewModel.config.collectAsState()
    val colors = LocalAppColors.current

    // Records whatever content is rendered behind the floating glass tab.
    val backdrop = rememberLayerBackdrop()

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = when (selectedTab) {
                    TAB_DASHBOARD -> "概览"
                    TAB_DEVICES -> "设备"
                    TAB_MONITOR -> "监控"
                    TAB_TERMINAL -> "终端"
                    else -> "关于"
                },
                subtitle = when (selectedTab) {
                    TAB_DASHBOARD -> "${config.username}@${config.ip}:${config.port}"
                    TAB_TERMINAL -> "${config.sshUsername}@${config.sshHost.ifBlank { config.ip }}:${config.sshPort}"
                    else -> null
                },
                actions = {
                    if (selectedTab == TAB_DASHBOARD) {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    }
                }
            )

            // Content area. layerBackdrop() records this layer so the floating tab can sample it.
            Box(
                Modifier
                    .weight(1f)
                    .layerBackdrop(backdrop)
            ) {
                when (selectedTab) {
                    TAB_DASHBOARD -> DashboardScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                    TAB_DEVICES -> DevicesScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                    TAB_MONITOR -> MonitorScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                    TAB_TERMINAL -> TerminalScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> AboutScreen(modifier = Modifier.fillMaxSize())
                }
            }
        }

        FloatingTabBar(
            backdrop = backdrop,
            tabs = listOf(
                TabItem("概览", Icons.Filled.Dashboard),
                TabItem("设备", Icons.Filled.Devices),
                TabItem("监控", Icons.Filled.ShowChart),
                TabItem("终端", Icons.Filled.Terminal),
                TabItem("设置", Icons.Filled.Settings)
            ),
            selectedIndex = selectedTab,
            onTabSelected = { selectedTab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp)
        )
    }
}
