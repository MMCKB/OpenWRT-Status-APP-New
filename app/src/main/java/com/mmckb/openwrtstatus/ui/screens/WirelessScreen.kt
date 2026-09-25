package com.mmckb.openwrtstatus.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.VisualTransformation
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

private val ENCRYPTION_OPTIONS = listOf("psk2", "psk2-mixed", "sae-mixed", "sae", "none")

/**
 * 无线设置页（二级页，独立 Activity）：读取 /etc/config/wireless，
 * 编辑 radio 的信道/开关与各接口的 SSID/密码/加密/隐藏/开关，
 * 应用时 uci 写回 + network reload 重载无线（Wi-Fi 会短暂断开）。
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
    var keyVisibleFor by remember { mutableStateOf<String?>(null) }
    var encryptionMenuFor by remember { mutableStateOf<String?>(null) }

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
                        Text(
                            buildString {
                                append(radio.section)
                                radio.band?.let { append("　·　${if (it == "5g") "5 GHz" else if (it == "2g") "2.4 GHz" else it}") }
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = radio.channel ?: "",
                            onValueChange = { v ->
                                updateRadio(radio.section) { it.copy(channel = v.trim()) }
                            },
                            singleLine = true,
                            label = { Text("信道（auto 或数字）") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "启用 radio",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = !radio.disabled,
                                onCheckedChange = { on ->
                                    updateRadio(radio.section) { it.copy(disabled = !on) }
                                }
                            )
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
                AppCard {
                    val allIfaces = radios.orEmpty().flatMap { radio ->
                        radio.ifaces.map { it to radio }
                    }
                    if (allIfaces.isEmpty()) {
                        Text(
                            "无 WiFi 接口",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                    allIfaces.forEachIndexed { ifaceIndex, (iface, radio) ->
                        if (ifaceIndex > 0) {
                            HorizontalDivider(color = colors.outline, modifier = Modifier.padding(vertical = 8.dp))
                        }
                        Text(
                            buildString {
                                append(iface.section)
                                append("　·　${radio.section}")
                                radio.band?.let { append("　·　${if (it == "5g") "5 GHz" else if (it == "2g") "2.4 GHz" else it}") }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = iface.ssid,
                            onValueChange = { v ->
                                updateIface(iface.section) { it.copy(ssid = v) }
                            },
                            singleLine = true,
                            label = { Text("SSID（Wi-Fi 名称）") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = iface.key ?: "",
                            onValueChange = { v ->
                                updateIface(iface.section) { it.copy(key = v) }
                            },
                            singleLine = true,
                            label = { Text("密码") },
                            visualTransformation = if (keyVisibleFor == iface.section) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                TextButton(onClick = {
                                    keyVisibleFor = if (keyVisibleFor == iface.section) null else iface.section
                                }) { Text(if (keyVisibleFor == iface.section) "隐藏" else "显示") }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "加密",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                encryptionMenuFor = if (encryptionMenuFor == iface.section) null else iface.section
                            }) {
                                Text(iface.encryption ?: "未设置")
                            }
                            androidx.compose.material3.DropdownMenu(
                                expanded = encryptionMenuFor == iface.section,
                                onDismissRequest = { encryptionMenuFor = null }
                            ) {
                                ENCRYPTION_OPTIONS.forEach { option ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = {
                                            updateIface(iface.section) { it.copy(encryption = option) }
                                            encryptionMenuFor = null
                                        }
                                    )
                                }
                            }
                        }
                        SettingRow("隐藏网络（不广播 SSID）", iface.hidden) {
                            updateIface(iface.section) { it.copy(hidden = !it.hidden) }
                        }
                        SettingRow("启用此接口", !iface.disabled) {
                            updateIface(iface.section) { it.copy(disabled = !it.disabled) }
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

// it2/it2 占位防止误用：直接以内联表达式实现。
private fun it2(current: WirelessIface, value: Boolean): Boolean = value
private fun WirelessIface.enabled2(current: WirelessIface, value: Boolean): Boolean = value

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
