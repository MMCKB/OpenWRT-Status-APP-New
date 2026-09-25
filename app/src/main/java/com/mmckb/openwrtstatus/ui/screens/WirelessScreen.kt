package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.background
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

private val ENCRYPTION_OPTIONS = listOf(
    "none" to "无加密",
    "psk" to "WPA-PSK",
    "psk2" to "WPA2-PSK",
    "psk-mixed" to "WPA/WPA2 混合",
    "sae" to "WPA3-SAE",
    "sae-mixed" to "WPA2/WPA3 混合",
    "wep-open" to "WEP 开放",
    "wep-shared" to "WEP 共享"
)

/** 卡片信息行：标签 + 当前值。 */
@Composable
private fun InfoRow(label: String, value: String) {
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

/** 卡片右下角的编辑小按钮。 */
@Composable
private fun EditButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = AppShapes.pill,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 14.dp, vertical = 4.dp
        )
    ) { Text(label) }
}

private fun bandLabel(band: String?): String = when (band) {
    "5g" -> "5 GHz"
    "2g" -> "2.4 GHz"
    "6g" -> "6 GHz"
    else -> band ?: ""
}

/**
 * 无线设置页（二级页，独立 Activity）：与 LuCI 对齐的全部无线设置。
 * 卡片只展示当前值；信道/htmode/发射功率/国家、SSID/网络/加密/密码等
 * 通过卡片右下角「编辑」按钮在对话框中修改；支持添加/删除 WiFi 接口。
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

    // 组合编辑对话框目标与临时字段。
    var editRadioFor by remember { mutableStateOf<String?>(null) }
    var radioChannel by remember { mutableStateOf("") }
    var radioHtmode by remember { mutableStateOf("") }
    var radioTxpower by remember { mutableStateOf("") }
    var radioCountry by remember { mutableStateOf("") }

    var editIfaceFor by remember { mutableStateOf<String?>(null) }
    var ifaceMode by remember { mutableStateOf("ap") }
    var ifaceSsid by remember { mutableStateOf("") }
    var ifaceNetwork by remember { mutableStateOf("") }
    var ifaceEncryption by remember { mutableStateOf("psk2") }
    var ifaceKey by remember { mutableStateOf("") }

    var addWifiFor by remember { mutableStateOf<String?>(null) }
    var addSsid by remember { mutableStateOf("") }
    var addKey by remember { mutableStateOf("") }
    var addEncryption by remember { mutableStateOf("psk2") }

    var deleteIfaceFor by remember { mutableStateOf<String?>(null) }

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
                // —— 无线网卡（wifi-device） ——
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    radio.section,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface
                                )
                                Text(
                                    buildString {
                                        bandLabel(radio.band).let { if (it.isNotEmpty()) append("$it　·　") }
                                        append("信道 ${radio.channel ?: "auto"}")
                                        radio.htmode?.let { append("　·　$it") }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = !radio.disabled,
                                onCheckedChange = { on ->
                                    updateRadio(radio.section) { it.copy(disabled = !on) }
                                }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        InfoRow("发射功率", radio.txpower?.let { "$it dBm" } ?: "默认")
                        InfoRow("国家代码", radio.country ?: "默认")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                radioChannel = radio.channel ?: "auto"
                                radioHtmode = radio.htmode ?: ""
                                radioTxpower = radio.txpower ?: ""
                                radioCountry = radio.country ?: ""
                                editRadioFor = radio.section
                            }) { Text("编辑") }
                        }
                    }
                }

                // —— WiFi（wifi-iface 接口） ——
                Text(
                    "WiFi",
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    buildString {
                                        append(radio.section)
                                        bandLabel(radio.band).let { if (it.isNotEmpty()) append("　·　$it") }
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface
                                )
                                Text(
                                    "接口 ${radio.ifaces.size} 个",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            EditButton("添加 WiFi", enabled = !busy) {
                                addSsid = ""
                                addKey = ""
                                addEncryption = "psk2"
                                addWifiFor = radio.section
                            }
                        }
                        if (radio.ifaces.isEmpty()) {
                            Text(
                                "此网卡暂无 WiFi 接口",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        radio.ifaces.forEach { iface ->
                            HorizontalDivider(color = colors.outline, modifier = Modifier.padding(vertical = 8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        iface.ssid.ifEmpty { "（未设置 SSID）" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        buildString {
                                            append(iface.encryption ?: "未设置加密")
                                            iface.mode?.let { append("　·　${if (it == "ap") "AP" else it.uppercase()}") }
                                            if (iface.hidden) append("　·　隐藏")
                                            if (iface.disabled) append("　·　已停用")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                EditButton("编辑", enabled = !busy) {
                                    ifaceMode = iface.mode ?: "ap"
                                    ifaceSsid = iface.ssid
                                    ifaceNetwork = iface.network ?: "lan"
                                    ifaceEncryption = iface.encryption ?: "psk2"
                                    ifaceKey = iface.key ?: ""
                                    editIfaceFor = iface.section
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    val iface = radio.ifaces.firstOrNull() ?: return@TextButton
                                    deleteIfaceFor = iface.section
                                },
                                enabled = radio.ifaces.isNotEmpty()
                            ) { Text("删除接口", color = colors.error) }
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

    // ---- 编辑对话框 ----

    editRadioFor?.let { section ->
        AppDialog(
            title = section,
            confirmLabel = "确定",
            onConfirm = {
                updateRadio(section) {
                    it.copy(
                        channel = radioChannel.trim(),
                        htmode = radioHtmode.trim(),
                        txpower = radioTxpower.trim(),
                        country = radioCountry.trim()
                    )
                }
                editRadioFor = null
            },
            onDismiss = { editRadioFor = null }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = radioChannel,
                    onValueChange = { radioChannel = it },
                    singleLine = true,
                    label = { Text("信道（auto 或数字）") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = radioHtmode,
                    onValueChange = { radioHtmode = it },
                    singleLine = true,
                    label = { Text("HT 模式（如 HE80 / HT20）") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = radioTxpower,
                    onValueChange = { radioTxpower = it },
                    singleLine = true,
                    label = { Text("发射功率 dBm（留空默认）") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = radioCountry,
                    onValueChange = { radioCountry = it },
                    singleLine = true,
                    label = { Text("国家代码（如 CN）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    editIfaceFor?.let { section ->
        val encLabel = ENCRYPTION_OPTIONS.firstOrNull { it.first == ifaceEncryption }?.second ?: ifaceEncryption
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
                // 模式
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("模式", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    var modeMenu by remember { mutableStateOf(false) }
                    TextButton(onClick = { modeMenu = !modeMenu }) { Text(ifaceMode) }
                    androidx.compose.material3.DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                        listOf("ap", "sta", "mesh").forEach { m ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(m) },
                                onClick = {
                                    ifaceMode = m
                                    modeMenu = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = ifaceSsid,
                    onValueChange = { ifaceSsid = it },
                    singleLine = true,
                    label = { Text("SSID（Wi-Fi 名称）") },
                    modifier = Modifier.fillMaxWidth()
                )
                // 网络
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("网络", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    var networkMenu by remember { mutableStateOf(false) }
                    TextButton(onClick = { networkMenu = !networkMenu }) { Text(ifaceNetwork.ifBlank { "lan" }) }
                    androidx.compose.material3.DropdownMenu(expanded = networkMenu, onDismissRequest = { networkMenu = false }) {
                        listOf("lan", "wan", "wan6").forEach { n ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(n) },
                                onClick = {
                                    ifaceNetwork = n
                                    networkMenu = false
                                }
                            )
                        }
                    }
                }
                // 加密
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("加密", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    var encMenu by remember { mutableStateOf(false) }
                    TextButton(onClick = { encMenu = !encMenu }) { Text(encLabel) }
                    androidx.compose.material3.DropdownMenu(expanded = encMenu, onDismissRequest = { encMenu = false }) {
                        ENCRYPTION_OPTIONS.forEach { (value, label) ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("$label ($value)") },
                                onClick = {
                                    ifaceEncryption = value
                                    encMenu = false
                                }
                            )
                        }
                    }
                }
                if (ifaceEncryption != "none") {
                    OutlinedTextField(
                        value = ifaceKey,
                        onValueChange = { ifaceKey = it },
                        singleLine = true,
                        label = { Text("密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    addWifiFor?.let { device ->
        val encLabel = ENCRYPTION_OPTIONS.firstOrNull { it.first == addEncryption }?.second ?: addEncryption
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("加密", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    var encMenu by remember { mutableStateOf(false) }
                    TextButton(onClick = { encMenu = !encMenu }) { Text(encLabel) }
                    androidx.compose.material3.DropdownMenu(expanded = encMenu, onDismissRequest = { encMenu = false }) {
                        ENCRYPTION_OPTIONS.forEach { (value, label) ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("$label ($value)") },
                                onClick = {
                                    addEncryption = value
                                    encMenu = false
                                }
                            )
                        }
                    }
                }
                if (addEncryption != "none") {
                    OutlinedTextField(
                        value = addKey,
                        onValueChange = { addKey = it },
                        singleLine = true,
                        label = { Text("密码") },
                        visualTransformation = PasswordVisualTransformation(),
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

    if (showApplyConfirm) {
        AppDialog(
            title = "应用无线设置",
            message = "将写入配置并重载无线，Wi-Fi 会短暂断开。如果你修改的是手机当前连接的网络，需要重新连接。",
            confirmLabel = "应用",
            onConfirm = {
                showApplyConfirm = false
                scope.launch {
                    busy = true
                    message = null
                    try {
                        withContext(Dispatchers.IO) { client.apply(config, changes) }
                        setMsg("已应用，Wi-Fi 正在重载。", false)
                        load()
                    } catch (e: Exception) {
                        setMsg(e.message ?: "应用失败。", true)
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = { showApplyConfirm = false }
        )
    }
}
