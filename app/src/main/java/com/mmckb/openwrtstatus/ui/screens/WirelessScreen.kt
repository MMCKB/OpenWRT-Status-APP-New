package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.remote.ScanNet
import com.mmckb.openwrtstatus.data.remote.WirelessClient
import com.mmckb.openwrtstatus.data.remote.WirelessIface
import com.mmckb.openwrtstatus.data.remote.WirelessRadio
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.components.SmoothOptionSwitcher
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val UPLOAD_TMP_PATH = "/tmp/upload.apk"

/** 加密方式（value to LuCI 中文标签）。 */
private val SECURITY_OPTIONS = listOf(
    "psk2" to "WPA2-PSK (强安全性)",
    "sae" to "WPA3-SAE (强安全性)",
    "sae-mixed" to "WPA2-PSK/WPA3-SAE 混合 (强安全性)",
    "psk-mixed" to "WPA-PSK/WPA2-PSK 混合 (中等安全性)",
    "psk" to "WPA-PSK (弱安全性)",
    "none" to "无加密 (开放网络)"
)

private val MACFILTER_OPTIONS = listOf(
    "disable" to "已禁用",
    "allow" to "仅允许列表内",
    "deny" to "仅允许列表外"
)

private fun bandLabel(band: String?): String = when (band) {
    "5g" -> "5 GHz"
    "2g" -> "2.4 GHz"
    "6g" -> "6 GHz"
    else -> band ?: ""
}

private fun encryptionLabel(value: String?, live: String? = null): String =
    live ?: (SECURITY_OPTIONS.firstOrNull { it.first == value }?.second ?: (value ?: "未设置"))

private fun channelOptions(band: String?): List<Pair<String, String>> {
    val list = mutableListOf("auto" to "auto")
    when (band) {
        "5g" -> listOf(
            36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120,
            124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165
        ).forEach { ch ->
            list.add("$ch" to "$ch (${5000 + ch * 5} MHz)")
        }
        else -> (1..13).forEach { ch ->
            list.add("$ch" to "$ch (${2407 + ch * 5} MHz)")
        }
    }
    return list
}

private fun htmodeOptions(band: String?, hwmode: String?): List<Pair<String, String>> {
    val mode = hwmode ?: when (band) {
        "5g" -> "11ac"
        else -> "11n"
    }
    val widths = when (mode) {
        "11n" -> listOf(20, 40)
        "11ac" -> listOf(20, 40, 80, 160)
        "11ax" -> listOf(20, 40, 80, 160)
        else -> listOf(20, 40)
    }
    val prefix = when (mode) {
        "11n" -> "HT"
        "11ac" -> "VHT"
        "11ax" -> "HE"
        else -> "HT"
    }
    return widths.map { w -> "$prefix$w" to "$w MHz" }
}

private fun htmodeLabel(htmode: String?): String = when {
    htmode == null -> "-"
    htmode.startsWith("HE") -> "${htmode.substring(2)} MHz (Wi-Fi 6)"
    htmode.startsWith("VHT") -> "${htmode.substring(3)} MHz (Wi-Fi 5)"
    htmode.startsWith("HT") -> "${htmode.substring(2)} MHz (Wi-Fi 4)"
    else -> htmode
}

private val COUNTRY_OPTIONS = listOf(
    "AU", "CA", "CN", "DE", "FR", "GB", "HK", "JP", "KR", "MO", "NZ",
    "SG", "TW", "US"
).map { code -> code to code }

private fun txpowerOptions(): List<Pair<String, String>> {
    val list = mutableListOf("" to "驱动默认")
    (33 downTo 0).forEach { list.add("$it" to "$it dBm") }
    return list
}

private data class SelectState(
    val title: String,
    val options: List<Pair<String, String>>,
    val selected: String?,
    val onPick: (String) -> Unit
)

/**
 * 无线设置页（二级页，独立 Activity）：功能与 LuCI 无线页对齐——
 * 网卡：实时信道/功率/噪声、重启、扫描、添加 WiFi、编辑设备配置（工作频率/频宽/信道/功率/国家）；
 * WiFi 接口：LuCI 同款信息（模式/加密/BSSID/信号/客户端）、编辑（常规/安全/MAC 过滤/高级）、删除。
 */
@Composable
fun WirelessScreen(
    config: RouterConfig,
    sshEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val client = remember { WirelessClient() }
    val ssh = remember(config) {
        SshConfig(
            host = config.sshHost.ifBlank { config.ip },
            port = config.sshPort,
            username = config.sshUsername,
            password = config.sshPassword
        )
    }

    var radios by remember { mutableStateOf<List<WirelessRadio>?>(null) }
    var original by remember { mutableStateOf<List<WirelessRadio>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var showApplyConfirm by remember { mutableStateOf(false) }
    var pendingApply by remember { mutableStateOf<(() -> Unit)?>(null) }

    var editRadioFor by remember { mutableStateOf<String?>(null) }
    var radioChannel by remember { mutableStateOf("auto") }
    var radioHtmode by remember { mutableStateOf("") }
    var radioTxpower by remember { mutableStateOf("") }
    var radioCountry by remember { mutableStateOf("") }
    var radioHwmode by remember { mutableStateOf("11ax") }

    var editIfaceFor by remember { mutableStateOf<String?>(null) }
    var ifaceMode by remember { mutableStateOf("ap") }
    var ifaceSsid by remember { mutableStateOf("") }
    var ifaceNetwork by remember { mutableStateOf("lan") }
    var ifaceEncryption by remember { mutableStateOf("psk2") }
    var ifaceCipher by remember { mutableStateOf("auto") }
    var ifaceKey by remember { mutableStateOf("") }
    var ifaceHidden by remember { mutableStateOf(false) }
    var ifaceWmm by remember { mutableStateOf(true) }
    var ifaceIsolate by remember { mutableStateOf(false) }
    var ifaceMacfilter by remember { mutableStateOf("disable") }
    var ifaceMaclist by remember { mutableStateOf("") }
    var ifaceBssid by remember { mutableStateOf("") }
    var ifaceDtim by remember { mutableStateOf("") }
    var ifaceBeaconInt by remember { mutableStateOf("") }
    var ifaceFrag by remember { mutableStateOf("") }
    var ifaceRts by remember { mutableStateOf("") }
    var ifaceShortPreamble by remember { mutableStateOf(true) }
    var ifaceSectionTab by remember { mutableStateOf("general") }

    var addWifiFor by remember { mutableStateOf<String?>(null) }
    var addSsid by remember { mutableStateOf("") }
    var addKey by remember { mutableStateOf("") }
    var addEncryption by remember { mutableStateOf("psk2") }

    var deleteIfaceFor by remember { mutableStateOf<String?>(null) }

    var scanFor by remember { mutableStateOf<String?>(null) }
    var scanResults by remember { mutableStateOf<List<ScanNet>?>(null) }
    var scanBusy by remember { mutableStateOf(false) }

    var selectState by remember { mutableStateOf<SelectState?>(null) }

    fun setMsg(text: String?, isError: Boolean) {
        message = text
        messageIsError = isError
    }

    fun load() {
        scope.launch {
            loading = true
            loadError = null
            try {
                val loaded = withContext(Dispatchers.IO) { client.load(config) }
                if (loaded.isEmpty()) {
                    loadError = "未读取到无线配置（路由器可能没有无线模块）。"
                }
                radios = loaded
                original = loaded
            } catch (e: Exception) {
                loadError = e.message ?: "读取无线配置失败。"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { if (sshEnabled) load() else loading = false }

    fun updateRadio(section: String, transform: (WirelessRadio) -> WirelessRadio) {
        radios = radios.orEmpty().map { if (it.section == section) transform(it) else it }
    }

    fun updateIface(section: String, transform: (WirelessIface) -> WirelessIface) {
        radios = radios.orEmpty().map { radio ->
            radio.copy(ifaces = radio.ifaces.map { if (it.section == section) transform(it) else it })
        }
    }

    fun buildChanges(): Map<String, Map<String, Any>> {
        val changes = mutableMapOf<String, MutableMap<String, Any>>()
        fun put(section: String, key: String, value: Any) {
            changes.getOrPut(section) { mutableMapOf() }[key] = value
        }
        for (radio in radios.orEmpty()) {
            val old = original.orEmpty().firstOrNull { it.section == radio.section }
            if (radio.disabled != (old?.disabled ?: false)) {
                put(radio.section, "disabled", if (radio.disabled) "1" else "0")
            }
            if (radio.channel != old?.channel && !radio.channel.isNullOrBlank()) {
                put(radio.section, "channel", radio.channel)
            }
            if (radio.htmode != old?.htmode && !radio.htmode.isNullOrBlank()) {
                put(radio.section, "htmode", radio.htmode)
            }
            if (radio.txpower != old?.txpower && !radio.txpower.isNullOrBlank()) {
                put(radio.section, "txpower", radio.txpower)
            }
            if (radio.country != old?.country && !radio.country.isNullOrBlank()) {
                put(radio.section, "country", radio.country)
            }
            if (radio.hwmode != old?.hwmode && !radio.hwmode.isNullOrBlank()) {
                put(radio.section, "hwmode", radio.hwmode)
            }
            for (iface in radio.ifaces) {
                val o = old?.ifaces?.firstOrNull { it.section == iface.section }
                if (iface.ssid != (o?.ssid ?: "")) put(iface.section, "ssid", iface.ssid)
                if (iface.key != o?.key && iface.key != null) put(iface.section, "key", iface.key)
                if (iface.encryption != o?.encryption && iface.encryption != null) {
                    put(iface.section, "encryption", iface.encryption)
                }
                if (iface.hidden != (o?.hidden ?: false)) {
                    put(iface.section, "hidden", if (iface.hidden) "1" else "0")
                }
                if (iface.mode != o?.mode && !iface.mode.isNullOrBlank()) {
                    put(iface.section, "mode", iface.mode)
                }
                if (iface.network != o?.network && !iface.network.isNullOrBlank()) {
                    put(iface.section, "network", iface.network)
                }
                if (iface.isolate != (o?.isolate ?: false)) {
                    put(iface.section, "isolate", if (iface.isolate) "1" else "0")
                }
                if (iface.wmm != (o?.wmm ?: true)) {
                    put(iface.section, "wmm", if (iface.wmm) "1" else "0")
                }
                if (iface.bssid != o?.bssid && !iface.bssid.isNullOrBlank()) {
                    put(iface.section, "bssid", iface.bssid)
                }
                if (iface.dtim != o?.dtim && !iface.dtim.isNullOrBlank()) {
                    put(iface.section, "dtim", iface.dtim)
                }
                if (iface.beaconInt != o?.beaconInt && !iface.beaconInt.isNullOrBlank()) {
                    put(iface.section, "beacon_int", iface.beaconInt)
                }
                if (iface.frag != o?.frag && !iface.frag.isNullOrBlank()) {
                    put(iface.section, "frag", iface.frag)
                }
                if (iface.rts != o?.rts && !iface.rts.isNullOrBlank()) {
                    put(iface.section, "rts", iface.rts)
                }
                if (iface.shortPreamble != (o?.shortPreamble ?: true)) {
                    put(iface.section, "short_preamble", if (iface.shortPreamble) "1" else "0")
                }
                if (iface.macfilter != o?.macfilter && iface.macfilter != null) {
                    put(iface.section, "macfilter", iface.macfilter)
                }
                if (iface.maclist != (o?.maclist ?: emptyList())) {
                    put(iface.section, "maclist", iface.maclist)
                }
                if (iface.disabled != (o?.disabled ?: false)) {
                    put(iface.section, "disabled", if (iface.disabled) "1" else "0")
                }
            }
        }
        return changes
    }

    val changes = buildChanges()

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
        }
        Text(
            "无线设置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface
        )

        if (loading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
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
                    "无线设置通过路由器 SSH 应用，请先在设备设置中开启 SSH（读取仅需要 LuCI 访问）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // —— 网卡 ——
                Text(
                    "网卡",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    modifier = Modifier.padding(start = 4.dp)
                )
                radios.orEmpty().forEach { radio ->
                    AppCard {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Router,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    radio.section,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface
                                )
                                Spacer(Modifier.weight(1f))
                                Switch(
                                    checked = !radio.disabled,
                                    onCheckedChange = { on ->
                                        updateRadio(radio.section) { it.copy(disabled = !on) }
                                    }
                                )
                            }
                            Text(
                                buildString {
                                    radio.liveHwmodesText?.let { append("$it　·　") }
                                    append("信道 ${radio.liveChannel ?: radio.channel ?: "auto"}")
                                    radio.liveTxpower?.let { append("　·　功率 $it dBm") }
                                    radio.liveNoise?.let { append("　·　噪声 $it dBm") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    com.mmckb.openwrtstatus.data.ssh.SshExec.run(
                                                        ssh, "wifi reload"
                                                    )
                                                }
                                                setMsg("网卡已重启。", false)
                                            } catch (e: Exception) {
                                                setMsg(e.message ?: "重启失败。", true)
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    },
                                    enabled = !busy
                                ) { Text("重启") }
                                TextButton(
                                    onClick = {
                                        scanFor = radio.section
                                        scanResults = null
                                        scanBusy = true
                                        scope.launch {
                                            try {
                                                scanResults = withContext(Dispatchers.IO) {
                                                    client.scan(config, radio.section)
                                                }
                                            } catch (e: Exception) {
                                                setMsg(e.message ?: "扫描失败。", true)
                                            } finally {
                                                scanBusy = false
                                            }
                                        }
                                    },
                                    enabled = !busy
                                ) { Text("扫描") }
                                TextButton(
                                    onClick = {
                                        addSsid = ""
                                        addKey = ""
                                        addEncryption = "psk2"
                                        addWifiFor = radio.section
                                    },
                                    enabled = !busy
                                ) { Text("添加 WiFi") }
                            }
                        }
                    }
                }

                // —— WiFi（接口，LuCI 信息形态） ——
                Text(
                    "WiFi",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    modifier = Modifier.padding(start = 4.dp)
                )
                val withParent = radios.orEmpty().flatMap { radio ->
                    radio.ifaces.map { Triple(it, radio, radio.section) }
                }
                if (withParent.isEmpty()) {
                    AppCard {
                        Text(
                            "无 WiFi 接口。可在上方网卡卡片点击「添加 WiFi」。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                withParent.forEach { (iface, radio, radioSection) ->
                    AppCard {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Wifi,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        iface.ssid.ifEmpty { "（未设置 SSID）" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        buildString {
                                            iface.liveBssid?.let { append("$it　·　") }
                                            append(iface.liveEncryption
                                                ?: encryptionLabel(iface.encryption))
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = !iface.disabled,
                                    onCheckedChange = { on ->
                                        updateIface(iface.section) { it.copy(disabled = !on) }
                                    }
                                )
                            }
                            Text(
                                buildString {
                                    append(radioSection)
                                    append("　·　信道 ${radio.liveChannel ?: radio.channel ?: "auto"}")
                                    iface.liveSignal?.let { append("　·　信号 $it dBm") }
                                    iface.clientCount?.let { append("　·　客户端 $it") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { deleteIfaceFor = iface.section },
                                    enabled = !busy
                                ) { Text("删除", color = colors.error) }
                                Button(
                                    onClick = {
                                        ifaceMode = iface.mode ?: "ap"
                                        ifaceSsid = iface.ssid
                                        ifaceNetwork = iface.network ?: "lan"
                                        ifaceEncryption = iface.encryption ?: "psk2"
                                        ifaceCipher = "auto"
                                        ifaceKey = iface.key ?: ""
                                        ifaceHidden = iface.hidden
                                        ifaceWmm = iface.wmm
                                        ifaceIsolate = iface.isolate
                                        ifaceMacfilter = iface.macfilter ?: "disable"
                                        ifaceMaclist = iface.maclist.joinToString("\n")
                                        ifaceBssid = iface.bssid ?: ""
                                        ifaceDtim = iface.dtim ?: ""
                                        ifaceBeaconInt = iface.beaconInt ?: ""
                                        ifaceFrag = iface.frag ?: ""
                                        ifaceRts = iface.rts ?: ""
                                        ifaceShortPreamble = iface.shortPreamble
                                        ifaceSectionTab = "general"
                                        editIfaceFor = iface.section
                                    },
                                    enabled = !busy,
                                    shape = AppShapes.pill,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 14.dp, vertical = 4.dp
                                    )
                                ) { Text("编辑") }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { showApplyConfirm = true },
                enabled = !busy && changes.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Text(if (changes.isEmpty()) "无更改" else "应用更改（${changes.size} 段）")
            }
        }
    }

    // ---- WiFi 编辑对话框（LuCI 分区：常规/安全/MAC 过滤/高级，顶部为方案选择器） ----
    editIfaceFor?.let { section ->
        AppDialog(
            title = "编辑 $section",
            confirmLabel = "保存",
            onConfirm = {
                updateIface(section) {
                    it.copy(
                        mode = ifaceMode,
                        ssid = ifaceSsid.trim(),
                        network = ifaceNetwork.trim(),
                        encryption = ifaceEncryption,
                        key = ifaceKey,
                        hidden = ifaceHidden,
                        wmm = ifaceWmm,
                        isolate = ifaceIsolate,
                        macfilter = ifaceMacfilter,
                        maclist = ifaceMaclist.lines().map { m -> m.trim() }
                            .filter { m -> m.isNotEmpty() },
                        bssid = ifaceBssid.trim(),
                        dtim = ifaceDtim.trim(),
                        beaconInt = ifaceBeaconInt.trim(),
                        frag = ifaceFrag.trim(),
                        rts = ifaceRts.trim(),
                        shortPreamble = ifaceShortPreamble
                    )
                }
                editIfaceFor = null
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) { client.apply(config, buildChanges()) }
                        setMsg("已应用，Wi-Fi 正在重载。", false)
                        load()
                    } catch (e: Exception) {
                        setMsg(e.message ?: "应用失败。", true)
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = { if (!busy) editIfaceFor = null }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SmoothOptionSwitcher(
                    options = listOf(
                        "general" to "常规设置",
                        "security" to "无线安全",
                        "macfilter" to "MAC 过滤",
                        "advanced" to "高级设置"
                    ),
                    selected = ifaceSectionTab,
                    onSelect = { ifaceSectionTab = it },
                    modifier = Modifier.fillMaxWidth()
                )
                when (ifaceSectionTab) {
                    "security" -> {
                        SelectRow("加密", encryptionLabel(ifaceEncryption)) {
                            selectState = SelectState(
                                "选择加密方式", SECURITY_OPTIONS, ifaceEncryption
                            ) { v -> ifaceEncryption = v }
                        }
                        if (ifaceEncryption != "none") {
                            OutlinedTextField(
                                value = ifaceKey,
                                onValueChange = { ifaceKey = it },
                                singleLine = true,
                                label = { Text("密码") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    "macfilter" -> {
                        SelectRow(
                            "MAC 过滤",
                            MACFILTER_OPTIONS.firstOrNull { it.first == ifaceMacfilter }?.second ?: "已禁用"
                        ) {
                            selectState = SelectState(
                                "选择 MAC 过滤", MACFILTER_OPTIONS, ifaceMacfilter
                            ) { v -> ifaceMacfilter = v }
                        }
                        OutlinedTextField(
                            value = ifaceMaclist,
                            onValueChange = { ifaceMaclist = it },
                            label = { Text("MAC 列表（每行一个）") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    "advanced" -> {
                        OutlinedTextField(
                            value = ifaceBssid,
                            onValueChange = { ifaceBssid = it },
                            singleLine = true,
                            label = { Text("BSSID（仅 STA 模式）") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = ifaceDtim,
                            onValueChange = { ifaceDtim = it },
                            singleLine = true,
                            label = { Text("DTIM 间隔") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = ifaceBeaconInt,
                            onValueChange = { ifaceBeaconInt = it },
                            singleLine = true,
                            label = { Text("信标间隔") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = ifaceFrag,
                            onValueChange = { ifaceFrag = it },
                            singleLine = true,
                            label = { Text("分片阈值") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = ifaceRts,
                            onValueChange = { ifaceRts = it },
                            singleLine = true,
                            label = { Text("RTS/CTS 阈值") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        SettingRow("短前导码", ifaceShortPreamble) { ifaceShortPreamble = it }
                    }
                    else -> {
                        SelectRow("模式", if (ifaceMode == "ap") "AP 接入点" else ifaceMode.uppercase()) {
                            selectState = SelectState(
                                "选择模式",
                                listOf("ap" to "AP 接入点", "sta" to "STA 客户端", "mesh" to "Mesh"),
                                ifaceMode
                            ) { v -> ifaceMode = v }
                        }
                        OutlinedTextField(
                            value = ifaceSsid,
                            onValueChange = { ifaceSsid = it },
                            singleLine = true,
                            label = { Text("SSID（Wi-Fi 名称）") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        SelectRow("网络", ifaceNetwork.ifBlank { "lan" }) {
                            selectState = SelectState(
                                "选择网络",
                                listOf("lan" to "lan", "wan" to "wan", "wan6" to "wan6"),
                                ifaceNetwork
                            ) { v -> ifaceNetwork = v }
                        }
                        SettingRow("隐藏 ESSID", ifaceHidden) { ifaceHidden = it }
                        SettingRow("WMM 模式", ifaceWmm) { ifaceWmm = it }
                        SettingRow("隔离客户端", ifaceIsolate) { ifaceIsolate = it }
                    }
                }
            }
        }
    }

    // ---- 网卡编辑对话框（设备配置，全部选择项） ----
    editRadioFor?.let { section ->
        val radio = radios.orEmpty().firstOrNull { it.section == section }
        val band = radio?.band
        val hwmode = if (radioHwmode.isBlank()) {
            when (band) { "5g" -> "11ac"; else -> "11n" }
        } else radioHwmode
        AppDialog(
            title = "设备配置（$section）",
            confirmLabel = "确定",
            onConfirm = {
                updateRadio(section) {
                    it.copy(
                        channel = radioChannel.trim(),
                        htmode = radioHtmode.trim(),
                        txpower = radioTxpower.trim(),
                        country = radioCountry.trim(),
                        hwmode = hwmode
                    )
                }
                editRadioFor = null
            },
            onDismiss = { editRadioFor = null }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectRow("工作频率", when (hwmode) {
                    "11ax" -> "AX"
                    "11ac" -> "AC"
                    "11n" -> "N"
                    "11g" -> "G"
                    "11b" -> "B"
                    else -> hwmode
                }) {
                    selectState = SelectState(
                        "选择工作频率",
                        if (band == "5g") {
                            listOf("11n" to "N", "11ac" to "AC", "11ax" to "AX")
                        } else {
                            listOf("11b" to "B", "11g" to "G", "11n" to "N", "11ax" to "AX")
                        },
                        hwmode
                    ) { v ->
                        radioHwmode = v
                        // 切换协议后保持频宽数值，前缀跟随协议。
                        val width = Regex("\\d+").find(radioHtmode)?.value ?: when (v) {
                            "11ac" -> "80"
                            else -> "20"
                        }
                        val prefix = when (v) {
                            "11n" -> "HT"
                            "11ac" -> "VHT"
                            "11ax" -> "HE"
                            else -> "HT"
                        }
                        radioHtmode = "$prefix$width"
                    }
                }
                SelectRow("频宽", htmodeLabel(radioHtmode.ifBlank { null })) {
                    selectState = SelectState(
                        "选择频宽", htmodeOptions(band, hwmode), radioHtmode
                    ) { v -> radioHtmode = v }
                }
                SelectRow("信道", "${radioChannel.ifBlank { "auto" }}") {
                    selectState = SelectState(
                        "选择信道", channelOptions(band), radioChannel
                    ) { v -> radioChannel = v }
                }
                SelectRow(
                    "最大传输功率",
                    radioTxpower.ifBlank { "驱动默认" }
                ) {
                    selectState = SelectState(
                        "选择最大传输功率", txpowerOptions(), radioTxpower
                    ) { v -> radioTxpower = v }
                }
                SelectRow("国家代码", radioCountry.ifBlank { "驱动默认" }) {
                    selectState = SelectState(
                        "选择国家代码", COUNTRY_OPTIONS.map { it to it }, radioCountry
                    ) { v -> radioCountry = v }
                }
            }
        }
    }

    // ---- 添加 WiFi 对话框 ----
    addWifiFor?.let { device ->
        AppDialog(
            title = "添加 WiFi（$device）",
            confirmLabel = "添加",
            confirmEnabled = addSsid.isNotBlank(),
            onConfirm = {
                val dev = device
                val ssid = addSsid.trim()
                val key = addKey
                val enc = addEncryption
                addWifiFor = null
                scope.launch {
                    busy = true
                    message = null
                    try {
                        withContext(Dispatchers.IO) { client.addIface(config, dev, ssid, key, enc) }
                        setMsg("WiFi 已添加并重载无线。", false)
                        load()
                    } catch (e: Exception) {
                        setMsg(e.message ?: "添加失败。", true)
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = { if (!busy) addWifiFor = null }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = addSsid,
                    onValueChange = { addSsid = it },
                    singleLine = true,
                    label = { Text("SSID（Wi-Fi 名称）") },
                    modifier = Modifier.fillMaxWidth()
                )
                SelectRow("加密", encryptionLabel(addEncryption)) {
                    selectState = SelectState(
                        "选择加密方式", SECURITY_OPTIONS, addEncryption
                    ) { v -> addEncryption = v }
                }
                if (addEncryption != "none") {
                    OutlinedTextField(
                        value = addKey,
                        onValueChange = { addKey = it },
                        singleLine = true,
                        label = { Text("密码") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    deleteIfaceFor?.let { section ->
        AppDialog(
            title = "删除接口",
            message = "确定删除「$section」吗？该 WiFi 将从路由器移除。",
            confirmLabel = "删除",
            confirmColor = colors.error,
            onConfirm = {
                val sec = section
                deleteIfaceFor = null
                scope.launch {
                    busy = true
                    message = null
                    try {
                        withContext(Dispatchers.IO) { client.deleteIface(config, sec) }
                        setMsg("接口已删除并重载无线。", false)
                        load()
                    } catch (e: Exception) {
                        setMsg(e.message ?: "删除失败。", true)
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = { deleteIfaceFor = null }
        )
    }

    // ---- 扫描结果对话框 ----
    scanFor?.let { device ->
        AppDialog(
            title = "扫描（$device）",
            confirmLabel = "关闭",
            dismissLabel = "",
            onConfirm = { scanFor = null },
            onDismiss = { scanFor = null }
        ) {
            if (scanBusy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
            } else {
                val nets = scanResults.orEmpty()
                if (nets.isEmpty()) {
                    Text(
                        "未扫描到网络。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        nets.forEach { net ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text(
                                    net.ssid.ifEmpty { "(隐藏网络)" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.onSurface
                                )
                                Text(
                                    buildString {
                                        append(net.bssid)
                                        net.channel?.let { append("　·　信道 $it") }
                                        net.signal?.let { append("　·　$it dBm") }
                                        if (net.encrypted) append("　·　加密")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- 通用选择对话框 ----
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
                    .heightIn(max = 340.dp)
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
                            .padding(vertical = 10.dp)
                    )
                }
            }
        }
    }
}
