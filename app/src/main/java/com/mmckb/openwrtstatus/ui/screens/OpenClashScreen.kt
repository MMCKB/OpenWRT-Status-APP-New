package com.mmckb.openwrtstatus.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.remote.ClashProxyGroup
import com.mmckb.openwrtstatus.data.remote.OpenClashClient
import com.mmckb.openwrtstatus.data.ssh.SshFiles
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.components.CardSectionTitle
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_LOG_CHARS = 30_000

/**
 * OpenClash 原生管理页（二级页，独立 Activity）：
 * 服务启停与自启、策略组节点切换（经 SSH 访问本机 Clash 控制器）、
 * 配置文件启用/上传/删除、订阅管理、常用 UCI 设置与运行日志。
 */
@Composable
fun OpenClashScreen(
    config: RouterConfig,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val client = remember { OpenClashClient() }

    val ssh = remember(config) {
        SshConfig(
            host = config.sshHost.ifBlank { config.ip },
            port = config.sshPort,
            username = config.sshUsername,
            password = config.sshPassword
        )
    }
    val sshEnabled = config.sshEnabled

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    var service by remember { mutableStateOf<com.mmckb.openwrtstatus.data.remote.OpenClashServiceState?>(null) }
    var settings by remember { mutableStateOf<com.mmckb.openwrtstatus.data.remote.OpenClashSettings?>(null) }
    var configFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var subscribes by remember { mutableStateOf<List<com.mmckb.openwrtstatus.data.remote.OpenClashSubscribe>>(emptyList()) }
    var groups by remember { mutableStateOf<List<ClashProxyGroup>>(emptyList()) }
    var groupsLoadedOnce by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }

    var groupDialog by remember { mutableStateOf<ClashProxyGroup?>(null) }
    var modeDialog by remember { mutableStateOf(false) }
    var enableConfigTarget by remember { mutableStateOf<String?>(null) }
    var deleteConfigTarget by remember { mutableStateOf<String?>(null) }
    var deleteSubscribeTarget by remember { mutableStateOf<com.mmckb.openwrtstatus.data.remote.OpenClashSubscribe?>(null) }
    var addSubscribeDialog by remember { mutableStateOf(false) }
    var subscribeName by remember { mutableStateOf("") }
    var subscribeUrl by remember { mutableStateOf("") }

    fun setMsg(text: String?, isError: Boolean) {
        message = text
        messageIsError = isError
    }

    fun refreshGroups() {
        if (!sshEnabled) return
        scope.launch {
            try {
                val s = settings
                val list = withContext(Dispatchers.IO) {
                    client.clashGroups(ssh, s?.dashboardPort ?: 9090, s?.dashboardPassword ?: "")
                }
                groups = list
                groupsLoadedOnce = true
            } catch (_: Exception) {
                groups = emptyList()
                groupsLoadedOnce = true
            }
        }
    }

    fun refreshAll(includeGroups: Boolean) {
        scope.launch {
            busy = true
            try {
                service = withContext(Dispatchers.IO) { client.serviceState(config) }
                settings = withContext(Dispatchers.IO) { client.readSettings(config) }
                configFiles = withContext(Dispatchers.IO) { client.listConfigFiles(config) }
                subscribes = withContext(Dispatchers.IO) { client.listSubscribes(config) }
                if (includeGroups && service?.running == true) refreshGroups()
            } catch (e: Exception) {
                setMsg(e.message ?: "读取 OpenClash 状态失败。", true)
            } finally {
                busy = false
            }
        }
    }

    // 状态每 5 秒静默轮询；错误不打扰。
    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                service = withContext(Dispatchers.IO) { client.serviceState(config) }
            } catch (_: Exception) {
            }
            delay(5000)
        }
    }
    LaunchedEffect(Unit) { refreshAll(includeGroups = true) }

    fun runJob(info: String, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = null
            try {
                block()
                message = info
                messageIsError = false
            } catch (e: com.mmckb.openwrtstatus.data.remote.RouterException) {
                message = listOfNotNull(e.message, e.hint).joinToString("\n")
                messageIsError = true
            } catch (e: Exception) {
                message = e.message ?: "操作失败。"
                messageIsError = true
            } finally {
                busy = false
            }
        }
    }

    fun serviceAction(action: String, label: String) {
        runJob("已$label，状态稍后自动刷新。") {
            withContext(Dispatchers.IO) { client.serviceAction(config, action) }
            delay(2500)
            service = withContext(Dispatchers.IO) { client.serviceState(config) }
            settings = withContext(Dispatchers.IO) { client.readSettings(config) }
            if (service?.running == true) refreshGroups()
        }
    }

    fun setOption(values: Map<String, String>, info: String) {
        val s = settings ?: return
        runJob(info) {
            withContext(Dispatchers.IO) { client.setMainOptions(config, s.section, values) }
            settings = withContext(Dispatchers.IO) { client.readSettings(config) }
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runJob("配置已上传。") {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("无法读取所选文件。")
                }
                val name = withContext(Dispatchers.IO) { queryDisplayName(context, uri) } ?: "config.yaml"
                val target = "/etc/openclash/config/${name.substringAfterLast('/')}"
                withContext(Dispatchers.IO) { SshFiles.upload(ssh, target, bytes) }
                configFiles = withContext(Dispatchers.IO) { client.listConfigFiles(config) }
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
                IconButton(onClick = { refreshAll(includeGroups = true) }, enabled = !busy) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = colors.onSurfaceVariant)
                }
            }
            Text(
                "OpenClash",
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

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
            ) {
                // --- 运行状态 ---
                item {
                    AppCard {
                        CardSectionTitle("运行状态")
                        Spacer(Modifier.height(10.dp))
                        val s = service
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            s == null -> colors.onSurfaceVariant
                                            s.running -> colors.success
                                            else -> colors.error
                                        }
                                    )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    s == null -> "读取中…"
                                    !s.present -> "未安装 OpenClash"
                                    s.running -> "运行中"
                                    else -> "已停止"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Spacer(Modifier.weight(1f))
                            if (s != null && s.present) {
                                Text(
                                    if (s.enabled) "开机自启" else "不自启",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                        s?.takeIf { it.present }?.let { st ->
                            Spacer(Modifier.height(8.dp))
                            DetailRowLite("运行模式", settings?.enMode)
                            DetailRowLite("代理模式", settings?.proxyMode)
                            DetailRowLite("内核架构", settings?.coreVersion)
                            DetailRowLite(
                                "当前配置",
                                settings?.configPath?.substringAfterLast('/')?.ifBlank { null } ?: "未选择"
                            )
                            DetailRowLite("控制面板端口", settings?.dashboardPort?.toString())
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val running = s?.running == true
                            Button(
                                onClick = { serviceAction("start", "启动") },
                                enabled = !busy && !running
                            ) { Text("启动") }
                            Button(
                                onClick = { serviceAction("stop", "停止") },
                                enabled = !busy && running
                            ) { Text("停止") }
                            Button(
                                onClick = { serviceAction("restart", "重启") },
                                enabled = !busy
                            ) { Text("重启") }
                            TextButton(
                                onClick = {
                                    serviceAction(if (s?.enabled == true) "disable" else "enable", if (s?.enabled == true) "关闭自启" else "开启自启")
                                },
                                enabled = !busy && s?.present == true
                            ) { Text(if (s?.enabled == true) "关闭自启" else "开启自启") }
                        }
                    }
                }

                // --- 策略组 ---
                item {
                    AppCard {
                        CardSectionTitle("策略组与节点", trailing = {
                            TextButton(onClick = { refreshGroups() }, enabled = !busy && sshEnabled) {
                                Text("刷新")
                            }
                        })
                        Spacer(Modifier.height(6.dp))
                        when {
                            !sshEnabled -> Text(
                                "节点切换需要通过路由器本机的 Clash 控制器，请先在设备设置中开启 SSH。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                            groups.isEmpty() -> Text(
                                if (groupsLoadedOnce) "无法读取策略组：请确认 OpenClash 正在运行，且设备已开启 SSH。"
                                else "读取中…",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                            else -> {
                                groups.forEachIndexed { index, group ->
                                    if (index > 0) {
                                        HorizontalDivider(color = colors.outline, modifier = Modifier.padding(vertical = 6.dp))
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !busy) { groupDialog = group }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                group.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = colors.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "当前：${group.now}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            "${group.options.size} 节点",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // --- 配置文件 ---
                item {
                    AppCard {
                        CardSectionTitle("配置文件", trailing = {
                            TextButton(
                                onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                                enabled = !busy && sshEnabled
                            ) { Text("上传") }
                        })
                        Spacer(Modifier.height(6.dp))
                        val currentFile = settings?.configPath?.substringAfterLast('/').orEmpty()
                        if (configFiles.isEmpty()) {
                            Text(
                                "暂无配置文件。可上传 yaml 配置，或先在下方添加订阅。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        } else {
                            configFiles.forEach { file ->
                                val isCurrent = file == currentFile
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !busy && !isCurrent) {
                                            enableConfigTarget = file
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            file,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isCurrent) colors.primary else colors.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isCurrent) {
                                            Text(
                                                "使用中，点击其他配置可切换",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { deleteConfigTarget = file },
                                        enabled = !busy
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "删除",
                                            tint = colors.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            if (sshEnabled && configFiles.size > 1) {
                                Text(
                                    "点击配置即可切换并自动重启服务；右侧 × 删除文件。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // --- 订阅管理 ---
                item {
                    AppCard {
                        CardSectionTitle("订阅", trailing = {
                            TextButton(onClick = {
                                subscribeName = ""
                                subscribeUrl = ""
                                addSubscribeDialog = true
                            }, enabled = !busy) { Text("添加") }
                        })
                        Spacer(Modifier.height(6.dp))
                        if (subscribes.isEmpty()) {
                            Text(
                                "暂无订阅。添加订阅地址后，重启服务即可自动下载配置。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        } else {
                            subscribes.forEach { sub ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            sub.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            sub.address,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { deleteSubscribeTarget = sub },
                                        enabled = !busy
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "删除订阅",
                                            tint = colors.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                "OpenClash 在服务启动时自动拉取订阅，添加或更新后请重启服务。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }

                // --- 常用设置 ---
                item {
                    val s = settings
                    AppCard {
                        CardSectionTitle("常用设置")
                        Spacer(Modifier.height(8.dp))
                        if (s == null) {
                            Text(
                                "未读取到 OpenClash 配置。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        } else {
                            SettingSwitchRow("DNS 劫持", s.enableRedirectDns) { v ->
                                setOption(mapOf("enable_redirect_dns" to if (v) "1" else "0"), "已保存。")
                            }
                            SettingSwitchRow("允许局域网连接", s.intranetAllowed) { v ->
                                setOption(mapOf("intranet_allowed" to if (v) "1" else "0"), "已保存。")
                            }
                            SettingSwitchRow("UDP 代理", s.enableUdpProxy) { v ->
                                setOption(mapOf("enable_udp_proxy" to if (v) "1" else "0"), "已保存。")
                            }
                            SettingSwitchRow("禁用 QUIC", s.disableUdpQuic) { v ->
                                setOption(mapOf("disable_udp_quic" to if (v) "1" else "0"), "已保存。")
                            }
                            SettingSwitchRow("订阅自动更新", s.autoUpdate) { v ->
                                setOption(mapOf("auto_update" to if (v) "1" else "0"), "已保存。")
                            }
                            HorizontalDivider(color = colors.outline, modifier = Modifier.padding(vertical = 8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "运行模式",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        s.enMode,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { modeDialog = true }, enabled = !busy) { Text("更改") }
                            }
                            Text(
                                "设置保存到路由器后需重启服务生效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }

                // --- 日志 ---
                item {
                    AppCard {
                        CardSectionTitle("运行日志", trailing = {
                            TextButton(
                                onClick = {
                                    runJob("") {
                                        val text = withContext(Dispatchers.IO) { client.tailLog(ssh) }
                                        logText = text.takeLast(MAX_LOG_CHARS)
                                    }
                                },
                                enabled = !busy && sshEnabled
                            ) { Text("刷新") }
                        })
                        Spacer(Modifier.height(6.dp))
                        if (!sshEnabled) {
                            Text(
                                "查看日志需要开启 SSH。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        } else if (logText.isEmpty()) {
                            Text(
                                "暂无日志，点击「刷新」读取最近 200 行。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        } else {
                            Text(
                                logText,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = colors.onSurface,
                                modifier = Modifier
                                    .height(280.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- 对话框 ----

    groupDialog?.let { group ->
        AppDialog(
            title = group.name,
            confirmLabel = "关闭",
            dismissLabel = "",
            onConfirm = { groupDialog = null },
            onDismiss = { groupDialog = null }
        ) {
            Column(
                modifier = Modifier
                    .height(380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                group.options.forEach { option ->
                    val isNow = option == group.now
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isNow) {
                                    groupDialog = null
                                    return@clickable
                                }
                                val g = group
                                groupDialog = null
                                runJob("已切换「${g.name}」到 $option。") {
                                    val ok = withContext(Dispatchers.IO) {
                                        client.clashSelectProxy(
                                            ssh,
                                            settings?.dashboardPort ?: 9090,
                                            settings?.dashboardPassword ?: "",
                                            g.name,
                                            option
                                        )
                                    }
                                    if (!ok) throw IllegalStateException("切换被 Clash 拒绝。")
                                    delay(400)
                                    refreshGroups()
                                }
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            option,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isNow) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isNow) colors.primary else colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (isNow) {
                            Text(
                                "当前",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary
                            )
                        }
                    }
                }
            }
        }
    }

    if (modeDialog) {
        val choices = listOf("fake-ip", "redir-host", "fake-ip-tun", "redir-host-tun")
        AppDialog(
            title = "运行模式",
            confirmLabel = "取消",
            dismissLabel = "",
            onConfirm = { modeDialog = false },
            onDismiss = { modeDialog = false }
        ) {
            choices.forEach { choice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            modeDialog = false
                            setOption(
                                mapOf(
                                    "en_mode" to choice,
                                    "operation_mode" to choice.removeSuffix("-tun")
                                ),
                                "已切换运行模式，重启服务后生效。"
                            )
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings?.enMode == choice,
                        onClick = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(choice, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                }
            }
        }
    }

    enableConfigTarget?.let { file ->
        AppDialog(
            title = "切换配置",
            message = "启用「$file」并重启 OpenClash 服务？",
            confirmLabel = "启用",
            onConfirm = {
                val s = settings
                enableConfigTarget = null
                runJob("已启用 $file。") {
                    if (s != null) {
                        withContext(Dispatchers.IO) {
                            client.setMainOptions(config, s.section, mapOf("config_path" to "/etc/openclash/config/$file"))
                        }
                    }
                    withContext(Dispatchers.IO) { client.serviceAction(config, "restart") }
                    delay(2500)
                    service = withContext(Dispatchers.IO) { client.serviceState(config) }
                    settings = withContext(Dispatchers.IO) { client.readSettings(config) }
                    if (service?.running == true) refreshGroups()
                }
            },
            onDismiss = { enableConfigTarget = null }
        )
    }

    deleteConfigTarget?.let { file ->
        AppDialog(
            title = "删除配置文件",
            message = "确定删除「$file」吗？此操作不可恢复。",
            confirmLabel = "删除",
            confirmColor = colors.error,
            onConfirm = {
                deleteConfigTarget = null
                runJob("已删除。") {
                    withContext(Dispatchers.IO) {
                        SshFiles.delete(ssh, "/etc/openclash/config/$file", isDir = false)
                    }
                    configFiles = withContext(Dispatchers.IO) { client.listConfigFiles(config) }
                }
            },
            onDismiss = { deleteConfigTarget = null }
        )
    }

    deleteSubscribeTarget?.let { sub ->
        AppDialog(
            title = "删除订阅",
            message = "确定删除订阅「${sub.name}」吗？已下载的配置文件不会被删除。",
            confirmLabel = "删除",
            confirmColor = colors.error,
            onConfirm = {
                deleteSubscribeTarget = null
                runJob("已删除订阅。") {
                    withContext(Dispatchers.IO) { client.deleteSubscribe(config, sub.section) }
                    subscribes = withContext(Dispatchers.IO) { client.listSubscribes(config) }
                }
            },
            onDismiss = { deleteSubscribeTarget = null }
        )
    }

    if (addSubscribeDialog) {
        AppDialog(
            title = "添加订阅",
            confirmLabel = "添加",
            confirmEnabled = subscribeName.isNotBlank() && subscribeUrl.isNotBlank(),
            onConfirm = {
                val name = subscribeName.trim()
                val url = subscribeUrl.trim()
                addSubscribeDialog = false
                runJob("订阅已添加，重启服务后自动下载。") {
                    withContext(Dispatchers.IO) { client.addSubscribe(config, name, url) }
                    subscribes = withContext(Dispatchers.IO) { client.listSubscribes(config) }
                }
            },
            onDismiss = { addSubscribeDialog = false }
        ) {
            OutlinedTextField(
                value = subscribeName,
                onValueChange = { subscribeName = it },
                singleLine = true,
                label = { Text("订阅名称") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = subscribeUrl,
                onValueChange = { subscribeUrl = it },
                singleLine = true,
                label = { Text("订阅地址（URL）") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DetailRowLite(label: String, value: String?) {
    val colors = LocalAppColors.current
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
