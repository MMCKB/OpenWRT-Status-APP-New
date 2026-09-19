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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.remote.MountInfo
import com.mmckb.openwrtstatus.data.remote.PackageClient
import com.mmckb.openwrtstatus.data.remote.PkgInfo
import com.mmckb.openwrtstatus.data.remote.PkgOpResult
import com.mmckb.openwrtstatus.data.ssh.SshFileException
import com.mmckb.openwrtstatus.data.ssh.SshFiles
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.formatBytes
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val UPLOAD_TMP_PATH = "/tmp/upload.apk"

/**
 * 软件包管理页（二级页，独立 Activity）：与 LuCI 的 package-manager 功能对齐——
 * 已安装/可用/可升级三个视图、过滤、更新列表、按名安装、上传安装、
 * 删除/升级，操作结果显示，以及根分区存储占用进度条。
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
    var storage by remember { mutableStateOf<MountInfo?>(null) }
    var filter by remember { mutableStateOf("") }
    var installName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    var opRunning by remember { mutableStateOf(false) }
    var opResult by remember { mutableStateOf<PkgOpResult?>(null) }
    var reloadOnOpClose by remember { mutableStateOf(false) }

    var pendingRemove by remember { mutableStateOf<PkgInfo?>(null) }
    var confirmInstallName by remember { mutableStateOf<String?>(null) }

    fun setMsg(text: String?, isError: Boolean) {
        message = text
        messageIsError = isError
    }

    fun loadLists() {
        scope.launch {
            busy = true
            try {
                installed = withContext(Dispatchers.IO) { client.listInstalled(ssh) }
                available = withContext(Dispatchers.IO) { client.listAvailable(ssh) }
                storage = withContext(Dispatchers.IO) { client.mountInfo(ssh) }
            } catch (e: Exception) {
                setMsg(e.message ?: "读取软件包列表失败。", true)
            } finally {
                busy = false
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (sshEnabled) loadLists()
    }

    fun runOp(action: String, pkgs: List<String>, info: String) {
        scope.launch {
            busy = true
            opRunning = true
            opResult = null
            reloadOnOpClose = true
            try {
                val result = withContext(Dispatchers.IO) { client.op(ssh, action, pkgs) }
                opResult = result
                if (!result.success) {
                    // 失败信息保留在结果弹窗里展示。
                    setMsg(null, false)
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

    fun closeOpAndReload() {
        opResult = null
        if (reloadOnOpClose) loadLists()
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
                    opResult = null
                    reloadOnOpClose = true
                    val result = withContext(Dispatchers.IO) {
                        client.op(ssh, "install", listOf(UPLOAD_TMP_PATH))
                    }
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
    val updatesList = installedList.filter { inst ->
        val avail = availableList.firstOrNull { it.name == inst.name }
        avail != null && avail.version != null && inst.version != null &&
            PackageClient.compareVersion(avail.version!!, inst.version!!) > 0
    }
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
                onClick = {
                    runOp("update", emptyList(), "更新列表")
                },
                enabled = !busy && sshEnabled
            ) { Text("更新列表") }
            TextButton(
                onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                enabled = !busy && sshEnabled
            ) { Text("上传安装") }
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
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
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
                        newVersion = if (mode == "updates") {
                            availableList.firstOrNull { it.name == pkg.name }?.version
                        } else {
                            null
                        },
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

    // 操作结果弹窗（对应 LuCI 的输出模态框）。
    if (opRunning || opResult != null) {
        AppDialog(
            title = "软件包操作",
            confirmLabel = if (opRunning) "后台等待" else "关闭",
            dismissLabel = "",
            onConfirm = { closeOpAndReload() },
            onDismiss = { closeOpAndReload() }
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
                    val body = listOf(
                        res.stdout?.takeIf { it.isNotBlank() },
                        res.stderr?.takeIf { it.isNotBlank() }?.let { "错误输出：\n$it" }
                    ).filterNotNull().joinToString("\n").ifBlank {
                        if (res.success) "操作成功完成。" else "操作失败。"
                    }
                    Text(
                        body,
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
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun PackageRow(
    pkg: PkgInfo,
    mode: String,
    newVersion: String?,
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
                        append(if (mode == "updates" && newVersion != null) {
                            "${pkg.version ?: "-"} » $newVersion"
                        } else {
                            pkg.version ?: "-"
                        })
                        if (pkg.size > 0) append("　·　${formatBytes(pkg.size)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            when (mode) {
                "available" -> Button(
                    onClick = onInstall,
                    enabled = !busy,
                    shape = AppShapes.pill,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp, vertical = 4.dp
                    )
                ) { Text("安装") }
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
