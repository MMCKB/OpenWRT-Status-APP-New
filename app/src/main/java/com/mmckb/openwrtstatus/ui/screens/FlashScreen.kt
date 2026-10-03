package com.mmckb.openwrtstatus.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.remote.FirmwareCheck
import com.mmckb.openwrtstatus.data.remote.FlashClient
import com.mmckb.openwrtstatus.data.remote.FlashInfo
import com.mmckb.openwrtstatus.data.remote.RouterException
import com.mmckb.openwrtstatus.ui.components.AppAlertType
import com.mmckb.openwrtstatus.ui.components.AppIconButton
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.components.AppSwitch
import com.mmckb.openwrtstatus.ui.components.AppTextField
import com.mmckb.openwrtstatus.ui.components.ConnectionMonitor
import com.mmckb.openwrtstatus.ui.components.SmoothOptionSwitcher
import com.mmckb.openwrtstatus.ui.components.StackedAlertHost
import com.mmckb.openwrtstatus.ui.components.rememberAlertStackState
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 备份/刷写等长操作的总时长上限。 */
private const val FLASH_LOAD_TIMEOUT_MS = 60_000L
private const val FLASH_TRANSFER_TIMEOUT_MS = 300_000L

/** 统一的失败文案：超时（连接中断/无响应）给出可操作的提示，其余透出原始信息。 */
private fun flashErrText(e: Exception): String = when {
    e is TimeoutCancellationException -> "路由器连接中断或长时间无响应，请检查网络后重试。"
    e is CancellationException -> "连接已断开，操作已终止。"
    else -> e.message ?: "请稍后重试。"
}

private data class FlashSelectState(
    val title: String,
    val options: List<Pair<String, String>>,
    val selected: String,
    val onPick: (String) -> Unit
)

private fun fwHumanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

/**
 * 备份与更新页（LuCI admin/system/flash 的完整复刻）：
 * 操作页签（生成备份 / 出厂重置 / 恢复配置 / 刷写固件，含 sysupgrade --test 校验、
 * 保留配置与强制刷写选项、mtdblock 下载）+ 配置页签（/etc/sysupgrade.conf 编辑）。
 * 备份经 sysupgrade -b 生成 tar.gz 后下载；恢复与刷写经 SSH 上传后执行；
 * 恢复与刷写完成后设备会自行重启。需要设备开启 SSH。
 */
@Composable
fun FlashScreen(
    config: RouterConfig,
    sshEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val client = remember { FlashClient() }
    val sshOrNull = remember(config) {
        if (sshEnabled) {
            SshConfig(
                host = config.sshHost.ifBlank { config.ip },
                port = config.sshPort,
                username = config.sshUsername,
                password = config.sshPassword
            )
        } else null
    }

    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val alertStack = rememberAlertStackState()
    var tab by remember { mutableStateOf("actions") }

    fun setAlert(type: AppAlertType, title: String, description: String? = null) {
        alertStack.push(type, title, description)
    }

    /** 点击操作前的快速检查：已断开则直接红色提示，不再发起操作。 */
    fun ensureConnected(): Boolean {
        if (ConnectionMonitor.status.value == ConnectionMonitor.Status.Offline) {
            setAlert(AppAlertType.Error, "与路由器已断开连接", "请检查手机与路由器的网络后重试。")
            return false
        }
        return true
    }

    // 跟踪进行中的任务；路由器断开连接时立即取消，按钮即刻恢复
    var opJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) {
        ConnectionMonitor.status.collect { status ->
            if (status == ConnectionMonitor.Status.Offline) opJob?.cancel()
        }
    }

    // busy 看门狗：每个操作带总预算，超时后无条件恢复按钮，杜绝「正在…」永不消失
    var busyDeadline by remember { mutableStateOf(Long.MAX_VALUE) }
    fun beginBusy(budgetMs: Long) {
        busy = true
        busyDeadline = System.currentTimeMillis() + budgetMs
    }
    LaunchedEffect(busy, busyDeadline) {
        if (!busy) return@LaunchedEffect
        val remain = busyDeadline - System.currentTimeMillis()
        if (remain > 0) delay(remain)
        if (busy) {
            busy = false
            setAlert(AppAlertType.Error, "操作超时", "路由器长时间无响应，已中止等待，请检查连接后重试。")
        }
    }

    var info by remember { mutableStateOf<FlashInfo?>(null) }
    var confContent by remember { mutableStateOf("") }
    var selectState by remember { mutableStateOf<FlashSelectState?>(null) }
    var confirmKind by remember { mutableStateOf<String?>(null) }
    var flashKeep by remember { mutableStateOf(true) }
    var flashForce by remember { mutableStateOf(false) }
    var mtdSelected by remember { mutableStateOf("") }
    var mtdPendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var flashCheck by remember {
        mutableStateOf(
            FirmwareCheck(valid = false, size = 0L, md5 = "", sha256 = "", output = "")
        )
    }

    fun load() {
        if (opJob?.isActive == true) return
        if (!ensureConnected()) {
            loading = false
            return
        }
        opJob = scope.launch {
            loading = true
            try {
                info = withTimeout(FLASH_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.loadInfo(config, sshOrNull) }
                }
                confContent = withTimeout(FLASH_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.readSysupgradeConf(config, sshOrNull) }
                }
                mtdSelected = info?.mtdBlocks?.firstOrNull()?.first ?: ""
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "读取失败", flashErrText(e))
            } finally {
                loading = false
            }
        }
    }

    var loadedOnce by remember { mutableStateOf(false) }
    if (!loadedOnce) {
        loadedOnce = true
        load()
    }

    val backupDownloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-gzip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                beginBusy(FLASH_TRANSFER_TIMEOUT_MS + 30_000)
                try {
                    val bytes = withTimeout(FLASH_TRANSFER_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { client.generateBackup(config, sshOrNull) }
                    }
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(bytes)
                    }
                    setAlert(AppAlertType.Success, "备份已生成并下载")
                } catch (e: Exception) {
                    setAlert(AppAlertType.Error, "备份失败", flashErrText(e))
                } finally {
                    busy = false
                }
            }
        }
    }

    val restorePickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                beginBusy(FLASH_TRANSFER_TIMEOUT_MS + 120_000)
                try {
                    val bytes = withTimeout(FLASH_TRANSFER_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        } ?: ByteArray(0)
                    }
                    if (bytes.isEmpty()) {
                        setAlert(AppAlertType.Error, "读取备份文件失败", "无法从所选文件读取内容。")
                        return@launch
                    }
                    val s = sshOrNull
                    if (s == null) {
                        setAlert(AppAlertType.Error, "需要开启 SSH", "备份与更新需要 SSH 访问，请在设备编辑页开启后重试。")
                        return@launch
                    }
                    withTimeout(FLASH_TRANSFER_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            com.mmckb.openwrtstatus.data.ssh.SshFiles.upload(
                                s, "/tmp/backup.tar.gz", bytes
                            )
                        }
                    }
                    val ok = withTimeout(FLASH_LOAD_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            client.verifyRestoreArchive(config, sshOrNull)
                        }
                    }
                    if (!ok) {
                        setAlert(AppAlertType.Error, "备份存档不可读", "上传的文件不是有效的配置备份。")
                        return@launch
                    }
                    confirmKind = "restore"
                } catch (e: Exception) {
                    setAlert(AppAlertType.Error, "上传备份失败", flashErrText(e))
                } finally {
                    busy = false
                }
            }
        }
    }

    val firmwarePickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                beginBusy(FLASH_TRANSFER_TIMEOUT_MS + 180_000)
                try {
                    val bytes = withTimeout(FLASH_TRANSFER_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        } ?: ByteArray(0)
                    }
                    if (bytes.isEmpty()) {
                        setAlert(AppAlertType.Error, "读取固件文件失败", "无法从所选文件读取内容。")
                        return@launch
                    }
                    val s = sshOrNull
                    if (s == null) {
                        setAlert(AppAlertType.Error, "需要开启 SSH", "备份与更新需要 SSH 访问，请在设备编辑页开启后重试。")
                        return@launch
                    }
                    withTimeout(FLASH_TRANSFER_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            com.mmckb.openwrtstatus.data.ssh.SshFiles.upload(
                                s, "/tmp/firmware.bin", bytes
                            )
                        }
                    }
                    val check = withTimeout(FLASH_LOAD_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { client.testFirmware(config, sshOrNull) }
                    }
                    flashCheck = check
                    if (!check.valid) {
                        setAlert(
                            AppAlertType.Error, "固件校验未通过",
                            check.output.ifBlank { "sysupgrade --test 未通过，请确认固件与设备匹配。" }
                        )
                        return@launch
                    }
                    confirmKind = "flash"
                } catch (e: Exception) {
                    setAlert(AppAlertType.Error, "固件校验失败", flashErrText(e))
                } finally {
                    busy = false
                }
            }
        }
    }

    val mtdDownloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val bytes = mtdPendingBytes
        if (uri != null && bytes != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(bytes)
                }
                android.widget.Toast.makeText(context, "已下载", android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.widget.Toast.makeText(context, "下载失败：${it.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        mtdPendingBytes = null
    }

    fun saveConf() {
        if (!ensureConnected()) return
        scope.launch {
            beginBusy(FLASH_LOAD_TIMEOUT_MS + 30_000)
            try {
                withTimeout(FLASH_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        client.writeSysupgradeConf(config, sshOrNull, confContent)
                    }
                }
                setAlert(AppAlertType.Success, "已保存")
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "保存失败", flashErrText(e))
            } finally {
                busy = false
            }
        }
    }

    fun startFlash() {
        if (!ensureConnected()) return
        scope.launch {
            beginBusy(60_000)
            try {
                withTimeout(FLASH_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        client.flashFirmware(config, sshOrNull, flashKeep, flashForce)
                    }
                }
                setAlert(
                    AppAlertType.Warning, "正在刷写固件",
                    "设备正在写入固件并即将重启，过程中请保持供电。重启完成后即可重新连接。"
                )
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "刷写失败", flashErrText(e))
            } finally {
                busy = false
            }
        }
    }

    fun startRestore() {
        scope.launch {
            beginBusy(120_000)
            try {
                withTimeout(FLASH_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.restoreBackup(config, sshOrNull) }
                }
                setAlert(
                    AppAlertType.Warning, "正在恢复配置",
                    "配置已恢复，设备正在重启。若 LAN 地址变化需要重新连接。"
                )
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "恢复失败", flashErrText(e))
            } finally {
                busy = false
            }
        }
    }

    fun startReset() {
        scope.launch {
            beginBusy(90_000)
            try {
                withTimeout(FLASH_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.performReset(config, sshOrNull) }
                }
                setAlert(
                    AppAlertType.Warning, "正在恢复出厂设置",
                    "配置分区已擦除，设备即将重启。"
                )
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "重置失败", flashErrText(e))
            } finally {
                busy = false
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 0.dp)
        ) {
            // 标题区
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppBackButton(onBack = onBack)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "备份与更新",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    AppIconButton(onClick = { load() }, enabled = !loading) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "刷新",
                            tint = colors.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Text(
                    "配置备份 / 出厂重置 / 恢复配置 / 固件刷写",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            SmoothOptionSwitcher(
                options = listOf("actions" to "操作", "config" to "配置"),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))

            Box(modifier = Modifier.weight(1f)) {
            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (tab == "actions") {
                        FlashCard(title = "备份", desc = "点击「生成备份」下载当前配置文件的 tar 存档。") {
                            Button(
                                onClick = {
                                    if (!ensureConnected()) return@Button
                                    backupDownloadLauncher.launch("openwrt-backup.tar.gz")
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (busy) "正在生成…" else "生成备份") }
                        }
                        if (info?.hasRootfsData == true) {
                            FlashCard(
                                title = "恢复",
                                desc = "要将固件恢复到初始状态，请单击「执行重置」（仅 squashfs 格式的固件有效）。"
                            ) {
                                Button(
                                    onClick = {
                                        if (!ensureConnected()) return@Button
                                        confirmKind = "reset"
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("执行重置") }
                            }
                        }
                        FlashCard(
                            title = "恢复配置",
                            desc = "上传备份存档以恢复配置。自定义文件（证书、脚本）会保留在系统上。"
                        ) {
                            Button(
                                onClick = {
                                    if (!ensureConnected()) return@Button
                                    restorePickLauncher.launch(arrayOf("*/*"))
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (busy) "正在上传…" else "上传备份…") }
                        }
                        FlashCard(
                            title = "更新固件",
                            desc = "在这里上传 sysupgrade 兼容固件文件以更新正在运行的固件。" +
                                if (info?.hasPlatformScript == false)
                                    "\n⚠ 未检测到平台脚本，此设备可能不支持 sysupgrade 刷写。" else ""
                        ) {
                            Button(
                                onClick = {
                                    if (!ensureConnected()) return@Button
                                    firmwarePickLauncher.launch(arrayOf("*/*"))
                                },
                                enabled = !busy && info?.hasPlatformScript != false,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (busy) "正在上传…" else "刷写固件…") }
                        }
                        if (!info?.mtdBlocks.isNullOrEmpty()) {
                            FlashCard(
                                title = "保存 mtdblock 内容",
                                desc = "下载指定 mtdblock 分区文件（仅供专业用途）。"
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(colors.surfaceVariant)
                                        .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
                                        .clickable(enabled = !busy) {
                                            val blocks = info?.mtdBlocks ?: emptyList()
                                            selectState = FlashSelectState(
                                                "选择 mtdblock",
                                                blocks.map { (dev, name) -> dev to "$dev ($name)" },
                                                mtdSelected
                                            ) { v -> mtdSelected = v }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "分区",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        mtdSelected.ifBlank { "请选择" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("›", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                                }
                                Button(
                                    onClick = {
                                        if (!ensureConnected()) return@Button
                                        scope.launch {
                                            beginBusy(FLASH_TRANSFER_TIMEOUT_MS + 30_000)
                                            try {
                                                val s = sshOrNull
                                                if (s == null) {
                                                    setAlert(AppAlertType.Error, "需要开启 SSH", "备份与更新需要 SSH 访问，请在设备编辑页开启后重试。")
                                                    return@launch
                                                }
                                                val bytes = withTimeout(FLASH_TRANSFER_TIMEOUT_MS) {
                                                    withContext(Dispatchers.IO) {
                                                        com.mmckb.openwrtstatus.data.ssh.SshFiles.download(
                                                            s, "/dev/$mtdSelected"
                                                        )
                                                    }
                                                }
                                                mtdPendingBytes = bytes
                                                mtdDownloadLauncher.launch("mtd-$mtdSelected.bin")
                                            } catch (e: Exception) {
                                                setAlert(AppAlertType.Error, "读取分区失败", flashErrText(e))
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    },
                                    enabled = !busy && mtdSelected.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (busy) "正在读取…" else "保存 mtdblock") }
                            }
                        }
                    } else {
                        FlashCard(
                            title = "配置",
                            desc = "以下为 sysupgrade 的 shell 通配模式列表，匹配的文件与目录将包含在备份中。" +
                                "/etc/config/ 下的修改文件与部分关键配置会自动保留。"
                        ) {
                            AppTextField(
                                value = confContent,
                                onValueChange = { confContent = it },
                                label = { Text("/etc/sysupgrade.conf") },
                                minLines = 8,
                                maxLines = 16,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = { saveConf() },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (busy) "正在保存…" else "保存") }
                        }
                    }
                }
                // 背景延伸到手势条区域，最后一张卡片垫在 inset 之上
                Spacer(Modifier.navigationBarsPadding().height(6.dp))
            }
            // 提示栈：钉在内容区顶部（标题正下方），不依赖坐标测量
            StackedAlertHost(
                state = alertStack,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp)
            )
            }
        }

        selectState?.let { sel ->
            AppDialog(
                title = sel.title,
                confirmLabel = "关闭",
                dismissLabel = "",
                onConfirm = { selectState = null },
                onDismiss = { selectState = null }
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    sel.options.forEach { (value, label) ->
                        val isSelected = value == sel.selected
                        Text(
                            text = if (isSelected) "● $label" else label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) colors.primary else colors.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sel.onPick(value)
                                    selectState = null
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        )
                    }
                }
            }
        }

        // 出厂重置确认
        if (confirmKind == "reset") {
            AppDialog(
                title = "执行重置",
                message = "确定要擦除全部设置吗？设备将恢复出厂配置并自动重启。",
                confirmLabel = "擦除并重启",
                confirmColor = colors.error,
                onConfirm = {
                    confirmKind = null
                    startReset()
                },
                onDismiss = { confirmKind = null }
            )
        }

        // 恢复配置确认
        if (confirmKind == "restore") {
            AppDialog(
                title = "恢复配置",
                message = "确定恢复该备份吗？恢复完成后设备将自动重启。",
                confirmLabel = "恢复并重启",
                confirmColor = colors.error,
                onConfirm = {
                    confirmKind = null
                    startRestore()
                },
                onDismiss = { confirmKind = null }
            )
        }

        // 刷写确认（校验通过后：大小/MD5/SHA256 + 保留配置/强制选项）
        if (confirmKind == "flash") {
            val check = flashCheck
            AppDialog(
                title = "刷写固件",
                confirmLabel = "继续刷写",
                confirmColor = colors.error,
                onConfirm = {
                    confirmKind = null
                    startFlash()
                },
                onDismiss = { confirmKind = null }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "固件已上传，请核对校验信息后开始刷写。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    Text(
                        "大小：${fwHumanBytes(check.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    if (check.md5.isNotBlank()) Text(
                        "MD5：${check.md5}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    if (check.sha256.isNotBlank()) Text(
                        "SHA256：${check.sha256}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "保留配置",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        AppSwitch(
                            checked = flashKeep,
                            onCheckedChange = { flashKeep = it },
                            modifier = Modifier.scale(0.75f)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "强制刷写",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        AppSwitch(
                            checked = flashForce,
                            onCheckedChange = { flashForce = it },
                            modifier = Modifier.scale(0.75f)
                        )
                    }
                    Text(
                        "⚠ 刷写过程中请勿断开电源。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error
                    )
                }
            }
        }
    }
}


/** 操作卡片：标题 + 说明 + 内容（生成备份/重置/恢复/刷写等区块）。 */
@Composable
private fun FlashCard(title: String, desc: String, content: @Composable () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface
        )
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        content()
    }
}
