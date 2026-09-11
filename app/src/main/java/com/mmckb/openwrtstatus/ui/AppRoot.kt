package com.mmckb.openwrtstatus.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mmckb.openwrtstatus.ui.screens.DashboardScreen
import com.mmckb.openwrtstatus.ui.screens.SettingsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(viewModel: RouterViewModel = viewModel()) {
    var screen by remember { mutableStateOf("dashboard") }
    val config by viewModel.config.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (screen == "dashboard") "OpenWrt 状态" else "设置") },
                actions = {
                    if (screen == "dashboard") {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                        IconButton(onClick = { screen = "settings" }) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    } else {
                        IconButton(onClick = { screen = "dashboard" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when (screen) {
            "dashboard" -> DashboardScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
            "settings" -> SettingsScreen(
                config = config,
                onSave = {
                    viewModel.saveConfig(it)
                    screen = "dashboard"
                },
                modifier = Modifier.padding(padding)
            )
        }
    }
}
