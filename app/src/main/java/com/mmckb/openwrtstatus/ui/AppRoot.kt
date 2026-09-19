package com.mmckb.openwrtstatus.ui

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.highlight.Highlight
import com.mmckb.openwrtstatus.AboutActivity
import com.mmckb.openwrtstatus.DeviceEditActivity
import com.mmckb.openwrtstatus.FileManagerActivity
import com.mmckb.openwrtstatus.PackageManagerActivity
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshTerminal
import com.mmckb.openwrtstatus.ui.components.AppTopBar
import com.mmckb.openwrtstatus.ui.components.ConnectionToastHost
import com.mmckb.openwrtstatus.ui.components.FloatingTabBar
import com.mmckb.openwrtstatus.ui.components.TabItem
import com.mmckb.openwrtstatus.ui.screens.AboutScreen
import com.mmckb.openwrtstatus.ui.screens.DashboardScreen
import com.mmckb.openwrtstatus.ui.screens.DeviceEditScreen
import com.mmckb.openwrtstatus.ui.screens.DetailScreen
import com.mmckb.openwrtstatus.ui.screens.DevicesScreen
import com.mmckb.openwrtstatus.ui.screens.FileManagerScreen
import com.mmckb.openwrtstatus.ui.screens.PackageManagerScreen
import com.mmckb.openwrtstatus.ui.screens.SettingsScreen
import com.mmckb.openwrtstatus.ui.screens.TerminalScreen
import com.mmckb.openwrtstatus.ui.screens.ToolScreen
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

private const val TAB_DASHBOARD = 0
private const val TAB_DEVICES = 1
private const val TAB_DETAIL = 2
private const val TAB_TERMINAL = 3
private const val TAB_TOOL = 4
private const val TAB_SETTINGS = 5

/** 横屏右栏可承载的二级页面。 */
private sealed interface SecondaryPage {
    data object FileManager : SecondaryPage
    data object PackageManager : SecondaryPage
    data object About : SecondaryPage
    data class DeviceEditor(val initial: RouterConfig, val isNew: Boolean) : SecondaryPage
}

@Composable
fun AppRoot(viewModel: RouterViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(TAB_DASHBOARD) }
    val config by viewModel.config.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val colors = LocalAppColors.current
    val context = LocalContext.current

    // 横屏（平板/折叠态/横持手机）双栏：左侧一级页 + 左下角 tab，右侧二级页；竖屏保持原样。
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var secondary by remember { mutableStateOf<SecondaryPage?>(null) }
    LaunchedEffect(isLandscape) {
        if (!isLandscape) secondary = null
    }

    val ssh = SshConfig(
        host = config.sshHost.ifBlank { config.ip },
        port = config.sshPort,
        username = config.sshUsername,
        password = config.sshPassword
    )

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

    fun openSecondary(page: SecondaryPage) {
        if (isLandscape) {
            secondary = page
        } else {
            val intent = when (page) {
                SecondaryPage.FileManager -> Intent(context, FileManagerActivity::class.java)
                SecondaryPage.PackageManager -> Intent(context, PackageManagerActivity::class.java)
                SecondaryPage.About -> Intent(context, AboutActivity::class.java)
                is SecondaryPage.DeviceEditor -> return
            }
            context.startActivity(intent.putExtra(FileManagerActivity.EXTRA_CONFIG, config))
        }
    }

    fun openEditor(device: RouterConfig, isNew: Boolean) {
        if (isLandscape) {
            secondary = SecondaryPage.DeviceEditor(device, isNew)
        } else {
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
    }

    // Records the page layer: pages extend edge to edge, so the translucent top bar and
    // the bottom tab strip blur the live content behind them.
    val backdrop = rememberLayerBackdrop()

    @Composable
    fun MainPage(modifier: Modifier) {
        Box(modifier.layerBackdrop(backdrop)) {
            when (selectedTab) {
                TAB_DASHBOARD -> DashboardScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
                TAB_DEVICES -> DevicesScreen(
                    viewModel = viewModel,
                    onOpenEditor = ::openEditor,
                    modifier = Modifier.fillMaxSize()
                )
                TAB_TERMINAL -> TerminalScreen(
                    viewModel = viewModel,
                    // 横屏双栏且未开二级页时，输出区渲染在右栏，左侧只保留输入。
                    hideOutput = secondary == null,
                    modifier = Modifier.fillMaxSize()
                )
                TAB_TOOL -> ToolScreen(
                    onOpenPackageManager = { openSecondary(SecondaryPage.PackageManager) },
                    onOpenFileManager = { openSecondary(SecondaryPage.FileManager) },
                    modifier = Modifier.fillMaxSize()
                )
                TAB_DETAIL -> DetailScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
                else -> SettingsScreen(
                    viewModel = viewModel,
                    onOpenAbout = { openSecondary(SecondaryPage.About) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    @Composable
    fun MainTopBar(modifier: Modifier) {
        AppTopBar(
            modifier = modifier
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = { blur(18.dp.toPx()) },
                    highlight = { Highlight(alpha = 0f) }
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
                    Surface(
                        onClick = {
                            if (terminalConnected) viewModel.disconnectSsh() else viewModel.connectSsh()
                        },
                        enabled = config.sshEnabled && terminalState !is SshTerminal.State.Connecting,
                        shape = AppShapes.pill,
                        color = if (terminalConnected) colors.surfaceVariant else colors.primary,
                        contentColor = if (terminalConnected) colors.onSurface else colors.onPrimary,
                        border = if (terminalConnected) BorderStroke(1.dp, colors.outline) else null
                    ) {
                        Text(
                            if (terminalConnected) "断开" else "连接",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        )
    }

    @Composable
    fun TabBar(modifier: Modifier) {
        FloatingTabBar(
            backdrop = backdrop,
            tabs = listOf(
                TabItem("概览", Icons.Filled.Dashboard),
                TabItem("设备", Icons.Filled.Devices),
                TabItem("详情", Icons.Filled.Info),
                TabItem("终端", Icons.Filled.Terminal),
                TabItem("工具", Icons.Filled.Build),
                TabItem("设置", Icons.Filled.Settings)
            ),
            selectedIndex = selectedTab,
            onTabSelected = { selectedTab = it },
            modifier = modifier
        )
    }

    if (isLandscape) {
        // 横屏双栏：左侧一级页（tab 在左侧底部），右侧二级页；未打开时右侧为空。
        Row(Modifier.fillMaxSize().background(colors.background)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // 横屏下 tab 悬浮在左下角：内容整体抬高避让，滑到底不会被 tab 压住。
                MainPage(
                    Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .padding(bottom = 28.dp)
                )
                MainTopBar(Modifier.align(Alignment.TopCenter))
                // 磨砂条不贴屏幕边缘：加边距并做圆角。
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 10.dp, bottom = 10.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .height(88.dp)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RectangleShape },
                            effects = { blur(16.dp.toPx()) },
                            highlight = { Highlight(alpha = 0f) }
                        )
                )
                TabBar(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 12.dp)
                )
            }
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(colors.outline)
            )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // 横屏终端：未开二级页时，命令输出面板单独占右栏。
                if (selectedTab == TAB_TERMINAL && secondary == null) {
                    com.mmckb.openwrtstatus.ui.screens.TerminalOutputPane(
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                secondary?.let { page ->
                    Box(Modifier.fillMaxSize()) {
                        when (page) {
                            SecondaryPage.FileManager -> FileManagerScreen(
                                ssh = ssh,
                                onBack = { secondary = null }
                            )
                            SecondaryPage.PackageManager -> PackageManagerScreen(
                                ssh = ssh,
                                sshEnabled = config.sshEnabled,
                                onBack = { secondary = null }
                            )
                            SecondaryPage.About -> AboutScreen(onBack = { secondary = null })
                            is SecondaryPage.DeviceEditor -> DeviceEditScreen(
                                initial = page.initial,
                                isNew = page.isNew,
                                existingNames = devices.filterNot { it.id == page.initial.id }
                                    .map { it.displayName },
                                onCancel = { secondary = null },
                                onSave = { saved ->
                                    if (page.isNew) viewModel.addDevice(saved) else viewModel.updateDevice(saved)
                                    secondary = null
                                },
                                onDelete = {
                                    viewModel.deleteDevice(page.initial.id)
                                    secondary = null
                                }
                            )
                        }
                        // 横屏下胶囊从顶部滑出（右上角），竖屏保持从右侧滑入。
                        ConnectionToastHost(
                            Modifier.align(Alignment.TopEnd),
                            slideFromTop = true
                        )
                    }
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            MainPage(Modifier.fillMaxSize())

            MainTopBar(Modifier.align(Alignment.TopCenter))

            // Gaussian blur strip below the tab pill area.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .height(88.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = { blur(16.dp.toPx()) },
                        highlight = { Highlight(alpha = 0f) }
                    )
            )

            TabBar(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 22.dp)
            )
        }
    }
}
