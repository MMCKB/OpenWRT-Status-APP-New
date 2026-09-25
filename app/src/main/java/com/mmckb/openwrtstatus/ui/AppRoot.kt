package com.mmckb.openwrtstatus.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
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
import com.mmckb.openwrtstatus.WirelessActivity
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshTerminal
import com.mmckb.openwrtstatus.ui.components.AppTopBar
import com.mmckb.openwrtstatus.ui.components.PredictiveBackEasing
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
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.screens.WirelessScreen
import com.mmckb.openwrtstatus.ui.screens.TerminalOutputPane
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
    data object Wireless : SecondaryPage
    data object About : SecondaryPage
    data class DeviceEditor(val initial: RouterConfig, val isNew: Boolean) : SecondaryPage
}

@OptIn(ExperimentalComposeApi::class)
@Composable
fun AppRoot(viewModel: RouterViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(TAB_DASHBOARD) }
    val config by viewModel.config.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val colors = LocalAppColors.current
    val context = LocalContext.current

    // 二级页状态：横屏内联在右栏；竖屏打开走独立 Activity（保留系统预测性返回）。
    // 旋转跨越两种布局时页面跟随：横屏 -> 竖屏保持内联渲染（全屏），竖屏 Activity 转到
    // 横屏时自行退出并把页面交回右栏（见各二级 Activity 的旋转交接）。
    var secondary by remember { mutableStateOf<SecondaryPage?>(null) }

    val editLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        // 设备编辑页旋转到横屏的交接：转成右栏内联编辑器，继续编辑同一设备。
        if (data != null && data.getBooleanExtra(DeviceEditActivity.EXTRA_OPEN_INLINE, false)) {
            val initial = data.getSerializableExtra(DeviceEditActivity.EXTRA_DEVICE) as? RouterConfig
            if (initial != null) {
                secondary = SecondaryPage.DeviceEditor(
                    initial,
                    data.getBooleanExtra(DeviceEditActivity.EXTRA_IS_NEW, false)
                )
            }
            return@rememberLauncherForActivityResult
        }
        val deletedId = data?.getStringExtra(DeviceEditActivity.EXTRA_DELETE_ID)
        if (deletedId != null) {
            viewModel.deleteDevice(deletedId)
            return@rememberLauncherForActivityResult
        }
        val saved = data?.getSerializableExtra(DeviceEditActivity.EXTRA_SAVED) as? RouterConfig
        if (saved != null) {
            val isNew = data?.getBooleanExtra(DeviceEditActivity.EXTRA_IS_NEW, false) ?: false
            if (isNew) viewModel.addDevice(saved) else viewModel.updateDevice(saved)
        }
    }

    // 竖屏独立二级页的启动器：旋转到横屏时 Activity 带着交接标记退出，在这里转成右栏内联。
    val fileManagerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.data?.getBooleanExtra(FileManagerActivity.EXTRA_OPEN_INLINE, false) == true) {
            secondary = SecondaryPage.FileManager
        }
    }
    val packageManagerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.data?.getBooleanExtra(PackageManagerActivity.EXTRA_OPEN_INLINE, false) == true) {
            secondary = SecondaryPage.PackageManager
        }
    }
    val aboutLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.data?.getBooleanExtra(AboutActivity.EXTRA_OPEN_INLINE, false) == true) {
            secondary = SecondaryPage.About
        }
    }
    val wirelessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.data?.getBooleanExtra(WirelessActivity.EXTRA_OPEN_INLINE, false) == true) {
            secondary = SecondaryPage.Wireless
        }
    }

    // Records the page layer: pages extend edge to edge, so the translucent top bar and
    // the bottom tab strip blur the live content behind them.
    val backdrop = rememberLayerBackdrop()

    // 二级页内容做成 movable：横屏右栏与竖屏全屏是两个不同的组合位置，movable 让页面
    // 在两者之间移动时保留内部状态（目录层级、滚动位置、正在编辑的表单都不丢）。
    val secondaryPane = remember {
        movableContentOf<SecondaryPage> { page ->
            Box(Modifier.fillMaxSize()) {
                val cfg = config
                val ssh = SshConfig(
                    host = cfg.sshHost.ifBlank { cfg.ip },
                    port = cfg.sshPort,
                    username = cfg.sshUsername,
                    password = cfg.sshPassword
                )
                when (page) {
                    SecondaryPage.FileManager -> FileManagerScreen(
                        ssh = ssh,
                        onBack = { secondary = null }
                    )
                    SecondaryPage.PackageManager -> PackageManagerScreen(
                        ssh = ssh,
                        sshEnabled = cfg.sshEnabled,
                        onBack = { secondary = null }
                    )
                    SecondaryPage.About -> AboutScreen(onBack = { secondary = null })
                    SecondaryPage.Wireless -> WirelessScreen(
                        config = cfg,
                        onBack = { secondary = null }
                    )
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
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // MainActivity 声明了 configChanges 自行处理旋转（组合不重建），横竖屏以窗口
        // 布局约束判断：尺寸变化必然触发重组，不依赖 Activity 重建或配置分发时机。
        val isLandscape = maxWidth > maxHeight

        // 二级页打开时拦截系统返回（原先横屏下系统返回会直接退出应用）；各页面内部的
        // 逐级返回/弹层处理组合在后、优先级更高，不冲突。
        BackHandler(enabled = secondary != null) { secondary = null }

        fun openSecondary(page: SecondaryPage) {
            if (isLandscape) {
                secondary = page
            } else {
                when (page) {
                    SecondaryPage.FileManager -> fileManagerLauncher.launch(
                        Intent(context, FileManagerActivity::class.java)
                            .putExtra(FileManagerActivity.EXTRA_CONFIG, config)
                    )
                    SecondaryPage.PackageManager -> packageManagerLauncher.launch(
                        Intent(context, PackageManagerActivity::class.java)
                            .putExtra(FileManagerActivity.EXTRA_CONFIG, config)
                    )
                    SecondaryPage.About -> aboutLauncher.launch(
                        Intent(context, AboutActivity::class.java)
                    )
                    SecondaryPage.Wireless -> wirelessLauncher.launch(
                        Intent(context, WirelessActivity::class.java)
                            .putExtra(WirelessActivity.EXTRA_CONFIG, config)
                    )
                    is SecondaryPage.DeviceEditor -> return
                }
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
                        hideOutput = isLandscape && secondary == null,
                        bottomSpacer = if (isLandscape) 40.dp else 76.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                    TAB_TOOL -> ToolScreen(
                        onOpenPackageManager = { openSecondary(SecondaryPage.PackageManager) },
                        onOpenFileManager = { openSecondary(SecondaryPage.FileManager) },
                        onOpenWireless = { openSecondary(SecondaryPage.Wireless) },
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
                            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
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
                        TerminalOutputPane(
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    secondary?.let { page ->
                        // 预测性返回：右栏二级页跟手右滑淡出，提交关闭，取消回弹。
                        val backAnim = remember(page) { androidx.compose.animation.core.Animatable(0f) }
                        val paneScope = rememberCoroutineScope()
                        androidx.activity.compose.PredictiveBackHandler { events ->
                            try {
                                events.collect { ev ->
                                    val p = com.mmckb.openwrtstatus.ui.components.PredictiveBackEasing
                                        .transform(ev.progress).coerceIn(0f, 1f)
                                    backAnim.snapTo(p)
                                }
                                secondary = null
                            } catch (_: kotlin.coroutines.cancellation.CancellationException) {
                                paneScope.launch {
                                    backAnim.animateTo(
                                        0f,
                                        androidx.compose.animation.core.spring(
                                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            }
                        }
                        val backP = backAnim.value
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = size.width * 0.35f * backP
                                    alpha = 1f - 0.6f * backP
                                }
                        ) {
                            secondaryPane(page)
                            // 横屏下胶囊从顶部滑出（右上角）。
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
                val page = secondary
                if (page == null) {
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
                } else {
                    // 竖屏全屏二级页：从横屏旋转过来时保持停留在这个页面，不弹回首页。
                    secondaryPane(page)
                    ConnectionToastHost(
                        Modifier.align(Alignment.CenterEnd),
                        slideFromTop = false
                    )
                }
            }
        }
    }
}
