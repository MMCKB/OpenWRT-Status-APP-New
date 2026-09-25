package com.mmckb.openwrtstatus.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.remote.ApkRepository
import com.mmckb.openwrtstatus.data.remote.MountInfo
import com.mmckb.openwrtstatus.data.remote.PackageClient
import com.mmckb.openwrtstatus.data.remote.PkgInfo
import com.mmckb.openwrtstatus.data.remote.PkgOpResult
import com.mmckb.openwrtstatus.data.ssh.SshFileException
import com.mmckb.openwrtstatus.data.ssh.SshFiles
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.components.ThinScrollbarColumn
import com.mmckb.openwrtstatus.ui.formatBytes
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val UPLOAD_TMP_PATH = "/tmp/upload.apk"

/** 标题旁状态胶囊的状态：运行中（转圈）/ 成功（打勾）/ 失败（打叉）/ 隐藏。 */
private sealed interface PillState {
    data object Hidden : PillState
    data class Running(val info: String) : PillState
    data class Success(val info: String) : PillState
    data class Failure(val info: String) : PillState
}

/**
 * 软件包管理页（二级页，独立 Activity）：与旧版 OpenWRT-Status-APP 相同的方式——
 * 通过 SSH 直接执行原生 apk 命令（info -v / list -u / search / add / del /
 * upgrade / update），提供已安装/可用/可升级三个视图、过滤、按名安装、
 * 上传安装、软件源管理，以及根分区存储占用进度条。
 */
@Composable
fun PackageManagerScreen(
    ssh: SshConfig,
    sshEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val client = remember { PackageClient() }

    var mode by remember { mutableStateOf("installed") }
    var installed by remember { mutableStateOf<List<PkgInfo>?>(null) }
    var available by remember { mutableStateOf<List<PkgInfo>?>(null) }
    var upgradable by remember { mutableStateOf<List<PkgInfo>?>(null) }
    var storage by remember { mutableStateOf<MountInfo?>(null) }
    var backend by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var showFilter by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var installedLoading by remember { mutableStateOf(false) }
    var availableLoading by remember { mutableStateOf(false) }
    var upgradableLoading by remember { mutableStateOf(false) }
    var storageLoading by remember { mutableStateOf(false) }
    var opInfo by remember { mutableStateOf("") }

    var opRunning by remember { mutableStateOf(false) }
    var opDialogHidden by remember { mutableStateOf(false) }
    var opResult by remember { mutableStateOf<PkgOpResult?>(null) }
    var reloadOnOpClose by remember { mutableStateOf(false) }
    var pillState by remember { mutableStateOf<PillState>(PillState.Hidden) }
    // 成功/失败胶囊停留约 1.8 秒后自动隐藏（退出动画由 AnimatedVisibility 承担）。
    LaunchedEffect(pillState) {
        if (pillState is PillState.Success || pillState is PillState.Failure) {
            delay(1800)
            pillState = PillState.Hidden
        }
    }

    var pendingRemove by remember { mutableStateOf<PkgInfo?>(null) }
    var confirmInstallName by remember { mutableStateOf<String?>(null) }

    // 软件源管理
    var showSources by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<ApkRepository>?>(null) }
    var sourcesBusy by remember { mutableStateOf(false) }

    fun setMsg(text: String?, isError: Boolean) {
        message = text
        messageIsError = isError
    }

    fun loadLists() {
        // 每一路独立转圈：哪个列表在加载，就在哪个视图里显示 spinner，
        // 已完成的列表立即渲染，不再等全部加载完。
        installedLoading = true
        availableLoading = true
        upgradableLoading = true
        storageLoading = true
        setMsg(null, false)
        scope.launch {
            launch {
                try {
                    installed = client.listInstalled(ssh)
                } catch (e: Exception) {
                    installed = emptyList()
                    setMsg(e.message ?: "已安装列表读取失败。", true)
                } finally {
                    installedLoading = false
                }
            }
            launch {
                try {
                    upgradable = client.listUpgradable(ssh)
                } catch (e: Exception) {
                    upgradable = emptyList()
                } finally {
                    upgradableLoading = false
                }
            }
            launch {
                try {
                    storage = client.mountInfo(ssh)
                } finally {
                    storageLoading = false
                }
            }
            try {
                val raw = client.listAvailable(ssh, emptySet())
                val names = installed.orEmpty().map { it.name }.toSet()
                available = raw.map { it.copy(installed = names.contains(it.name)) }
            } catch (e: Exception) {
                available = emptyList()
            } finally {
                availableLoading = false
            }
        }
    }

    fun runOp(action: String, pkgs: List<String>, info: String, background: Boolean = false) {
        opInfo = info
        if (background) pillState = PillState.Running(info)
        scope.launch {
            busy = true
            opRunning = true
            opResult = null
            // background（自动任务）从一开始就不弹操作窗，仅显示标题旁的胶囊动画；
            // 手动任务仍先弹窗，等用户点「后台等待」后再转入胶囊形态。
            opDialogHidden = background
            reloadOnOpClose = !background
            try {
                val result = withContext(Dispatchers.IO) {
                    when (action) {
                        "update" -> client.update(ssh)
                        "install" -> client.install(ssh, pkgs.first())
                        "remove" -> client.remove(ssh, pkgs.first())
                        "upgrade-all" -> client.upgradeAll(ssh)
                        else -> client.upgradePackage(ssh, pkgs.first())
                    }
                }
                opRunning = false
                if (background) {
                    // 自动/后台任务：不弹结果窗，胶囊转为成功/失败形态。
                    pillState =
                        if (result.success) PillState.Success(info) else PillState.Failure(info)
                    if (!result.success) {
                        setMsg(
                            "${info}失败：${result.stdout?.lineSequence()?.firstOrNull { it.startsWith("ERROR") } ?: "退出码 ${result.code}"}",
                            true
                        )
                    }
                    loadLists()
                } else {
                    opResult = result
                    if (opDialogHidden) {
                        // 用户已选「后台等待」：成功静默刷新，失败才提示。
                        pillState =
                            if (result.success) PillState.Success(info) else PillState.Failure(info)
                        if (!result.success) {
                            setMsg(
                                "${info}失败：${result.stdout?.lineSequence()?.firstOrNull { it.startsWith("ERROR") } ?: "退出码 ${result.code}"}",
                                true
                            )
                        }
                        loadLists()
                    }
                }
            } catch (e: Exception) {
                opRunning = false
                // 胶囊正在显示（后台模式）时以失败形态反馈。
                if (background || opDialogHidden) pillState = PillState.Failure(info)
                setMsg(e.message ?: "$info 失败。", true)
                reloadOnOpClose = false
            } finally {
                busy = false
            }
        }
    }

    fun closeOpDialog() {
        if (opRunning) {
            // 「后台等待」：隐藏弹窗，操作继续在后台执行，胶囊接管进度显示。
            opDialogHidden = true
            pillState = PillState.Running(opInfo)
        } else {
            opResult = null
            if (reloadOnOpClose) loadLists()
        }
    }

    // 进入软件包页即自动「更新列表」：并行加载三个列表与存储占用，
    // 同时后台执行 apk update；期间标题旁显示后台等待的胶囊动画。
    LaunchedEffect(sshEnabled) {
        if (sshEnabled) {
            loadLists()
            runOp("update", emptyList(), "更新列表", background = true)
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                message = null
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw SshFileException("无法读取所选文件。")
                    }
                    withContext(Dispatchers.IO) { SshFiles.upload(ssh, UPLOAD_TMP_PATH, bytes) }
                    busy = false
                    opRunning = true
                    opDialogHidden = false
                    opResult = null
                    reloadOnOpClose = true
                    opInfo = "上传安装"
                    val result = withContext(Dispatchers.IO) {
                        client.install(ssh, if (backend == "opkg") "/tmp/upload.ipk" else UPLOAD_TMP_PATH)
                    }
                    opRunning = false
                    opResult = result
                    if (opDialogHidden) {
                        pillState =
                            if (result.success) PillState.Success(opInfo) else PillState.Failure(opInfo)
                    }
                } catch (e: Exception) {
                    setMsg(e.message ?: "上传安装失败。", true)
                    if (opDialogHidden) pillState = PillState.Failure("上传安装")
                    reloadOnOpClose = false
                } finally {
                    runCatching {
                        withContext(Dispatchers.IO) { SshFiles.delete(ssh, if (backend == "opkg") "/tmp/upload.ipk" else UPLOAD_TMP_PATH, isDir = false) }
                    }
                    busy = false
                    opRunning = false
                }
            }
        }
    }

    val installedList = installed.orEmpty()
    val availableList = available.orEmpty()
    val updatesList = upgradable.orEmpty()
    val listForMode = when (mode) {
        "available" -> availableList
        "updates" -> updatesList
        else -> installedList
    }
    val filtered = if (filter.isBlank()) {
        listForMode
    } else {
        listForMode.filter {
            it.name.contains(filter, ignoreCase = true) ||
                (it.description?.contains(filter, ignoreCase = true) == true)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 2.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppBackButton(onBack = onBack)
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { runOp("update", emptyList(), "更新列表") },
                enabled = !busy && sshEnabled
            ) { Text("更新列表") }
            TextButton(
                onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                enabled = !busy && sshEnabled
            ) { Text("上传安装") }
            TextButton(
                onClick = {
                    showSources = true
                    if (sources == null) {
                        sourcesBusy = true
                        scope.launch {
                            try {
                                sources = withContext(Dispatchers.IO) { client.repositoriesSnapshot(ssh) }
                            } catch (e: Exception) {
                                setMsg(e.message ?: "读取软件源失败。", true)
                            } finally {
                                sourcesBusy = false
                            }
                        }
                    }
                },
                enabled = !busy && sshEnabled
            ) { Text("软件源") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "软件包",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Spacer(Modifier.weight(1f))
            // 标题右侧的状态胶囊：运行中（主色+转圈）→ 成功（绿色+打勾动画）/
            // 失败（红色+打叉动画）。颜色与内容切换均带过渡，停留约 1.8 秒后
            // 以缩放+淡出动画消失。
            AnimatedVisibility(
                visible = pillState != PillState.Hidden,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                val pillColor by animateColorAsState(
                    targetValue = when (pillState) {
                        is PillState.Failure -> colors.error
                        is PillState.Success -> colors.success
                        PillState.Hidden, is PillState.Running -> colors.primary
                    },
                    animationSpec = tween(300),
                    label = "pillColor"
                )
                Surface(
                    shape = AppShapes.pill,
                    color = pillColor,
                    contentColor = colors.onPrimary
                ) {
                    AnimatedContent(
                        targetState = pillState,
                        transitionSpec = {
                            (fadeIn(tween(200)) + scaleIn(initialScale = 0.6f, animationSpec = tween(200))) togetherWith
                                (fadeOut(tween(150)) + scaleOut(targetScale = 0.6f, animationSpec = tween(150)))
                        },
                        label = "pillContent"
                    ) { state ->
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when (state) {
                                is PillState.Running -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = colors.onPrimary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "正在${state.info}",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                is PillState.Success -> {
                                    AnimatedCheckIcon(Modifier.size(16.dp), colors.onPrimary)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "${state.info}成功",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                is PillState.Failure -> {
                                    AnimatedCrossIcon(Modifier.size(16.dp), colors.onPrimary)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "${state.info}失败",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                PillState.Hidden -> {}
                            }
                        }
                    }
                }
            }
        }

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (messageIsError) colors.error else colors.success,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        if (!sshEnabled) {
            AppCard {
                Text(
                    "软件包管理通过路由器 SSH 执行，请先在设备设置中开启 SSH。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
        } else {
            // 存储占用进度条（对应 LuCI 顶部的分区用量）。
            storage?.let { st ->
                val used = (st.size - st.free).coerceAtLeast(0)
                val pct = if (st.size > 0) (used * 100 / st.size).toInt() else 0
                Column(Modifier.padding(top = 4.dp)) {
                    LinearProgressIndicator(
                        progress = { (pct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.primary,
                        trackColor = colors.surfaceVariant
                    )
                    Text(
                        "存储已用 $pct%（已用 ${formatBytes(used)}，可用 ${formatBytes(st.free)}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // 视图切换
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TabChip("已安装 ${installedList.size}", mode == "installed", Modifier.weight(1f)) { mode = "installed" }
                TabChip("可用 ${availableList.size}", mode == "available", Modifier.weight(1f)) { mode = "available" }
                TabChip("可升级 ${updatesList.size}", mode == "updates", Modifier.weight(1f)) { mode = "updates" }
                // 搜索胶囊：与三个 tab 同高同圆角。
                Surface(
                    onClick = {
                        showFilter = !showFilter
                        if (!showFilter) filter = ""
                    },
                    enabled = sshEnabled,
                    shape = AppShapes.pill,
                    color = colors.primary,
                    contentColor = colors.onPrimary,
                    modifier = Modifier.height(38.dp)
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = "搜索",
                        modifier = Modifier.padding(horizontal = 12.dp).size(18.dp)
                    )
                }
            }
            // 紧凑圆角搜索/安装框：点搜索胶囊后展开收起（带动画）。
            // 可用视图下，输入的内容既是过滤条件也可以直接安装。
            androidx.compose.animation.AnimatedVisibility(
                visible = showFilter,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(38.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (filter.isEmpty()) {
                                Text(
                                    if (mode == "available") "搜索，或输入包名安装" else "搜索",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            androidx.compose.foundation.text.BasicTextField(
                                value = filter,
                                onValueChange = { filter = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (filter.isNotEmpty()) {
                            IconButton(onClick = { filter = "" }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "清除",
                                    tint = colors.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        if (mode == "available") {
                            Spacer(Modifier.width(6.dp))
                            Button(
                                onClick = { confirmInstallName = filter.trim() },
                                enabled = filter.isNotBlank() && !busy,
                                shape = AppShapes.pill,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 12.dp, vertical = 4.dp
                                )
                            ) { Text("安装") }
                        }
                    }
                }
            }

            // 软件包列表
            val listLoading = when (mode) {
                "available" -> availableLoading
                "updates" -> upgradableLoading
                else -> installedLoading
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 6.dp)
            ) {
                if (listLoading) {
                    // spinner 显示在正在加载的那个视图里。
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 32.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        }
                    }
                } else if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (mode == "updates") "所有软件包均为最新。" else "无匹配软件包。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 24.dp)
                        )
                    }
                }
                items(filtered, key = { "${mode}:${it.name}" }) { pkg ->
                    PackageRow(
                        pkg = pkg,
                        mode = mode,
                        busy = busy,
                        onRemove = { pendingRemove = pkg },
                        onInstall = { runOp("install", listOf(pkg.name), "安装") },
                        onUpgrade = { runOp("upgrade", listOf(pkg.name), "升级") }
                    )
                }
            }
        }
    }

    // ---- 对话框 ----

    pendingRemove?.let { pkg ->
        AppDialog(
            title = "删除软件包",
            message = "确定删除「${pkg.name}」吗？其依赖的其他软件包可能一并被清理。",
            confirmLabel = "删除",
            confirmColor = colors.error,
            onConfirm = {
                val name = pkg.name
                pendingRemove = null
                runOp("remove", listOf(name), "删除")
            },
            onDismiss = { pendingRemove = null }
        )
    }

    confirmInstallName?.let { nameOrUrl ->
        val isPath = nameOrUrl.contains('/')
        val known = availableList.any { it.name == nameOrUrl }
        AppDialog(
            title = "安装软件包",
            message = when {
                isPath -> "「$nameOrUrl」是一个路径/URL，将直接从该地址安装。来自不可信来源的软件包存在安全风险！"
                !known -> "软件包「$nameOrUrl」不在可用列表中，安装可能失败。仍要继续吗？"
                else -> "确定安装「$nameOrUrl」吗？"
            },
            confirmLabel = "安装",
            onConfirm = {
                confirmInstallName = null
                runOp("install", listOf(nameOrUrl), "安装")
            },
            onDismiss = { confirmInstallName = null }
        )
    }

    // 软件源管理（读取 distfeed / customfeeds，可新增源、开关启用状态，保存后自动 apk update）。
    if (showSources) {
        var newSourceUrl by remember { mutableStateOf("") }
        var sourcesError by remember { mutableStateOf<String?>(null) }
        AppDialog(
            title = "软件源",
            confirmLabel = if (sourcesBusy) "保存中…" else "保存",
            dismissLabel = "关闭",
            confirmEnabled = !sourcesBusy && sources != null,
            onConfirm = {
                val repos = sources.orEmpty()
                scope.launch {
                    sourcesBusy = true
                    var ok = false
                    var output: String? = null
                    try {
                        output = withContext(Dispatchers.IO) { client.saveRepositories(ssh, repos) }
                        ok = !Regex("^ERROR|^Collected errors", RegexOption.MULTILINE).containsMatchIn(output)
                        setMsg(
                            if (ok) "软件源已保存并更新索引。" else "保存完成但更新索引失败：${output.lineSequence().firstOrNull { it.startsWith("ERROR") } ?: ""}",
                            !ok
                        )
                    } catch (e: Exception) {
                        setMsg(e.message ?: "保存软件源失败。", true)
                    } finally {
                        // 无论成功失败都关闭弹窗，结果由消息条反馈。
                        sourcesBusy = false
                        showSources = false
                        if (ok) loadLists()
                    }
                }
            },
            onDismiss = { if (!sourcesBusy) showSources = false }
        ) {
            if (sources == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
            } else {
                ThinScrollbarColumn(modifier = Modifier.height(360.dp)) {
                    // 添加源：默认加入 customfeeds.list。输入框与添加按钮同高同圆角。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = colors.surfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "http(s)://",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                                androidx.compose.foundation.text.BasicTextField(
                                    value = newSourceUrl,
                                    onValueChange = { newSourceUrl = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val url = newSourceUrl.trim()
                                if (url.isEmpty()) return@Button
                                sources = sources.orEmpty() + ApkRepository(
                                    line = -(sources.orEmpty().size + 1),
                                    url = url,
                                    enabled = true,
                                    source = "/etc/apk/repositories.d/customfeeds.list"
                                )
                                newSourceUrl = ""
                            },
                            enabled = newSourceUrl.isNotBlank(),
                            shape = AppShapes.pill,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 14.dp, vertical = 9.dp
                            )
                        ) { Text("添加") }
                    }
                    sourcesError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.error,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    sources.orEmpty().forEachIndexed { idx, repo ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    repo.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (repo.enabled) colors.onSurface else colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    repo.source?.substringAfterLast('/') ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = repo.enabled,
                                onCheckedChange = { enabled ->
                                    // 至少启用一个仓库：关掉最后一个启用项时阻止并提示。
                                    if (!enabled && sources.orEmpty().count { it.enabled } <= 1) {
                                        sourcesError = "至少启用一个软件包仓库。"
                                        return@Switch
                                    }
                                    sourcesError = null
                                    sources = sources.orEmpty().mapIndexed { i, r ->
                                        if (i == idx) r.copy(enabled = enabled) else r
                                    }
                                }
                            )
                        }
                    }
                    Text(
                        "保存后会备份原文件并自动更新索引；至少保留并启用一个仓库。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }

    // 操作结果弹窗（对应 LuCI 的输出模态框）。
    if ((opRunning && !opDialogHidden) || opResult != null) {
        AppDialog(
            title = "软件包操作",
            confirmLabel = if (opRunning) "后台等待" else "关闭",
            dismissLabel = "",
            onConfirm = { closeOpDialog() },
            onDismiss = { closeOpDialog() }
        ) {
            if (opRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
                }
            } else {
                val res = opResult
                if (res != null) {
                    res.pkmcmd?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    ThinScrollbarColumn(modifier = Modifier.height(260.dp)) {
                        Text(
                            res.stdout?.takeIf { it.isNotBlank() }
                                ?: if (res.success) "操作成功完成。" else "操作失败。",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (res.success) colors.onSurface else colors.error
                        )
                    }
                    if (!res.success) {
                        Text(
                            "命令退出码 ${res.code}。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.error,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = AppShapes.pill,
        color = if (selected) colors.primary else colors.surfaceVariant,
        contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, colors.outline),
        // 与搜索胶囊同高（38dp），圆角同为胶囊形。
        modifier = modifier.height(38.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PackageRow(
    pkg: PkgInfo,
    mode: String,
    busy: Boolean,
    onRemove: () -> Unit,
    onInstall: () -> Unit,
    onUpgrade: () -> Unit
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pkg.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(pkg.version ?: "-")
                        if (pkg.size > 0) append("　·　${formatBytes(pkg.size)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            when (mode) {
                "available" -> if (pkg.installed) {
                    Text(
                        "已安装",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant
                    )
                } else {
                    Button(
                        onClick = onInstall,
                        enabled = !busy,
                        shape = AppShapes.pill,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 14.dp, vertical = 4.dp
                        )
                    ) { Text("安装") }
                }
                "updates" -> Button(
                    onClick = onUpgrade,
                    enabled = !busy,
                    shape = AppShapes.pill,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.success),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp, vertical = 4.dp
                    )
                ) { Text("升级") }
                else -> Button(
                    onClick = onRemove,
                    enabled = !busy,
                    shape = AppShapes.pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.surface,
                        contentColor = colors.error
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.error.copy(alpha = 0.5f)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp, vertical = 4.dp
                    )
                ) { Text("删除") }
            }
        }
        pkg.description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** 打勾图标：两段线条按进度依次画出（入场时逐笔成形）。 */
@Composable
private fun AnimatedCheckIcon(modifier: Modifier = Modifier, color: Color) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val p = progress.value
        val a = Offset(size.width * 0.18f, size.height * 0.55f)
        val b = Offset(size.width * 0.42f, size.height * 0.78f)
        val c = Offset(size.width * 0.82f, size.height * 0.26f)
        val stroke = 2.5.dp.toPx()
        val p1 = (p / 0.45f).coerceIn(0f, 1f)
        val p2 = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f)
        if (p1 > 0f) drawLine(color, a, lerp(a, b, p1), stroke, StrokeCap.Round)
        if (p2 > 0f) drawLine(color, b, lerp(b, c, p2), stroke, StrokeCap.Round)
    }
}

/** 打叉图标：两条对角线按进度依次画出（入场时逐笔成形）。 */
@Composable
private fun AnimatedCrossIcon(modifier: Modifier = Modifier, color: Color) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val p = progress.value
        val a = Offset(size.width * 0.25f, size.height * 0.25f)
        val b = Offset(size.width * 0.75f, size.height * 0.75f)
        val c = Offset(size.width * 0.75f, size.height * 0.25f)
        val d = Offset(size.width * 0.25f, size.height * 0.75f)
        val stroke = 2.5.dp.toPx()
        val p1 = (p / 0.5f).coerceIn(0f, 1f)
        val p2 = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)
        if (p1 > 0f) drawLine(color, a, lerp(a, b, p1), stroke, StrokeCap.Round)
        if (p2 > 0f) drawLine(color, c, lerp(c, d, p2), stroke, StrokeCap.Round)
    }
}
