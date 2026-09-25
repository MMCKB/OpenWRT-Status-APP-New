package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.remote.WirelessClient
import com.mmckb.openwrtstatus.data.remote.WirelessIface
import com.mmckb.openwrtstatus.data.remote.WirelessRadio
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 加密方式（value to 中文标签），与 LuCI 的无线加密选项对齐。 */
private val ENCRYPTION_OPTIONS = listOf(
    "none" to "无加密",
    "psk" to "WPA-PSK",
    "psk2" to "WPA2-PSK",
    "psk-mixed" to "WPA/WPA2 混合",
    "sae" to "WPA3-SAE",
    "sae-mixed" to "WPA2/WPA3-SAE 混合",
    "wep-open" to "WEP 开放",
    "wep-shared" to "WEP 共享"
)

private fun bandLabel(band: String?): String = when (band) {
    "5g" -> "5 GHz"
    "2g" -> "2.4 GHz"
    "6g" -> "6 GHz"
    else -> band ?: ""
}

private fun encryptionLabel(value: String?): String =
    ENCRYPTION_OPTIONS.firstOrNull { it.first == value }?.second ?: (value ?: "未设置")

private fun channelOptions(band: String?): List<Pair<String, String>> {
    val list = mutableListOf("auto" to "自动")
    when (band) {
        "5g" -> listOf(
            36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120,
            124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165
        ).forEach { list.add("$it" to "$it") }
        else -> (1..13).forEach { list.add("$it" to "$it") }
    }
    return list
}

private fun htmodeOptions(band: String?): List<Pair<String, String>> = when (band) {
    "2g" -> listOf("HT20", "HT40", "HE20", "HE40")
    "5g" -> listOf(
        "HT20", "HT40", "VHT20", "VHT40", "VHT80", "VHT160",
        "HE20", "HE40", "HE80", "HE160"
    )
    "6g" -> listOf("HE20", "HE40", "HE80", "HE160")
    else -> listOf("HT20", "HT40", "VHT80", "HE80")
}

private fun txpowerOptions(): List<Pair<String, String>> {
    val list = mutableListOf("" to "默认")
    (30 downTo 1).forEach { list.add("$it" to "$it dBm") }
    return list
}

private val COUNTRY_OPTIONS = listOf(
    "CN", "US", "AU", "CA", "DE", "FR", "GB", "JP", "KR", "HK", "TW", "SG", "NZ", "NL"
)

/** 通用下拉选择对话框（应用风格）。 */
private data class SelectState(
    val title: String,
    val options: List<Pair<String, String>>,
    val selected: String?,
    val onPick: (String) -> Unit
)

/**
 * 无线设置页（二级页，独立 Activity）：卡片只展示当前值；
 * 网卡的信道/HT 模式/发射功率/国家代码通过选择项修改；
 * WiFi 接口支持添加、删除与全部 LuCI 字段编辑。
 */
@Composable
fun WirelessScreen(
    config: RouterConfig,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val client = remember { WirelessClient() }

    var radios by remember { mutableStateOf<List<WirelessRadio>?>(null) }
    var original by remember { mutableStateOf<List<WirelessRadio>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var showApplyConfirm by remember { mutableStateOf(false) }

    var editIfaceFor by remember { mutableStateOf<String?>(null) }
    var ifaceMode by remember { mutableStateOf("ap") }
    var ifaceSsid by remember { mutableStateOf("") }
    var ifaceNetwork by remember { mutableStateOf("lan") }
    var ifaceEncryption by remember { mutableStateOf("psk2") }
    var ifaceKey by remember { mutableStateOf("") }

    var addWifiFor by remember { mutableStateOf<String?>(null) }
    var addSsid by remember { mutableStateOf("") }
    var addKey by remember { mutableStateOf("") }
    var addEncryption by remember { mutableStateOf("psk2") }

    var deleteIfaceFor by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(Unit) { load() }

    fun updateRadio(section: String, transform: (WirelessRadio) -> WirelessRadio) {
        radios = radios.orEmpty().map { if (it.section == section) transform(it) else it }
    }

    fun updateIface(section: String, transform: (WirelessIface) -> WirelessIface) {
        radios = radios.orEmpty().map { radio ->
            radio.copy(ifaces = radio.ifaces.map { if (it.section == section) transform(it) else it })
        }
    }

    fun buildChanges(): Map<String, Map<String, String>> {
        val changes = mutableMapOf<String, MutableMap<String, String>>()
        fun put(section: String, key: String, value: String) {
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
        loadError?.let {
            AppCard {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
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

        if (!loading && loadError == null) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // —— 无线网卡（wifi-device）：全部为选择项 ——
                Text(
                    "无线网卡",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    modifier = Modifier.padding(start = 4.dp)
                )
                radios.orEmpty().forEach { radio ->
                    AppCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                buildString {
                                    append(radio.section)
                                    bandLabel(radio.band).let { if (it.isNotEmpty()) append("　·　$it") }
                                },
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
                        Spacer(Modifier.height(4.dp))
                        SelectRow("信道", radio.channel ?: "auto") {
                            selectState = SelectState(
                                "选择信道", channelOptions(radio.band), radio.channel
                            ) { v -> updateRadio(radio.section) { it.copy(channel = v) } }
                        }
                        SelectRow("HT 模式", radio.htmode ?: "-") {
                            selectState = SelectState(
                                "选择 HT 模式", htmodeOptions(radio.band), radio.htmode
                            ) { v -> updateRadio(radio.section) { it.copy(htmode = v) } }
                        }
                        SelectRow(
                            "发射功率",
                            radio.txpower?.let { "$it dBm" } ?: "默认"
                        ) {
                            selectState = SelectState(
                                "选择发射功率", txpowerOptions(), radio.txpower ?: ""
                            ) { v -> updateRadio(radio.section) { it.copy(txpower = v) } }
                        }
                        SelectRow("国家代码", radio.country ?: "默认") {
                            selectState = SelectState(
                                "选择国家代码",
                                COUNTRY_OPTIONS.map { it to it },
                                radio.country
                            ) { v -> updateRadio(radio.section) { it.copy(country = v) } }
                        }
                    }
                }

                // —— WiFi（wifi-iface 接口，LuCI 信息形态） ——
                Text(
                    "WiFi",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    modifier = Modifier.padding(start = 4.dp)
                )
                val withParent = radios.orEmpty().flatMap { radio ->
                    radio.ifaces.map { it to radio }
                }
                if (withParent.isEmpty()) {
                    AppCard {
                        Text(
                            "无 WiFi 接口",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                withParent.forEach { (iface, radio) ->
                    AppCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
                                        append(if (iface.mode == "ap") "AP" else (iface.mode?.uppercase() ?: "-"))
                                        append("　·　信道 ${radio.channel ?: "auto"}")
                                        bandLabel(radio.band).let { if (it.isNotEmpty()) append(" ($it)") }
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
                        Spacer(Modifier.height(4.dp))
                        InfoRow("加密", encryptionLabel(iface.encryption))
                        InfoRow("网络", iface.network ?: "lan")
                        if (iface.hidden) InfoRow("隐藏", "是")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { deleteIfaceFor = iface.section },
                                enabled = !busy
                            ) { Text("删除", color = colors.error) }
                            TextButton(
                                onClick = {
                                    ifaceMode = iface.mode ?: "ap"
                                    ifaceSsid = iface.ssid
                                    ifaceNetwork = iface.network ?: "lan"
                                    ifaceEncryption = iface.encryption ?: "psk2"
                                    ifaceKey = iface.key ?: ""
                                    editIfaceFor = iface.section
                                },
                                enabled = !busy
                            ) { Text("编辑") }
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
                        "选择加密方式", ENCRYPTION_OPTIONS, addEncryption
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

    // ---- 接口编辑对话框（全部字段，选择项用应用风格对话框） ----
    editIfaceFor?.let { section ->
        AppDialog(
            title = section,
            confirmLabel = "确定",
            onConfirm = {
                updateIface(section) {
                    it.copy(
                        mode = ifaceMode,
                        ssid = ifaceSsid.trim(),
                        network = ifaceNetwork.trim(),
                        encryption = ifaceEncryption,
                        key = ifaceKey
                    )
                }
                editIfaceFor = null
            },
            onDismiss = { editIfaceFor = null }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectRow("模式", if (ifaceMode == "ap") "AP" else ifaceMode.uppercase()) {
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
                SelectRow("加密", encryptionLabel(ifaceEncryption)) {
                    selectState = SelectState(
                        "选择加密方式", ENCRYPTION_OPTIONS, ifaceEncryption
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

    // ---- 通用选择对话框（应用风格，替代 MD3 菜单） ----
    selectState?.let { sel ->
        AppDialog(
            title = sel.title,
            confirmLabel = "关闭",
            dismissLabel = "",
            onConfirm = { selectState = null },
            onDismiss = { selectState = null }
        ) {
            LazyColumn(
                modifier = Modifier.height(340.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(sel.options, key = { it.first }) { (value, label) ->
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

/** 信息行：标签 + 当前值（LuCI 无线页同款信息形态）。 */
@Composable
private fun InfoRow(label: String, value: String) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 选择行：点击后弹出应用风格的选择对话框。 */
@Composable
private fun SelectRow(label: String, value: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}
