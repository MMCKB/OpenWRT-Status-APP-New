package com.mmckb.openwrtstatus.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.remote.SystemClient
import com.mmckb.openwrtstatus.data.remote.SystemData
import com.mmckb.openwrtstatus.data.remote.ZoneEntry
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.components.SmoothOptionSwitcher
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class SysSelectState(
    val title: String,
    val options: List<Pair<String, String>>,
    val selected: String?,
    val onPick: (String) -> Unit
)

/**
 * 系统管理页（工具页入口，LuCI admin/system/system 的完整复刻）：
 * 常规设置（本地时间/主机名/描述/备注/时区/时间格式）、日志、时间同步（NTP）、
 * ZRam、外观（LuCI 语言/主题）。页签为方案选择器（支持长按拖动 1:1 跟手）。
 */
@Composable
fun SystemScreen(
    config: RouterConfig,
    sshEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val client = remember { SystemClient() }
    val ssh = remember(config) {
        SshConfig(
            host = config.sshHost.ifBlank { config.ip },
            port = config.sshPort,
            username = config.sshUsername,
            password = config.sshPassword
        )
    }

    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("general") }

    var data by remember { mutableStateOf<SystemData?>(null) }
    var zones by remember { mutableStateOf<List<ZoneEntry>>(emptyList()) }
    var routerTime by remember { mutableStateOf<Long?>(null) }

    var hostname by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var zonename by remember { mutableStateOf("") }
    var tzstring by remember { mutableStateOf("") }
    var timestyle by remember { mutableStateOf(false) }
    var hourcycle by remember { mutableStateOf("") }
    var logSize by remember { mutableStateOf("") }
    var logIp by remember { mutableStateOf("") }
    var logPort by remember { mutableStateOf("") }
    var logProto by remember { mutableStateOf("udp") }
    var logFile by remember { mutableStateOf("") }
    var conloglevel by remember { mutableStateOf("") }
    var cronloglevel by remember { mutableStateOf("7") }
    var zramSize by remember { mutableStateOf("") }
    var zramAlgo by remember { mutableStateOf("lzo") }
    var ntpEnabled by remember { mutableStateOf(false) }
    var ntpProvide by remember { mutableStateOf(false) }
    var ntpUseDhcp by remember { mutableStateOf(true) }
    var ntpServers by remember { mutableStateOf("") }
    var ntpInterface by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf("auto") }
    var theme by remember { mutableStateOf("") }
    var tablefilters by remember { mutableStateOf(false) }
    var selectState by remember { mutableStateOf<SysSelectState?>(null) }

    fun setMsg(text: String?, isError: Boolean) {
        message = text
        messageIsError = isError
    }

    fun fill(d: SystemData) {
        hostname = d.hostname
        description = d.description ?: ""
        notes = d.notes ?: ""
        zonename = d.zonename ?: ""
        tzstring = d.timezone ?: ""
        timestyle = d.clockTimestyle
        hourcycle = d.clockHourcycle ?: ""
        logSize = d.logSize ?: ""
        logIp = d.logIp ?: ""
        logPort = d.logPort ?: ""
        logProto = d.logProto ?: "udp"
        logFile = d.logFile ?: ""
        conloglevel = d.conloglevel ?: ""
        cronloglevel = d.cronloglevel ?: "7"
        zramSize = d.zramSizeMb ?: ""
        zramAlgo = d.zramCompAlgo ?: "lzo"
        ntpEnabled = d.ntpEnabled
        ntpProvide = d.ntpProvideServer
        ntpUseDhcp = d.ntpUseDhcp
        ntpServers = d.ntpServers.joinToString("\n")
        ntpInterface = d.ntpInterface ?: ""
        lang = d.luciLang ?: "auto"
        theme = d.luciTheme ?: ""
        tablefilters = d.luciTablefilters
    }

    fun load() {
        scope.launch {
            loading = true
            try {
                val d = withContext(Dispatchers.IO) { client.load(config) }
                if (d == null) {
                    setMsg("读取系统配置失败。", true)
                } else {
                    data = d
                    fill(d)
                    zones = withContext(Dispatchers.IO) { client.timezones(config) }
                    routerTime = withContext(Dispatchers.IO) { client.unixtime(config) }
                }
            } catch (e: Exception) {
                setMsg(e.message ?: "读取失败。", true)
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

    val timeText = routerTime?.let {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it * 1000))
    } ?: "—"

    fun buildChanges(): Map<String, Map<String, Map<String, Any>>> {
        val d = data ?: return emptyMap()
        val system = mutableMapOf<String, Any>()
        if (hostname.trim() != d.hostname) system["hostname"] = hostname.trim()
        if (description.trim() != (d.description ?: "")) system["description"] = description.trim()
        if (notes.trim() != (d.notes ?: "")) system["notes"] = notes.trim()
        if (zonename != (d.zonename ?: "")) {
            system["zonename"] = zonename
            system["timezone"] = tzstring
        }
        if (timestyle != d.clockTimestyle) system["clock_timestyle"] = if (timestyle) "1" else "0"
        if (hourcycle != (d.clockHourcycle ?: "")) system["clock_hourcycle"] = hourcycle
        if (logSize.trim() != (d.logSize ?: "")) system["log_size"] = logSize.trim()
        if (logIp.trim() != (d.logIp ?: "")) system["log_ip"] = logIp.trim()
        if (logPort.trim() != (d.logPort ?: "")) system["log_port"] = logPort.trim()
        if (logProto != (d.logProto ?: "udp")) system["log_proto"] = logProto
        if (logFile.trim() != (d.logFile ?: "")) system["log_file"] = logFile.trim()
        if (conloglevel != (d.conloglevel ?: "")) system["conloglevel"] = conloglevel
        if (cronloglevel != (d.cronloglevel ?: "")) system["cronloglevel"] = cronloglevel
        if (zramSize.trim() != (d.zramSizeMb ?: "")) system["zram_size_mb"] = zramSize.trim()
        if (zramAlgo != (d.zramCompAlgo ?: "lzo")) system["zram_comp_algo"] = zramAlgo

        val sections = mutableMapOf<String, Map<String, Any>>()
        if (system.isNotEmpty()) sections[d.systemSection] = system

        val ntpChanged = ntpEnabled != d.ntpEnabled || ntpProvide != d.ntpProvideServer ||
            ntpUseDhcp != d.ntpUseDhcp ||
            ntpServers.lines().map { it.trim() }.filter { it.isNotEmpty() } != d.ntpServers ||
            ntpInterface.trim() != (d.ntpInterface ?: "")
        if (ntpChanged) {
            val ntp = mutableMapOf<String, Any>(
                "enabled" to if (ntpEnabled) "1" else "0"
            )
            if (ntpEnabled) {
                ntp["enable_server"] = if (ntpProvide) "1" else "0"
                ntp["use_dhcp"] = if (ntpUseDhcp) "1" else "0"
                val servers = ntpServers.lines().map { it.trim() }.filter { it.isNotEmpty() }
                ntp["server"] = servers
                if (ntpInterface.isNotBlank()) ntp["interface"] = ntpInterface.trim()
            }
            // 段不存在时以 "ntp" 命名创建；存在时直接写 ntp 段
            val spec = if (d.ntpSectionExists) "ntp" else "ntp!ntp"
            sections[spec] = ntp
        }

        val luciMain = mutableMapOf<String, Any>()
        if (lang != (d.luciLang ?: "auto")) luciMain["lang"] = lang
        if (theme != (d.luciTheme ?: "")) luciMain["mediaurlbase"] = theme
        if (tablefilters != d.luciTablefilters) luciMain["tablefilters"] = if (tablefilters) "1" else "0"
        val luci = if (luciMain.isNotEmpty()) mapOf("main" to luciMain) else emptyMap()

        val out = mutableMapOf<String, Map<String, Map<String, Any>>>()
        if (sections.isNotEmpty()) out["system"] = sections
        if (luci.isNotEmpty()) out["luci"] = luci
        return out
    }

    fun save() {
        val changes = buildChanges()
        if (changes.isEmpty()) {
            setMsg("没有需要保存的更改。", false)
            return
        }
        scope.launch {
            busy = true
            message = null
            try {
                withContext(Dispatchers.IO) {
                    client.apply(config, changes, if (sshEnabled) ssh else null) { phase ->
                        setMsg(phase, false)
                    }
                }
                setMsg("已保存并应用。", false)
                load()
            } catch (e: Exception) {
                setMsg(e.message ?: "保存失败。", true)
            } finally {
                busy = false
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBackButton(onBack = onBack)
                Spacer(Modifier.weight(1f))
            }
            Text(
                "系统管理",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Spacer(Modifier.height(10.dp))
            SmoothOptionSwitcher(
                options = listOf(
                    "general" to "常规",
                    "logging" to "日志",
                    "timesync" to "时间",
                    "zram" to "ZRam",
                    "style" to "外观"
                ),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
            } else if (data == null) {
                Text(
                    message ?: "读取系统配置失败。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (tab) {
                        "general" -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "本地时间",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    timeText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    client.setLocaltime(config, System.currentTimeMillis() / 1000)
                                                }
                                                routerTime = withContext(Dispatchers.IO) { client.unixtime(config) }
                                                setMsg("已将路由器时钟同步为手机时间。", false)
                                            } catch (e: Exception) {
                                                setMsg(e.message ?: "同步失败。", true)
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f)
                                ) { Text("与手机同步") }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    com.mmckb.openwrtstatus.data.ssh.SshExec.run(
                                                        ssh, "/etc/init.d/sysntpd restart >/dev/null 2>&1; echo __OK__", 20_000
                                                    )
                                                }
                                                setMsg("已通过 NTP 重新校时。", false)
                                            } catch (e: Exception) {
                                                setMsg(e.message ?: "NTP 校时失败（需要 SSH）。", true)
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f)
                                ) { Text("与 NTP 同步") }
                            }
                            OutlinedTextField(
                                value = hostname,
                                onValueChange = { hostname = it },
                                singleLine = true,
                                label = { Text("主机名") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = description,
                                onValueChange = { description = it },
                                singleLine = true,
                                label = { Text("描述（可选）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = notes,
                                onValueChange = { notes = it },
                                label = { Text("备注（可选）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            SysSelectRow(
                                "时区",
                                zonename.ifBlank { "UTC" }
                            ) {
                                selectState = SysSelectState(
                                    "选择时区",
                                    zones.map { it.zone to it.zone }.ifEmpty {
                                        listOf("UTC" to "UTC", "Asia/Shanghai" to "Asia/Shanghai")
                                    },
                                    zonename
                                ) { v ->
                                    zonename = v
                                    tzstring = zones.firstOrNull { it.zone == v }?.tzstring ?: "UTC"
                                }
                            }
                            SysSettingRow("显示完整时区名", timestyle) { timestyle = it }
                            SysSelectRow(
                                "时间格式",
                                when (hourcycle) {
                                    "h12" -> "12 小时制"
                                    "h23" -> "24 小时制"
                                    else -> "默认"
                                }
                            ) {
                                selectState = SysSelectState(
                                    "选择时间格式",
                                    listOf("" to "默认", "h12" to "12 小时制", "h23" to "24 小时制"),
                                    hourcycle
                                ) { v -> hourcycle = v }
                            }
                        }
                        "logging" -> {
                            OutlinedTextField(
                                value = logSize,
                                onValueChange = { logSize = it },
                                singleLine = true,
                                label = { Text("系统日志缓冲区大小（kiB，默认 128）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = logIp,
                                onValueChange = { logIp = it },
                                singleLine = true,
                                label = { Text("外部系统日志服务器") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = logPort,
                                onValueChange = { logPort = it },
                                singleLine = true,
                                label = { Text("外部系统日志服务器端口（默认 514）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            SysSelectRow(
                                "外部系统日志服务器协议",
                                if (logProto == "tcp") "TCP" else "UDP"
                            ) {
                                selectState = SysSelectState(
                                    "选择协议",
                                    listOf("udp" to "UDP", "tcp" to "TCP"),
                                    logProto
                                ) { v -> logProto = v }
                            }
                            OutlinedTextField(
                                value = logFile,
                                onValueChange = { logFile = it },
                                singleLine = true,
                                label = { Text("写系统日志到文件（默认 /tmp/system.log）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            SysSelectRow(
                                "日志输出级别（内核日志）",
                                conloglevelLabel(conloglevel)
                            ) {
                                selectState = SysSelectState(
                                    "选择日志输出级别",
                                    listOf(
                                        "8" to "Debug (8)", "7" to "Info (7)", "6" to "Notice (6)",
                                        "5" to "Warning (5)", "4" to "Error (4)", "3" to "Critical (3)",
                                        "2" to "Alert (2)", "1" to "Emergency (1)"
                                    ),
                                    conloglevel
                                ) { v -> conloglevel = v }
                            }
                            SysSelectRow(
                                "Cron 日志级别",
                                when (cronloglevel) {
                                    "9" -> "禁用"
                                    "5" -> "Debug"
                                    else -> "正常"
                                }
                            ) {
                                selectState = SysSelectState(
                                    "选择 Cron 日志级别",
                                    listOf("7" to "正常", "9" to "禁用", "5" to "Debug"),
                                    cronloglevel
                                ) { v -> cronloglevel = v }
                            }
                        }
                        "timesync" -> {
                            SysSettingRow("启用 NTP 客户端", ntpEnabled) {
                                ntpEnabled = it
                                if (!it) ntpProvide = false
                            }
                            if (ntpEnabled) {
                                SysSettingRow("提供 NTP 服务器", ntpProvide) { ntpProvide = it }
                                SysSettingRow("使用 DHCP 通告的服务器", ntpUseDhcp) { ntpUseDhcp = it }
                                OutlinedTextField(
                                    value = ntpInterface,
                                    onValueChange = { ntpInterface = it },
                                    singleLine = true,
                                    label = { Text("绑定 NTP 服务器到接口（可选）") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = ntpServers,
                                    onValueChange = { ntpServers = it },
                                    label = { Text("NTP 服务器候选（每行一个）") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        "zram" -> {
                            OutlinedTextField(
                                value = zramSize,
                                onValueChange = { zramSize = it },
                                singleLine = true,
                                label = { Text("ZRam 大小（MB，默认 16）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            SysSelectRow(
                                "ZRam 压缩算法",
                                zramAlgo.ifBlank { "lzo" }
                            ) {
                                selectState = SysSelectState(
                                    "选择压缩算法",
                                    listOf("lzo" to "lzo", "lz4" to "lz4", "zstd" to "zstd"),
                                    zramAlgo
                                ) { v -> zramAlgo = v }
                            }
                        }
                        else -> {
                            SysSelectRow(
                                "语言",
                                if (lang == "auto" || lang.isBlank()) "自动" else lang
                            ) {
                                selectState = SysSelectState(
                                    "选择 LuCI 语言",
                                    listOf("auto" to "auto", "en" to "English"),
                                    lang
                                ) { v -> lang = v }
                            }
                            SysSelectRow(
                                "设计（主题）",
                                theme.ifBlank { "默认" }
                            ) {
                                val opts = (data?.luciThemes ?: emptyList())
                                    .ifEmpty { listOf(theme to theme) }
                                selectState = SysSelectState("选择设计", opts, theme) { v -> theme = v }
                            }
                            SysSettingRow("表格过滤器", tablefilters) { tablefilters = it }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                Button(
                    onClick = { save() },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Text(if (busy) "正在应用…" else "保存并应用")
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (messageIsError) colors.error else colors.success,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // 通用选择对话框（时区/协议/级别/算法/语言/主题等）
            selectState?.let { sel ->
                AppDialog(
                    title = sel.title,
                    confirmLabel = "关闭",
                    dismissLabel = "",
                    onConfirm = { selectState = null },
                    onDismiss = { selectState = null }
                ) {
                    Column(modifier = Modifier.heightIn(max = 380.dp)) {
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
        }
    }
}

private fun conloglevelLabel(value: String?): String = when (value) {
    "8" -> "Debug (8)"
    "7" -> "Info (7)"
    "6" -> "Notice (6)"
    "5" -> "Warning (5)"
    "4" -> "Error (4)"
    "3" -> "Critical (3)"
    "2" -> "Alert (2)"
    "1" -> "Emergency (1)"
    else -> "默认"
}

@Composable
private fun SysSelectRow(label: String, value: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .background(colors.surface, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .border(1.dp, colors.outline, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text("›", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun SysSettingRow(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .background(colors.surface, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .border(1.dp, colors.outline, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChanged,
            modifier = Modifier.scale(0.75f)
        )
    }
}
