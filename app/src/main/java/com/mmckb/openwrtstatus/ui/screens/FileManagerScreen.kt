package com.mmckb.openwrtstatus.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.FileEntry
import com.mmckb.openwrtstatus.data.ssh.SshCancelledException
import com.mmckb.openwrtstatus.data.ssh.SshFileException
import com.mmckb.openwrtstatus.data.ssh.SshFiles
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.formatBytes
import com.mmckb.openwrtstatus.ui.formatRate
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SEARCH_LIMIT = 200
private val ARCHIVE_EXTENSIONS = listOf(".zip", ".tar", ".tgz", ".tar.gz", ".tar.bz2", ".tar.xz")

/** Live transfer state shown in the progress dialog; updates come from SSH callback threads. */
private data class TransferInfo(
    val name: String,
    val total: Long,
    val isUpload: Boolean,
    val sent: Long,
    val speedBps: Float
)

private val ARCHIVE_SORT = compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() }

/** Zip 中央目录只存在于文件末尾；先小后大拉取尾部尝试解析。 */
private val ZIP_TAIL_BYTES = longArrayOf(256L * 1024, 4L * 1024 * 1024)

/**
 * 从 zip 尾部数据里解析中央目录（EOCD → 中央目录条目）。
 * 返回 null 表示尾部不够（目录被截断）或 zip64，需要换更大的尾部/整包下载。
 */
private fun parseZipCentralDirectory(data: ByteArray, fileTotalSize: Long): List<FileEntry>? {
    fun u16(off: Int): Int = (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
    fun u32(off: Int): Long = (u16(off).toLong() and 0xFFFF) or ((u16(off + 2).toLong() and 0xFFFF) shl 16)

    // 从末尾向前找 EOCD 签名 PK\x05\x06（注释最长 64KB）。
    var i = data.size - 22
    val scanFloor = maxOf(0, data.size - 22 - 65535 - 16)
    while (i >= scanFloor) {
        if (data[i] == 0x50.toByte() && data[i + 1] == 0x4B.toByte() &&
            data[i + 2] == 0x05.toByte() && data[i + 3] == 0x06.toByte()
        ) break
        i--
    }
    if (i < 0) return null
    val entriesTotal = u16(i + 10)
    val cdSize = u32(i + 12)
    val cdOffset = u32(i + 16)
    if (entriesTotal == 0 || entriesTotal == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) return null

    var pos = (cdOffset - (fileTotalSize - data.size)).toInt()
    if (pos < 0 || pos >= data.size) return null
    val end = minOf(data.size.toLong(), pos.toLong() + cdSize).toInt()

    val result = mutableListOf<FileEntry>()
    while (pos + 46 <= end && result.size < entriesTotal) {
        if (u32(pos) != 0x02014B50L) break
        val nameLen = u16(pos + 28)
        val extraLen = u16(pos + 30)
        val commentLen = u16(pos + 32)
        val uncompSize = u32(pos + 24)
        if (pos + 46 + nameLen > data.size) break
        val name = String(data, pos + 46, nameLen, Charsets.UTF_8)
        val isDir = name.endsWith("/")
        result.add(FileEntry(name.removeSuffix("/"), isDir, if (isDir) 0L else uncompSize))
        pos += 46 + nameLen + extraLen + commentLen
    }
    return if (result.isEmpty()) null else result.sortedWith(ARCHIVE_SORT)
}

/** Lists zip entries from locally downloaded bytes (no remote unzip needed). */
private fun parseZipEntries(context: Context, bytes: ByteArray): List<FileEntry> {
    val tmp = java.io.File(context.cacheDir, "fm-archive-${System.currentTimeMillis()}.zip")
    tmp.writeBytes(bytes)
    try {
        java.util.zip.ZipFile(tmp).use { zf ->
            return zf.entries().asSequence()
                .filter { it.name.isNotEmpty() && it.name != "/" }
                .map { e ->
                    if (e.isDirectory) FileEntry(e.name.removeSuffix("/"), true, 0L)
                    else FileEntry(e.name, false, e.size)
                }
                .sortedWith(ARCHIVE_SORT)
                .toList()
        }
    } finally {
        tmp.delete()
    }
}

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

/** One recursive-search hit: absolute remote path plus its type. */
private data class SearchHit(val path: String, val name: String, val isDir: Boolean)

/** Names staged for paste-into-folder; [isMove] distinguishes copy from cut. */
private data class ClipboardContent(val sourceDir: String, val names: List<String>, val isMove: Boolean)

/** Pending upload that collides with an existing name. */
private class UploadConflict(val name: String, val bytes: ByteArray)

/**
 * 文件管理页（二级页，独立 Activity）：基于 SSH exec 浏览路由器目录。
 * 支持文本编辑器、压缩包浏览、搜索与递归查找、路径跳转、多选删除、
 * 复制/剪切粘贴、移动，以及上传同名时的覆盖/重命名/取消选择。
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
    val transferCancel = remember { mutableStateOf(false) }

    // Dialog targets.
    var viewTarget by remember { mutableStateOf<String?>(null) }
    var viewName by remember { mutableStateOf("") }
    var editorText by remember { mutableStateOf("") }
    var editorBinary by remember { mutableStateOf(false) }
    var archiveTarget by remember { mutableStateOf<Pair<String, List<FileEntry>>?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var multiDelete by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    var newFolderDialog by remember { mutableStateOf(false) }
    var folderText by remember { mutableStateOf("") }
    var jumpDialog by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    var uploadConflict by remember { mutableStateOf<UploadConflict?>(null) }

    // 搜索（输入即过滤当前目录；回车提交递归查找）。
    var searchQuery by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchHits by remember { mutableStateOf<List<SearchHit>?>(null) }

    // 多选与剪贴板。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var clipboard by remember { mutableStateOf<ClipboardContent?>(null) }

    fun joinPath(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

    fun parentOf(path: String): String? {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return null
        val parent = trimmed.substringBeforeLast('/')
        return if (parent.isEmpty()) "/" else parent
    }

    fun normalizePath(input: String): String {
        var p = input.trim().replace(Regex("/+"), "/")
        if (!p.startsWith("/")) p = "/$p"
        p = p.trimEnd('/')
        return if (p.isEmpty()) "/" else p
    }

    fun isArchive(name: String): Boolean =
        ARCHIVE_EXTENSIONS.any { name.lowercase().endsWith(it) }

    /** First available "name (n).ext" candidate for the overwrite-avoiding rename upload. */
    fun uniqueName(original: String): String {
        val existing = entries?.map { it.name }?.toSet() ?: emptySet()
        val dot = original.lastIndexOf('.')
        val base = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (candidate !in existing) return candidate
            i++
        }
    }

    fun navigate(path: String) {
        currentPath = normalizePath(path)
        searchQuery = ""
        searchHits = null
        searching = false
        selectionMode = false
        selectedNames = emptySet()
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

    // 系统返回：先退出选择/搜索，再逐级返回上级目录，到根目录后才退出页面。
    BackHandler(enabled = selectionMode || searchHits != null || currentPath != "/") {
        when {
            selectionMode -> {
                selectionMode = false
                selectedNames = emptySet()
            }
            searchHits != null -> searchHits = null
            else -> currentPath = parentOf(currentPath) ?: "/"
        }
    }

    fun runOp(info: String?, refreshList: Boolean = true, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = null
            var errorText: String? = null
            try {
                withContext(Dispatchers.IO) { block() }
                if (info != null) {
                    message = info
                    messageIsError = false
                }
            } catch (e: Exception) {
                errorText = e.message ?: "操作失败。"
            }
            // 就地重取目录，让新建/删除/重命名/粘贴后立刻可见（失败时也刷新已生效的部分）。
            if (refreshList) {
                try {
                    entries = withContext(Dispatchers.IO) { SshFiles.list(ssh, currentPath) }
                } catch (e: Exception) {
                    errorText = errorText ?: (e.message ?: "刷新目录失败。")
                }
            }
            errorText?.let {
                message = it
                messageIsError = true
            }
            busy = false
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
            transferCancel.value = false
            transfer = TransferInfo(name, total, isUpload, 0L, 0f)
            try {
                op { sent ->
                    transfer = TransferInfo(name, total, isUpload, sent, transferMeter.sample(sent))
                }
                if (info.isNotEmpty()) {
                    message = info
                    messageIsError = false
                }
                if (refreshList) {
                    try {
                        entries = withContext(Dispatchers.IO) { SshFiles.list(ssh, currentPath) }
                    } catch (e: Exception) {
                        message = e.message ?: "刷新目录失败。"
                        messageIsError = true
                    }
                }
            } catch (e: SshCancelledException) {
                message = "已取消。"
                messageIsError = false
            } catch (e: Exception) {
                message = e.message ?: "传输失败。"
                messageIsError = true
            } finally {
                transfer = null
                busy = false
            }
        }
    }

    fun startUpload(name: String, bytes: ByteArray) {
        runTransfer("上传完成。", name, bytes.size.toLong(), isUpload = true, refreshList = true) { onProgress ->
            SshFiles.upload(ssh, joinPath(currentPath, name), bytes, onProgress) { transferCancel.value }
        }
    }

    fun viewFile(fullPath: String, name: String) {
        scope.launch {
            busy = true
            message = null
            try {
                val content = withContext(Dispatchers.IO) { SshFiles.readText(ssh, fullPath) }
                editorText = content
                editorBinary = content.contains('\u0000')
                viewName = name
                viewTarget = fullPath
            } catch (e: Exception) {
                message = e.message ?: "读取文件失败。"
                messageIsError = true
            } finally {
                busy = false
            }
        }
    }

    /**
     * 打开压缩包：
     * - tar/tgz/bz2/xz 走远端 `tar -tvf`（BusyBox 必有 tar，秒出列表）；
     * - zip 只拉文件末尾的中央目录（EOCD 技巧，通常仅几十 KB）即刻解析，
     *   解析不出时才回退整包下载 + 本地 ZipFile；全程可取消、带进度。
     */
    fun openArchive(fullPath: String, name: String) {
        val lower = name.lowercase()
        val size = entries?.firstOrNull { it.name == name }?.size ?: 0L
        when {
            lower.endsWith(".zip") -> runTransfer(
                "", name, minOf(size, ZIP_TAIL_BYTES.first()), isUpload = false, refreshList = false
            ) { onProgress ->
                archiveTarget = fullPath to fetchZipListing(fullPath, size, onProgress)
            }
            else -> {
                scope.launch {
                    busy = true
                    message = null
                    try {
                        val list = withContext(Dispatchers.IO) { SshFiles.listArchive(ssh, fullPath) }
                        archiveTarget = fullPath to list
                    } catch (e: Exception) {
                        message = e.message ?: "读取压缩包失败。"
                        messageIsError = true
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    private suspend fun fetchZipListing(
        fullPath: String,
        totalSize: Long,
        onProgress: (Long) -> Unit
    ): List<FileEntry> {
        val cancelled = { transferCancel.value }
        if (totalSize > 0) {
            for (tailLen in ZIP_TAIL_BYTES) {
                if (cancelled()) throw SshCancelledException()
                val len = minOf(totalSize, tailLen)
                val data = SshFiles.tail(ssh, fullPath, len, cancelled)
                onProgress(len)
                val parsed = parseZipCentralDirectory(data, totalSize)
                if (parsed != null) return parsed
            }
        }
        // 兜底：整包下载后用本地 ZipFile 解析。
        val bytes = SshFiles.download(ssh, fullPath, onProgress, cancelled)
        return parseZipEntries(context, bytes)
    }

    fun openEntry(fullPath: String, name: String, isDir: Boolean) {
        when {
            isDir -> navigate(fullPath)
            isArchive(name) -> openArchive(fullPath, name)
            else -> viewFile(fullPath, name)
        }
    }

    fun runSearch() {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            searchHits = null
            return
        }
        scope.launch {
            searching = true
            message = null
            try {
                val hits = withContext(Dispatchers.IO) { SshFiles.find(ssh, currentPath, q, SEARCH_LIMIT) }
                searchHits = hits.map { (p, isDir) -> SearchHit(p, p.substringAfterLast('/'), isDir) }
            } catch (e: Exception) {
                message = e.message ?: "搜索失败。"
                messageIsError = true
            } finally {
                searching = false
            }
        }
    }

    fun pasteClipboard() {
        val clip = clipboard ?: return
        runOp(if (clip.isMove) "已移动 ${clip.names.size} 项。" else "已复制 ${clip.names.size} 项。") {
            val failures = mutableListOf<String>()
            for (n in clip.names) {
                try {
                    if (clip.isMove) {
                        SshFiles.rename(ssh, joinPath(clip.sourceDir, n), joinPath(currentPath, n))
                    } else {
                        SshFiles.copy(ssh, joinPath(clip.sourceDir, n), currentPath)
                    }
                } catch (e: Exception) {
                    failures += n
                }
            }
            if (failures.isNotEmpty()) {
                throw SshFileException("以下条目处理失败：${failures.joinToString("、")}")
            }
        }
        clipboard = null
    }

    fun deleteSelected() {
        val names = selectedNames.toList()
        val isDirByName = entries?.associate { it.name to it.isDir } ?: emptyMap()
        multiDelete = false
        selectionMode = false
        selectedNames = emptySet()
        runOp("已删除 ${names.size} 项。") {
            val failures = mutableListOf<String>()
            for (n in names) {
                try {
                    SshFiles.delete(ssh, joinPath(currentPath, n), isDirByName[n] ?: false)
                } catch (e: Exception) {
                    failures += n
                }
            }
            if (failures.isNotEmpty()) {
                throw SshFileException("以下条目删除失败：${failures.joinToString("、")}")
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
            runTransfer("已保存到手机。", name, size, isUpload = false, refreshList = false) { onProgress ->
                val bytes = SshFiles.download(ssh, joinPath(currentPath, name), onProgress) { transferCancel.value }
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw SshFileException("无法写入所选位置。")
            }
        }
        pendingDownloadName = null
    }
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                // 先在本地读取，再决定是否需要同名询问，最后进入带进度弹窗的传输。
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
                val name = withContext(Dispatchers.IO) { queryDisplayName(context, uri) } ?: "upload.bin"
                if (entries?.any { it.name == name } == true) {
                    uploadConflict = UploadConflict(name, bytes)
                } else {
                    startUpload(name, bytes)
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
                    onClick = {
                        selectionMode = !selectionMode
                        selectedNames = emptySet()
                    },
                    enabled = !busy
                ) { Text(if (selectionMode) "取消选择" else "选择") }
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
            // Breadcrumb: every segment is a tap target back up the tree; pencil jumps to a path.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
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
                            .clip(AppShapes.pill)
                            .clickable(enabled = segments.isNotEmpty()) { navigate("/") }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
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
                                .clip(AppShapes.pill)
                                .clickable(enabled = !isLast) { navigate("/" + segments.take(index + 1).joinToString("/")) }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
                IconButton(
                    onClick = {
                        jumpText = currentPath
                        jumpDialog = true
                    },
                    enabled = !busy
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "跳转到路径",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 紧凑圆角搜索条：输入即时过滤当前目录，键盘搜索键递归查找。
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = colors.surfaceVariant,
                border = BorderStroke(1.dp, colors.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                "搜索",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                searchHits = null
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
                            cursorBrush = SolidColor(colors.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            searchHits = null
                        }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "清除",
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            if (selectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已选 ${selectedNames.size} 项",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface
                    )
                    Row {
                        TextButton(
                            onClick = {
                                clipboard = ClipboardContent(currentPath, selectedNames.toList(), isMove = false)
                                selectionMode = false
                                selectedNames = emptySet()
                            },
                            enabled = selectedNames.isNotEmpty()
                        ) { Text("复制") }
                        TextButton(
                            onClick = {
                                clipboard = ClipboardContent(currentPath, selectedNames.toList(), isMove = true)
                                selectionMode = false
                                selectedNames = emptySet()
                            },
                            enabled = selectedNames.isNotEmpty()
                        ) { Text("移动") }
                        TextButton(
                            onClick = { if (selectedNames.isNotEmpty()) multiDelete = true },
                            enabled = selectedNames.isNotEmpty()
                        ) { Text("删除", color = colors.error) }
                    }
                }
            }
            clipboard?.let { clip ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已${if (clip.isMove) "剪切" else "复制"} ${clip.names.size} 项",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.primary
                    )
                    Row {
                        TextButton(onClick = { pasteClipboard() }, enabled = !busy) { Text("粘贴到此处") }
                        TextButton(onClick = { clipboard = null }) {
                            Text("取消", color = colors.onSurfaceVariant)
                        }
                    }
                }
            }

            if (busy || searching) {
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

            when {
                searchHits != null -> {
                    val hits = searchHits.orEmpty()
                    if (hits.isEmpty()) {
                        Text(
                            "未找到 “${searchQuery.trim()}” 相关内容",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 24.dp)
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 88.dp)
                    ) {
                        items(hits, key = { it.path }) { hit ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy) {
                                        if (hit.isDir) navigate(hit.path)
                                        else openEntry(hit.path, hit.name, false)
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (hit.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                                    contentDescription = null,
                                    tint = if (hit.isDir) colors.primary else colors.onSurfaceVariant
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        hit.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        hit.path.substringBeforeLast('/'),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            HorizontalDivider(color = colors.outline)
                        }
                    }
                }
                else -> {
                    val list = entries
                    when {
                        list == null && !busy -> Text(
                            message ?: "读取目录失败。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 24.dp)
                        )
                        list != null -> {
                            val filtered = if (searchQuery.isBlank()) {
                                list
                            } else {
                                list.filter { it.name.contains(searchQuery, ignoreCase = true) }
                            }
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(bottom = 88.dp)
                            ) {
                                val parent = parentOf(currentPath)
                                if (parent != null) {
                                    item(key = "..") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { navigate(parent) }
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
                                if (filtered.isEmpty() && searchQuery.isNotBlank()) {
                                    item {
                                        Text(
                                            "当前目录无匹配项",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 24.dp)
                                        )
                                    }
                                } else if (filtered.isEmpty() && parent == null) {
                                    item {
                                        Text(
                                            "此目录为空",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 24.dp)
                                        )
                                    }
                                }
                                items(filtered, key = { it.name }) { entry ->
                                    FileRow(
                                        entry = entry,
                                        busy = busy,
                                        selectionMode = selectionMode,
                                        selected = entry.name in selectedNames,
                                        onOpen = {
                                            if (entry.isDir) {
                                                navigate(joinPath(currentPath, entry.name))
                                            } else {
                                                openEntry(joinPath(currentPath, entry.name), entry.name, false)
                                            }
                                        },
                                        onLongPress = {
                                            if (!selectionMode) {
                                                selectionMode = true
                                                selectedNames = setOf(entry.name)
                                            }
                                        },
                                        onToggleSelect = {
                                            selectedNames = if (entry.name in selectedNames) {
                                                selectedNames - entry.name
                                            } else {
                                                selectedNames + entry.name
                                            }
                                        },
                                        onView = {
                                            openEntry(joinPath(currentPath, entry.name), entry.name, false)
                                        },
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
            Icon(Icons.Filled.CreateNewFolder, contentDescription = "新建文件夹")
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
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { transferCancel.value = true },
                            enabled = !transferCancel.value
                        ) {
                            Text(if (transferCancel.value) "正在取消…" else "取消")
                        }
                    }
                }
            }
        }
    }

    // 内置文本编辑器（二进制文件只读提示）。
    viewTarget?.let { path ->
        AppDialog(
            title = viewName,
            confirmLabel = "保存",
            dismissLabel = "关闭",
            confirmEnabled = !editorBinary,
            onConfirm = {
                val text = editorText
                viewTarget = null
                runOp("已保存。", refreshList = true) {
                    SshFiles.upload(ssh, path, text.toByteArray(Charsets.UTF_8))
                }
            },
            onDismiss = { viewTarget = null }
        ) {
            if (editorBinary) {
                Text(
                    "二进制文件，不支持编辑，可下载到手机查看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = editorText,
                onValueChange = { if (!editorBinary) editorText = it },
                readOnly = editorBinary,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
            )
        }
    }

    // 压缩包内容浏览。
    archiveTarget?.let { (path, list) ->
        AppDialog(
            title = path.substringAfterLast('/'),
            confirmLabel = "关闭",
            dismissLabel = "",
            onConfirm = { archiveTarget = null },
            onDismiss = { archiveTarget = null }
        ) {
            Text(
                "压缩包内容（${list.size} 项）",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .height(360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (list.isEmpty()) {
                    Text(
                        "空压缩包",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
                list.forEach { e ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (e.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                            contentDescription = null,
                            tint = if (e.isDir) colors.primary else colors.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                e.name,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (e.isDir) "文件夹" else formatBytes(e.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
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

    if (multiDelete) {
        AppDialog(
            title = "删除所选",
            message = "确定删除已选的 ${selectedNames.size} 项吗？文件夹及其全部内容将被删除，不可恢复。",
            confirmLabel = "删除",
            confirmColor = colors.error,
            onConfirm = { deleteSelected() },
            onDismiss = { multiDelete = false }
        )
    }

    renameTarget?.let { entry ->
        val newName = renameText.trim().trim('/')
        val nameTaken = newName != entry.name && entries?.any { it.name == newName } == true
        AppDialog(
            title = "重命名",
            confirmEnabled = renameText.isNotBlank() && newName != entry.name && !nameTaken,
            onConfirm = {
                val from = joinPath(currentPath, entry.name)
                val to = joinPath(currentPath, newName)
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
            if (nameTaken) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "该名称已被占用，请换一个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }
        }
    }

    if (newFolderDialog) {
        val folderName = folderText.trim().trim('/')
        val folderTaken = entries?.any { it.name == folderName } == true
        AppDialog(
            title = "新建文件夹",
            confirmLabel = "创建",
            confirmEnabled = folderText.isNotBlank() && !folderTaken,
            onConfirm = {
                val path = joinPath(currentPath, folderName)
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
            if (folderTaken) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "该名称已被占用，请换一个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }
        }
    }

    if (jumpDialog) {
        AppDialog(
            title = "跳转到路径",
            confirmEnabled = jumpText.isNotBlank(),
            onConfirm = {
                val target = normalizePath(jumpText)
                jumpDialog = false
                navigate(target)
            },
            onDismiss = { jumpDialog = false }
        ) {
            OutlinedTextField(
                value = jumpText,
                onValueChange = { jumpText = it },
                singleLine = true,
                label = { Text("绝对路径，如 /etc/config") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // 上传同名冲突：覆盖 / 重命名 / 取消。
    uploadConflict?.let { c ->
        Dialog(onDismissRequest = { uploadConflict = null }) {
            Surface(
                shape = AppShapes.card,
                color = colors.surface,
                border = BorderStroke(1.dp, colors.outline),
                modifier = Modifier.widthIn(min = 280.dp, max = 360.dp)
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text(
                        "文件已存在",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "“${c.name}” 已存在于此文件夹。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { uploadConflict = null }) {
                            Text("取消", color = colors.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = {
                            val newName = uniqueName(c.name)
                            uploadConflict = null
                            startUpload(newName, c.bytes)
                        }) { Text("重命名上传") }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = {
                            val n = c.name
                            uploadConflict = null
                            startUpload(n, c.bytes)
                        }) { Text("覆盖", color = colors.error) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    busy: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
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
            .combinedClickable(
                enabled = !busy,
                onClick = { if (selectionMode) onToggleSelect() else onOpen() },
                onLongClick = onLongPress
            )
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
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
        } else {
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !busy) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "操作", tint = colors.onSurfaceVariant)
                }
                if (menuOpen) {
                    // 应用自己的弹层（扁平卡片风格），替代 Material3 DropdownMenu。
                    Popup(
                        alignment = Alignment.BottomEnd,
                        onDismissRequest = { menuOpen = false },
                        properties = PopupProperties(focusable = true)
                    ) {
                        Surface(
                            shape = AppShapes.block,
                            color = colors.surface,
                            border = BorderStroke(1.dp, colors.outline),
                            modifier = Modifier.widthIn(min = 150.dp)
                        ) {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                if (!entry.isDir) {
                                    PopupLabel("查看 / 编辑", colors.onSurface) { menuOpen = false; onView() }
                                    PopupLabel("下载", colors.onSurface) { menuOpen = false; onDownload() }
                                }
                                PopupLabel("重命名", colors.onSurface) { menuOpen = false; onRename() }
                                PopupLabel("删除", colors.error) { menuOpen = false; onDelete() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PopupLabel(label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = tint,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp)
    )
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
