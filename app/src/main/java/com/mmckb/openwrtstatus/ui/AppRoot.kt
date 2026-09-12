package com.mmckb.openwrtstatus.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.mmckb.openwrtstatus.data.ssh.SshTerminal
import com.mmckb.openwrtstatus.ui.components.AppTopBar
import com.mmckb.openwrtstatus.ui.components.PredictiveBackEasing
import com.mmckb.openwrtstatus.ui.components.FloatingTabBar
import com.mmckb.openwrtstatus.ui.components.TabItem
import com.mmckb.openwrtstatus.ui.screens.AboutScreen
import com.mmckb.openwrtstatus.ui.screens.DashboardScreen
import com.mmckb.openwrtstatus.ui.screens.DetailScreen
import com.mmckb.openwrtstatus.ui.screens.DevicesScreen
import com.mmckb.openwrtstatus.ui.screens.SettingsScreen
import com.mmckb.openwrtstatus.ui.screens.TerminalScreen
import com.mmckb.openwrtstatus.ui.screens.ToolScreen
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

private const val TAB_DASHBOARD = 0
private const val TAB_DEVICES = 1
private const val TAB_TERMINAL = 2
private const val TAB_TOOL = 3
private const val TAB_DETAIL = 4
private const val TAB_SETTINGS = 5

@Composable
fun AppRoot(viewModel: RouterViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(TAB_DASHBOARD) }
    var showAbout by remember { mutableStateOf(false) }
    var deviceFormOpen by remember { mutableStateOf(false) }
    var aboutBackProgress by remember { mutableFloatStateOf(0f) }
    val config by viewModel.config.collectAsState()
    val colors = LocalAppColors.current

    // A secondary page (about / device form) is a standalone view: the tab bar and the
    // bottom blur strip hide while it is open, and system back returns one level up
    // with the predictive back gesture.
    val secondaryOpen = showAbout || deviceFormOpen

    // Records the page layer: pages extend edge to edge, so the translucent top bar and
    // the bottom tab strip blur the live content behind them.
    val backdrop = rememberLayerBackdrop()

    Box(Modifier.fillMaxSize().background(colors.background)) {
        // Page layer fills the whole screen; the top bar overlays it.
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            if (showAbout) {
                // Material predictive back spec: the outgoing surface scales to 90% and
                // fades out by the 35% threshold while the page behind fades in.
                val eased = PredictiveBackEasing.transform(aboutBackProgress)
                SettingsScreen(
                    onOpenAbout = { showAbout = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = ((eased - 0.35f) / 0.65f).coerceIn(0f, 1f)
                        }
                )
                AboutScreen(
                    onBack = { showAbout = false },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1f - 0.1f * eased
                            scaleY = 1f - 0.1f * eased
                            alpha = (1f - eased / 0.35f).coerceIn(0f, 1f)
                        }
                )
            } else {
                when (selectedTab) {
                    TAB_DASHBOARD -> DashboardScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                    TAB_DEVICES -> DevicesScreen(
                        viewModel = viewModel,
                        onSecondaryPageChanged = { deviceFormOpen = it },
                        modifier = Modifier.fillMaxSize()
                    )
                    TAB_TERMINAL -> TerminalScreen(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                    TAB_TOOL -> ToolScreen(modifier = Modifier.fillMaxSize())
                    TAB_DETAIL -> DetailScreen(modifier = Modifier.fillMaxSize())
                    else -> SettingsScreen(
                        onOpenAbout = { showAbout = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Top bar: a plain Gaussian blur of the live content - no tint on top of it.
        // Hidden on secondary pages: those are standalone views with their own headers.
        if (!secondaryOpen) {
            AppTopBar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = { blur(18.dp.toPx()) }
                    ),
                title = when (selectedTab) {
                    TAB_DASHBOARD -> "概览"
                    TAB_DEVICES -> "设备"
                    TAB_TERMINAL -> "终端"
                    TAB_TOOL -> "工具"
                    TAB_DETAIL -> "详情"
                    else -> "设置"
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
                    if (selectedTab == TAB_TERMINAL) {
                        val terminalState by viewModel.terminal.state.collectAsState()
                        val terminalConnected = terminalState is SshTerminal.State.Connected
                        TextButton(
                            onClick = {
                                if (terminalConnected) viewModel.disconnectSsh() else viewModel.connectSsh()
                            },
                            enabled = config.sshEnabled && terminalState !is SshTerminal.State.Connecting
                        ) {
                            Text(if (terminalConnected) "断开" else "连接", color = colors.primary)
                        }
                    }
                }
            )
        }

        // Gaussian blur strip below the tab pill area (hidden on secondary pages).
        if (!secondaryOpen) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .height(88.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = { blur(16.dp.toPx()) }
                    )
            )
        }

        if (!secondaryOpen) {
            FloatingTabBar(
                backdrop = backdrop,
                tabs = listOf(
                    TabItem("概览", Icons.Filled.Dashboard),
                    TabItem("设备", Icons.Filled.Devices),
                    TabItem("终端", Icons.Filled.Terminal),
                    TabItem("工具", Icons.Filled.Build),
                    TabItem("详情", Icons.Filled.Info),
                    TabItem("设置", Icons.Filled.Settings)
                ),
                selectedIndex = selectedTab,
                onTabSelected = {
                    selectedTab = it
                    showAbout = false
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 22.dp)
            )
        }
    }

    // About page: predictive back - the page tracks the gesture and returns on commit.
    if (showAbout) {
        PredictiveBackHandler { events ->
            try {
                events.collect { aboutBackProgress = it.progress }
                showAbout = false
            } catch (_: kotlinx.coroutines.CancellationException) {
            } finally {
                aboutBackProgress = 0f
            }
        }
    }
}
