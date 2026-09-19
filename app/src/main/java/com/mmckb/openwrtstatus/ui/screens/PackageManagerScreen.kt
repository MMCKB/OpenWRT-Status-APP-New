package com.mmckb.openwrtstatus.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.mmckb.openwrtstatus.ui.formatBytes
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val UPLOAD_TMP_PATH = "/tmp/upload.apk"

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
    var filter by remember { mutableStateOf("") }
    var installName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    var opRunning by remember { mutableStateOf(false) }
    var opDialogHidden by remember { mutableStateOf(false) }
    var opResult by remember { mutableStateOf<PkgOpResult?>(null) }
    var reloadOnOpClose by remember { mutableStateOf(false) }

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
        scope.launch {
            busy = true
            try {
                // 四路并行：已安装、可升级、存储、可用（最大最慢的一路单独跑）。
                val installedJob = scope.async {
                    runCatching { withContext(Dispatchers.IO) { client.listInstalled(ssh) } }
                }
                val upgradableJob = scope.async {
                    runCatching { withContext(Dispatchers.IO) { client.listUpgradable(ssh) } }
                }
                val storageJob = scope.async {
                    runCatching { withContext(Dispatchers.IO) { client.mountInfo(ssh) } }
                }
                val availableJob = scope.async {
                    runCatching { withContext(Dispatchers.IO) { client.listAvailable(ssh, emptySet()) } }
                }

                val installedResult = installedJob.await()
                installed = installedResult.getOrNull()
                upgradable = upgradableJob.await().getOrNull()
                storage = storageJob.await().getOrNull()
                val installedNames = installed.orEmpty().map { it.name }.toSet()
                val availableResult = availableJob.await()
                // 可用列表到达后，用已安装名单就地标记安装状态。
                available = availableResult.getOrNull()?.map {
                    it.copy(installed = installedNames.contains(it.name))
                }

                val failures = listOf(
                    installedResult,
                    upgradableJob.await(),
                    storageJob.await(),
                    availableResult
                ).count { it.isFailure }
                when {
                    failures == 4 -> setMsg("读取软件包列表失败，请检查 SSH 连接。", true)
                    failures > 0 -> setMsg("部分信息读取失败，请重试。", true)
                    else -> setMsg(null, false)
                }
            } finally {
                busy = false
            }
        }
    }

    fun runOp(action: String, pkgs: List<String>, info: String) {
        scope.launch {
            busy = true
            opRunning = true
            opResult = null
            opDialogHidden = false
            reloadOnOpClose = true
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
                opResult = result
                if (opDialogHidden) {
                    // 用户已选「后台等待」：用消息条反馈并自动刷新。
                    setMsg(
                        if (result.success) "$info 完成。" else "${info}失败：${result.stdout?.lineSequence()?.firstOrNull { it.startsWith("ERROR") } ?: "退出码 ${result.code}"}",
                        !result.success
                    )
                    loadLists()
                }
            } catch (e: Exception) {
                opRunning = false
                setMsg(e.message ?: "$info 失败。", true)
                reloadOnOpClose = false
            } finally {
                busy = false
            }
        }
    }

    fun closeOpDialog() {
        if (opRunning) {
            // 「后台等待」：隐藏弹窗，操作继续在后台执行。
            opDialogHidden = true
        } else {
            opResult = null
            if (reloadOnOpClose) loadLists()
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
                    val result = withContext(Dispatchers.IO) {
                        client.install(ssh, UPLOAD_TMP_PATH)
                    }
                    opRunning = false
                    opResult = result
                } catch (e: Exception) {
                    setMsg(e.message ?: "上传安装失败。", true)
                    reloadOnOpClose = false
                } finally {
                    runCatching {
                        withContext(Dispatchers.IO) { SshFiles.delete(ssh, UPLOAD_TMP_PATH, isDir = false) }
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
        Text(
            "软件包",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface
        )

        if (busy) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
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

            // 视图切换 + 过滤
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TabChip("已安装 ${installedList.size}", mode == "installed", Modifier.weight(1f)) { mode = "installed" }
                TabChip("可用 ${availableList.size}", mode == "available", Modifier.weight(1f)) { mode = "available" }
                TabChip("可升级 ${updatesList.size}", mode == "updates", Modifier.weight(1f)) { mode = "updates" }
            }
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                placeholder = { Text("过滤软件包名称或描述") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
            if (mode == "available") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = installName,
                        onValueChange = { installName = it },
                        singleLine = true,
                        placeholder = { Text("输入包名或 URL 安装") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { confirmInstallName = installName.trim() },
                        enabled = !busy && installName.isNotBlank()
                    ) { Text("安装") }
                }
            }

            // 软件包列表
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 6.dp)
            ) {
                if (filtered.isEmpty() && !busy) {
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
                installName = ""
                runOp("install", listOf(nameOrUrl), "安装")
            },
            onDismiss = { confirmInstallName = null }
        )
    }

    // 软件源管理（读取 distfeed / customfeeds，开关启用状态，保存后自动 apk update）。
    if (showSources) {
        AppDialog(
            title = "软件源",
            confirmLabel = if (sourcesBusy) "保存中…" else "保存",
            dismissLabel = "关闭",
            confirmEnabled = !sourcesBusy && sources != null,
            onConfirm = {
                val repos = sources.orEmpty()
                scope.launch {
                    sourcesBusy = true
                    try {
                        withContext(Dispatchers.IO) { client.saveRepositories(ssh, repos) }
                        setMsg("软件源已保存并更新索引。", false)
                        showSources = false
                        loadLists()
                    } catch (e: Exception) {
                        setMsg(e.message ?: "保存软件源失败。", true)
                    } finally {
                        sourcesBusy = false
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
                Column(
                    modifier = Modifier
                        .height(320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    sources.orEmpty().forEach { repo ->
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
                                    sources = sources.orEmpty().map {
                                        if (it.line == repo.line && it.source == repo.source) {
                                            it.copy(enabled = enabled)
                                        } else {
                                            it
                                        }
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
                    Text(
                        res.stdout?.takeIf { it.isNotBlank() } ?: if (res.success) "操作成功完成。" else "操作失败。",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (res.success) colors.onSurface else colors.error,
                        modifier = Modifier
                            .height(260.dp)
                            .verticalScroll(rememberScrollState())
                    )
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
        modifier = modifier
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .fillMaxWidth(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
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
                else -> TextButton(
                    onClick = onRemove,
                    enabled = !busy
                ) { Text("删除", color = colors.error) }
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
