package com.mmckb.openwrtstatus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import com.mmckb.openwrtstatus.ui.screens.DashboardScreen
import com.mmckb.openwrtstatus.ui.screens.SettingsScreen
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

@Composable
fun AppRoot(viewModel: RouterViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val config by viewModel.config.collectAsState()
    val colors = LocalAppColors.current

    // Records whatever content is rendered behind the floating glass tab.
    val backdrop = rememberLayerBackdrop()

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                title = if (selectedTab == 0) "OpenWrt 状态" else "设置",
                actions = {
                    if (selectedTab == 0) {
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
                    0 -> DashboardScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                    1 -> SettingsScreen(
                        config = config,
                        onSave = {
                            viewModel.saveConfig(it)
                            selectedTab = 0
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        FloatingTabBar(
            backdrop = backdrop,
            tabs = listOf(
                TabItem("概览", Icons.Filled.Dashboard),
                TabItem("设置", Icons.Filled.Settings)
            ),
            selectedIndex = selectedTab,
            onTabSelected = { selectedTab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
}
