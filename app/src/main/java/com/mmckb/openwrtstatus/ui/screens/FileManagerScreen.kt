package com.mmckb.openwrtstatus.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.FileEntry
import com.mmckb.openwrtstatus.data.ssh.SshFileException
import com.mmckb.openwrtstatus.data.ssh.SshFiles
import com.mmckb.openwrtstatus.ui.formatBytes
import com.mmckb.openwrtstatus.ui.formatRate
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.components.AppShapes
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024
private const val MAX_DOWNLOAD_BYTES = 30L * 1024 * 1024
private const val PREVIEW_BYTES = 64 * 1024

/**
 * 文件管理页（二级页，独立 Activity）：基于 SSH exec 浏览路由器目录，
 * 支持查看文本、下载、上传、新建文件夹、重命名与删除。
 */
@Composable
fun FileManagerScreen(
    ssh: SshConfig,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var currentPath by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var transfer by remember { mutableStateOf<TransferInfo?>(null) }
    val transferMeter = remember { SpeedMeter() }

    // Dialog targets.
    var viewTarget by remember { mutableStateOf<Pair<FileEntry, String>?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var newFolderDialog by remember { mutableStateOf(false) }
    var folderText by remember { mutableStateOf("") }

    fun joinPath(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

    fun parentOf(path: String): String? {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return null
        val parent = trimmed.substringBeforeLast('/')
        return if (parent.isEmpty()) "/" else parent
    }

    fun load(path: String) {
        scope.launch {
            busy = true
            message = null
            entries = null
            try {
                entries = withContext(Dispatchers.IO) { SshFiles.list(ssh, path) }
            } catch (e: Exception) {
                message = e.message ?: "读取目录失败。"
                messageIsError = true
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(currentPath) { load(currentPath) }

    // 系统返回：在子目录时先逐级返回上级，到根目录后才退出页面。
    BackHandler(enabled = currentPath != "/") {
        currentPath = parentOf(currentPath) ?: "/"
    }

    fun runOp(info: String?, refreshList: Boolean = true, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = null
            try {
                withContext(Dispatchers.IO) { block() }
                message = info
                messageIsError = false
                // 就地重取目录，新建/删除/重命名/上传后立刻可见。
                if (refreshList) {
                    try {
                        entries = withContext(Dispatchers.IO) { SshFiles.list(ssh, currentPath) }
                    } catch (e: Exception) {
                        message = e.message ?: "刷新目录失败。"
                        messageIsError = true
                    }
                }
            } catch (e: Exception) {
                message = e.message ?: "操作失败。"
                messageIsError = true
            } finally {
                busy = false
            }
        }
    }

    /** Runs a transfer with the modal progress dialog (live speed + progress bar). */
    fun runTransfer(
        info: String,
        name: String,
        total: Long,
        isUpload: Boolean,
        refreshList: Boolean,
        op: suspend ((Long) -> Unit) -> Unit
    ) {
        scope.launch {
            busy = true
            message = null
            transferMeter.reset()
            transfer = TransferInfo(name, total, isUpload, 0L, 0f)
            try {
                op { sent ->
                    transfer = TransferInfo(name, total, isUpload, sent, transferMeter.sample(sent))
                }
                message = info
                messageIsError = false
                if (refreshList) {
                    try {
                        entries = withContext(Dispatchers.IO) { SshFiles.list(ssh, currentPath) }
                    } catch (e: Exception) {
                        message = e.message ?: "刷新目录失败。"
                        messageIsError = true
                    }
                }
            } catch (e: Exception) {
                message = e.message ?: "传输失败。"
                messageIsError = true
            } finally {
                transfer = null
                busy = false
            }
        }
    }

    fun viewFile(entry: FileEntry) {
        scope.launch {
            busy = true
            message = null
            try {
                val content = withContext(Dispatchers.IO) {
                    SshFiles.readText(ssh, joinPath(currentPath, entry.name))
                }
                viewTarget = entry to content
            } catch (e: Exception) {
                message = e.message ?: "读取文件失败。"
                messageIsError = true
            } finally {
                busy = false
            }
        }
    }

    // SAF: 下载到手机（create）与从手机上传（open）。
    var pendingDownloadName by remember { mutableStateOf<String?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val name = pendingDownloadName
        if (uri != null && name != null) {
            val size = entries?.firstOrNull { it.name == name }?.size ?: 0L
            if (size > MAX_DOWNLOAD_BYTES) {
                message = "文件过大（${formatBytes(size)}），暂不支持超过 ${formatBytes(MAX_DOWNLOAD_BYTES)} 的下载。"
                messageIsError = true
            } else {
                runTransfer("已保存到手机。", name, size, isUpload = false, refreshList = false) { onProgress ->
                    val bytes = SshFiles.download(ssh, joinPath(currentPath, name), onProgress)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw SshFileException("无法写入所选位置。")
                }
            }
        }
        pendingDownloadName = null
    }
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                // 先在本地读取并校验，再进入带进度弹窗的网络传输。
                val bytes = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw SshFileException("无法读取所选文件。")
                    }
                } catch (e: Exception) {
                    message = e.message ?: "读取所选文件失败。"
                    messageIsError = true
                    return@launch
                }
                if (bytes.size > MAX_UPLOAD_BYTES) {
                    message = "文件过大（${formatBytes(bytes.size.toLong())}），暂支持不超过 ${formatBytes(MAX_UPLOAD_BYTES.toLong())} 的上传。"
                    messageIsError = true
                    return@launch
                }
                val name = withContext(Dispatchers.IO) { queryDisplayName(context, uri) } ?: "upload.bin"
                runTransfer("上传完成。", name, bytes.size.toLong(), isUpload = true, refreshList = true) { onProgress ->
                    SshFiles.upload(ssh, joinPath(currentPath, name), bytes, onProgress)
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBackButton(onBack = onBack)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                    enabled = !busy
                ) { Text("上传") }
            }
            Text(
                "文件管理",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            // Breadcrumb: every segment is a tap target back up the tree.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val trimmed = currentPath.trimEnd('/')
                val segments = if (trimmed.isEmpty()) emptyList() else trimmed.removePrefix("/").split('/')
                Text(
                    "根目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (segments.isEmpty()) colors.primary else colors.onSurfaceVariant,
                    fontWeight = if (segments.isEmpty()) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clickable(enabled = segments.isNotEmpty()) { currentPath = "/" }
                        .padding(vertical = 8.dp)
                )
                segments.forEachIndexed { index, segment ->
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    val isLast = index == segments.lastIndex
                    Text(
                        segment,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isLast) colors.primary else colors.onSurfaceVariant,
                        fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clickable(enabled = !isLast) {
                                currentPath = "/" + segments.take(index + 1).joinToString("/")
                            }
                            .padding(vertical = 8.dp)
                    )
                }
            }

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

            when (val list = entries) {
                null -> if (!busy) {
                    Text(
                        message ?: "读取目录失败。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp)
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)
                ) {
                    val parent = parentOf(currentPath)
                    if (parent != null) {
                        item(key = "..") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentPath = parent }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "..　返回上一级",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.primary
                                )
                            }
                            HorizontalDivider(color = colors.outline)
                        }
                    }
                    if (list.isEmpty() && parent == null) {
                        item {
                            Text(
                                "此目录为空",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(top = 24.dp)
                            )
                        }
                    }
                    items(list, key = { it.name }) { entry ->
                        FileRow(
                            entry = entry,
                            busy = busy,
                            onOpen = {
                                if (entry.isDir) {
                                    currentPath = joinPath(currentPath, entry.name)
                                } else {
                                    viewFile(entry)
                                }
                            },
                            onView = { viewFile(entry) },
                            onDownload = {
                                pendingDownloadName = entry.name
                                downloadLauncher.launch(entry.name)
                            },
                            onRename = {
                                renameTarget = entry
                                renameText = entry.name
                            },
                            onDelete = { deleteTarget = entry }
                        )
                        HorizontalDivider(color = colors.outline)
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                folderText = ""
                newFolderDialog = true
            },
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(20.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "新建文件夹")
        }
    }

    // ---- Dialogs ----

    // 传输进度弹窗：模态显示实时进度与速度，传输期间不可关闭。
    transfer?.let { t ->
        val fraction = if (t.total > 0) (t.sent.toFloat() / t.total).coerceIn(0f, 1f) else 0f
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Surface(
                shape = AppShapes.card,
                color = colors.surface,
                border = BorderStroke(1.dp, colors.outline),
                modifier = Modifier.widthIn(min = 280.dp, max = 360.dp)
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text(
                        if (t.isUpload) "正在上传" else "正在下载",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.primary,
                        trackColor = colors.surfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${(fraction * 100).toInt()}%　${formatBytes(t.sent)} / ${formatBytes(t.total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Text(
                            formatRate(t.speedBps.toDouble()),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    viewTarget?.let { (entry, content) ->
        AppDialog(
            title = entry.name,
            confirmLabel = "关闭",
            dismissLabel = "",
            onConfirm = { viewTarget = null },
            onDismiss = { viewTarget = null }
        ) {
            Column(
                modifier = Modifier
                    .height(360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (entry.size > PREVIEW_BYTES) {
                    Text(
                        "文件较大，仅显示前 64 KB。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    content,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = colors.onSurface
                )
            }
        }
    }

    deleteTarget?.let { entry ->
        AppDialog(
            title = "删除${if (entry.isDir) "文件夹" else "文件"}",
            message = "确定删除 “${entry.name}” 吗？${if (entry.isDir) "文件夹及其全部内容将被删除。" else "此操作不可恢复。"}",
            confirmLabel = "删除",
            confirmColor = colors.error,
            onConfirm = {
                deleteTarget = null
                runOp("已删除。") { SshFiles.delete(ssh, joinPath(currentPath, entry.name), entry.isDir) }
            },
            onDismiss = { deleteTarget = null }
        )
    }

    renameTarget?.let { entry ->
        AppDialog(
            title = "重命名",
            confirmEnabled = renameText.isNotBlank() && renameText.trim() != entry.name,
            onConfirm = {
                val from = joinPath(currentPath, entry.name)
                val to = joinPath(currentPath, renameText.trim().trim('/'))
                renameTarget = null
                runOp("已重命名。") { SshFiles.rename(ssh, from, to) }
            },
            onDismiss = { renameTarget = null }
        ) {
            OutlinedTextField(
                value = renameText,
                onValueChange = { renameText = it },
                singleLine = true,
                label = { Text("新名称") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (newFolderDialog) {
        AppDialog(
            title = "新建文件夹",
            confirmLabel = "创建",
            confirmEnabled = folderText.isNotBlank(),
            onConfirm = {
                val path = joinPath(currentPath, folderText.trim().trim('/'))
                newFolderDialog = false
                runOp("已创建文件夹。") { SshFiles.mkdir(ssh, path) }
            },
            onDismiss = { newFolderDialog = false }
        ) {
            OutlinedTextField(
                value = folderText,
                onValueChange = { folderText = it },
                singleLine = true,
                label = { Text("文件夹名称") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    busy: Boolean,
    onOpen: () -> Unit,
    onView: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LocalAppColors.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onOpen)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Description,
            contentDescription = null,
            tint = if (entry.isDir) colors.primary else colors.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (entry.isDir) "文件夹" else formatBytes(entry.size),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = !busy) {
                Icon(Icons.Filled.MoreVert, contentDescription = "操作", tint = colors.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!entry.isDir) {
                    DropdownMenuItem(
                        text = { Text("查看内容") },
                        onClick = { menuOpen = false; onView() }
                    )
                    DropdownMenuItem(
                        text = { Text("下载") },
                        onClick = { menuOpen = false; onDownload() }
                    )
                }
                DropdownMenuItem(
                    text = { Text("重命名") },
                    onClick = { menuOpen = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("删除", color = colors.error) },
                    onClick = { menuOpen = false; onDelete() }
                )
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

/** Live transfer state shown in the progress dialog; updates come from SSH callback threads. */
private data class TransferInfo(
    val name: String,
    val total: Long,
    val isUpload: Boolean,
    val sent: Long,
    val speedBps: Float
)

/** Smooths periodic byte samples into a display-friendly transfer speed. */
private class SpeedMeter {
    private var lastAt = 0L
    private var lastBytes = 0L
    private var smoothed = 0f

    fun sample(bytes: Long): Float {
        val now = System.currentTimeMillis()
        if (lastAt > 0) {
            val dt = now - lastAt
            if (dt > 0) {
                val instant = (bytes - lastBytes).coerceAtLeast(0) * 1000f / dt
                smoothed = if (smoothed == 0f) instant else smoothed * 0.65f + instant * 0.35f
            }
        }
        lastAt = now
        lastBytes = bytes
        return smoothed
    }

    fun reset() {
        lastAt = 0
        lastBytes = 0
        smoothed = 0f
    }
}
