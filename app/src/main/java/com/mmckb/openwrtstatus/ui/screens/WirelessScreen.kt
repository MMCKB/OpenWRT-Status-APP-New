package com.mmckb.openwrtstatus.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.remote.LuciFeatures
import com.mmckb.openwrtstatus.data.remote.ScanNet
import com.mmckb.openwrtstatus.data.remote.WirelessClient
import com.mmckb.openwrtstatus.data.remote.WirelessIface
import com.mmckb.openwrtstatus.data.remote.WirelessRadio
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.components.PredictiveBackEasing
import com.mmckb.openwrtstatus.ui.components.SmoothOptionSwitcher
import com.mmckb.openwrtstatus.ui.components.ThinScrollbarColumn
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val UPLOAD_TMP_PATH = "/tmp/upload.apk"

/** 加密方式清单：与 LuCI crypto_modes 同序同集（按特性裁剪，默认回退视为完整版 wpad）。 */
private fun securityOptions(features: LuciFeatures?): List<Pair<String, String>> {
    val f = features ?: LuciFeatures.FALLBACK
    return buildList {
        add("none" to "无加密")
        if (f.hostapdEap) {
            if (f.hostapdSuiteb192) add("wpa3-192" to "WPA3-EAP 192 位")
            add("psk2" to "WPA2-PSK")
            add("wpa2" to "WPA2-EAP")
            add("wpa3" to "WPA3-EAP")
            add("wpa3-mixed" to "WPA2-EAP/WPA3-EAP 混合")
        }
        if (f.hostapdSae) {
            add("sae" to "WPA3-SAE")
            add("sae-mixed" to "WPA2-PSK/WPA3-SAE 混合")
            add("sae-compat" to "WPA2-PSK/WPA3-SAE 兼容模式")
        }
        add("psk-mixed" to "WPA-PSK/WPA2-PSK 混合")
        add("wpa" to "WPA-EAP")
        add("psk" to "WPA-PSK")
        if (f.hostapdOwe) add("owe" to "OWE (增强开放)")
    }
}

/** 需要填写 PSK 密码的加密方式。 */
private val PSK_ENCRYPTIONS = setOf("psk", "psk2", "psk-mixed", "sae", "sae-mixed", "sae-compat")

/** 企业级（EAP/RADIUS）加密方式。 */
private val EAP_ENCRYPTIONS = setOf("wpa", "wpa2", "wpa3", "wpa3-mixed", "wpa3-192")

/** 加密方式是否需要密码（含 802.11w/算法选择的家族，同 LuCI cipher 依赖）。 */
private val CIPHER_ENCRYPTIONS = setOf("psk", "psk2", "psk-mixed", "sae", "wpa", "wpa2", "wpa3", "wpa3-mixed", "wpa3-192")

/** 算法选项（LuCI 同款）。 */
private val CIPHER_OPTIONS = listOf(
    "auto" to "auto",
    "ccmp" to "强制 CCMP (AES)",
    "ccmp256" to "强制 CCMP-256 (AES)",
    "gcmp" to "强制 GCMP (AES)",
    "gcmp256" to "强制 GCMP-256 (AES)",
    "tkip" to "强制 TKIP",
    "tkip+ccmp" to "强制 TKIP + CCMP (AES)"
)

private val MACFILTER_OPTIONS = listOf(
    "" to "已禁用",
    "allow" to "仅允许列表内",
    "deny" to "仅允许列表外"
)

/** 模式选项（含 WDS 组合；保存时拆为 uci 的 mode + wds=1；Mesh 仅在固件支持时出现，同 LuCI）。 */
private val MODE_OPTIONS = listOf(
    "ap" to "AP 接入点",
    "sta" to "客户端 (STA)",
    "adhoc" to "Ad-Hoc",
    "mesh" to "802.11s (Mesh)",
    "ap-wds" to "AP 接入点 (WDS)",
    "sta-wds" to "客户端 (WDS)"
)

private fun modeOptions(meshAvailable: Boolean): List<Pair<String, String>> =
    MODE_OPTIONS.filter { meshAvailable || it.first != "mesh" }

private fun modeLabel(value: String): String =
    MODE_OPTIONS.firstOrNull { it.first == value }?.second ?: value

private fun bandLabel(band: String?): String = when (band) {
    "5g" -> "5 GHz"
    "2g" -> "2.4 GHz"
    "6g" -> "6 GHz"
    else -> band ?: ""
}

private fun encryptionLabel(value: String?, live: String? = null): String =
    live ?: (securityOptions(null).firstOrNull { it.first == value }?.second ?: (value ?: "未设置"))

/**
 * WiFi 分享二维码内容（安卓/iOS 相机通用格式）：
 * `WIFI:T:<认证>;S:<SSID>;P:<密码>;H:<隐藏>;;`。WPA 涵盖 WPA/WPA2/WPA3 个人网络；
 * 特殊字符（\ ; , : " '）需转义，开放网络用 nopass 且不携带 P 字段。
 */
private fun wifiQrContent(
    ssid: String,
    password: String?,
    encryption: String?,
    hidden: Boolean
): String {
    val esc = { raw: String -> raw.replace(Regex("([\\\\;,:\"'])"), "\\\\$1") }
    val auth = if (encryption == "none") "nopass" else "WPA"
    return buildString {
        append("WIFI:T:$auth;S:${esc(ssid)};")
        if (auth != "nopass") append("P:${esc(password.orEmpty())};")
        if (hidden) append("H:true;")
        append(";")
    }
}

/** 生成二维码位图：白底黑码 + 1 模块静区，保证深色模式下也能被相机识别。 */
private fun wifiQrBitmap(content: String): Bitmap? = runCatching {
    val size = 720
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8"
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] =
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}.getOrNull()

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

/**
 * 频宽选项：按 LuCI CBIWifiFrequencyValue 的规则——每个工作频率只列该协议的频宽，
 * 并按 iwinfo 实测支持的 htmodes 过滤，标签为纯 MHz（如 "80 MHz"），首项 "-" 表示仅 Legacy。
 */
private fun htmodeOptions(hwmode: String?, availableHtmodes: List<String>): List<Pair<String, String>> {
    val entries = when (hwmode) {
        "n" -> listOf("HT20" to "20 MHz", "HT40" to "40 MHz")
        "ac" -> listOf("VHT20" to "20 MHz", "VHT40" to "40 MHz", "VHT80" to "80 MHz", "VHT160" to "160 MHz")
        "ax" -> listOf("HE20" to "20 MHz", "HE40" to "40 MHz", "HE80" to "80 MHz", "HE160" to "160 MHz")
        "be" -> listOf("EHT20" to "20 MHz", "EHT40" to "40 MHz", "EHT80" to "80 MHz", "EHT160" to "160 MHz", "EHT320" to "320 MHz")
        else -> emptyList()
    }
    return listOf("" to "-") + entries.filter { it.first in availableHtmodes }
}

private fun htmodeLabel(htmode: String?): String = when {
    htmode.isNullOrEmpty() -> "-"
    htmode.startsWith("EHT") -> "${htmode.substring(3)} MHz"
    htmode.startsWith("HE") -> "${htmode.substring(2)} MHz"
    htmode.startsWith("VHT") -> "${htmode.substring(3)} MHz"
    htmode.startsWith("HT") -> "${htmode.substring(2)} MHz"
    else -> htmode
}

/**
 * 工作频率（hwmode）选项：按 iwinfo 实测的 hwmodes 与 hostapd 特性裁剪（同 LuCI）。
 * uci 的 hwmode 值与 LuCI 一致为 ''/'n'/'ac'/'ax'/'be'。
 */
private fun hwmodeOptions(availableHwmodes: List<String>, features: LuciFeatures?): List<Pair<String, String>> {
    val opts = mutableListOf<Pair<String, String>>()
    if (availableHwmodes.any { it == "a" || it == "b" || it == "g" }) opts.add("" to "Legacy")
    if ("n" in availableHwmodes) opts.add("n" to "N")
    if ("ac" in availableHwmodes && features?.hostapd11ac != false) opts.add("ac" to "AC")
    if ("ax" in availableHwmodes && features?.hostapd11ax != false) opts.add("ax" to "AX")
    if ("be" in availableHwmodes && features?.hostapd11be == true) opts.add("be" to "BE")
    return opts
}

/** 显示用工作频率：uci 未写 hwmode 时按 htmode 前缀推导（同 LuCI 的行为）。 */
private fun effectiveHwmode(hwmode: String?, htmode: String?): String =
    hwmode?.takeIf { it.isNotBlank() } ?: when {
        htmode?.startsWith("HE") == true -> "ax"
        htmode?.startsWith("VHT") == true -> "ac"
        htmode?.startsWith("EHT") == true -> "be"
        htmode?.startsWith("HT") == true -> "n"
        else -> ""
    }

private fun hwmodeDisplay(hwmode: String?, htmode: String?): String = when (effectiveHwmode(hwmode, htmode)) {
    "n" -> "N"
    "ac" -> "AC"
    "ax" -> "AX"
    "be" -> "BE"
    else -> "Legacy"
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
@OptIn(ExperimentalMaterial3Api::class)
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
    val isLandscape =
        androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
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
    var ifaceMacfilter by remember { mutableStateOf("") }
    var ifaceMaclist by remember { mutableStateOf("") }
    var ifaceBssid by remember { mutableStateOf("") }
    var ifaceShortPreamble by remember { mutableStateOf(true) }
    var ifaceSectionTab by remember { mutableStateOf("general") }

    // 添加/编辑 WiFi 共用的弹窗状态：两级页签（网卡/接口）+ LuCI 全部选项。
    var dlgGroup by remember { mutableStateOf("iface") }
    var dlgDeviceTab by remember { mutableStateOf("general") }
    var dlgKeyError by remember { mutableStateOf<String?>(null) }
    var networkOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var features by remember { mutableStateOf<LuciFeatures?>(null) }
    var txPowerChoices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var countryChoices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var channelChoices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var networkPickerOpen by remember { mutableStateOf(false) }
    var networkPicked by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 接口表单：常规（mesh）
    var dlgMeshId by remember { mutableStateOf("") }
    var dlgMeshFwding by remember { mutableStateOf(true) }
    var dlgMeshRssi by remember { mutableStateOf("") }
    // 接口表单：无线安全
    var dlgPmf by remember { mutableStateOf("0") }
    var dlgPmfMaxTimeout by remember { mutableStateOf("") }
    var dlgPmfRetryTimeout by remember { mutableStateOf("") }
    var dlgKrack by remember { mutableStateOf(false) }
    var dlgWps by remember { mutableStateOf(false) }
    var dlgAuthServer by remember { mutableStateOf("") }
    var dlgAuthPort by remember { mutableStateOf("") }
    var dlgAuthSecret by remember { mutableStateOf("") }
    var dlgDynamicVlan by remember { mutableStateOf("") }
    var dlgPerStaVif by remember { mutableStateOf(false) }
    // 接口表单：高级
    var dlgMulticastToUnicast by remember { mutableStateOf(false) }
    var dlgBridgeIsolate by remember { mutableStateOf(false) }
    var dlgIfname by remember { mutableStateOf("") }
    var dlgMacaddr by remember { mutableStateOf("") }
    var dlgGroupRekey by remember { mutableStateOf("") }
    var dlgSkipInactivity by remember { mutableStateOf(false) }
    var dlgMaxInactivity by remember { mutableStateOf("") }
    var dlgMaxListenInterval by remember { mutableStateOf("") }
    var dlgDisassocLowAck by remember { mutableStateOf(true) }
    // 接口表单：WLAN 漫游（802.11r/k/v）
    var dlgIeee80211r by remember { mutableStateOf(false) }
    var dlgNasid by remember { mutableStateOf("") }
    var dlgMobilityDomain by remember { mutableStateOf("") }
    var dlgReassocDeadline by remember { mutableStateOf("") }
    var dlgFtOverDs by remember { mutableStateOf("") }
    var dlgFtPskLocal by remember { mutableStateOf(true) }
    var dlgR0Lifetime by remember { mutableStateOf("") }
    var dlgR1KeyHolder by remember { mutableStateOf("") }
    var dlgPmkR1Push by remember { mutableStateOf(false) }
    var dlgR0kh by remember { mutableStateOf("") }
    var dlgR1kh by remember { mutableStateOf("") }
    var dlgIeee80211k by remember { mutableStateOf(false) }
    var dlgRrmNeighbor by remember { mutableStateOf(false) }
    var dlgRrmBeacon by remember { mutableStateOf(false) }
    var dlgTimeAdv by remember { mutableStateOf("") }
    var dlgTimeZone by remember { mutableStateOf("") }
    var dlgWnm by remember { mutableStateOf(false) }
    var dlgWnmNoKeys by remember { mutableStateOf(false) }
    var dlgBssTransition by remember { mutableStateOf(false) }
    var dlgProxyArp by remember { mutableStateOf(false) }

    // 设备表单（网卡页签，typed 字段沿用 radio*；高级项为 uci extra）
    var radioCellDensity by remember { mutableStateOf("") }
    var radioDistance by remember { mutableStateOf("") }
    var radioFrag by remember { mutableStateOf("") }
    var radioRts by remember { mutableStateOf("") }
    var radioBeaconInt by remember { mutableStateOf("") }
    var radioDtimPeriod by remember { mutableStateOf("") }
    var radioNoscan by remember { mutableStateOf(false) }
    var radioVendorVht by remember { mutableStateOf(false) }
    var radioLegacyRates by remember { mutableStateOf(false) }
    var radioRxldpc by remember { mutableStateOf(true) }
    var radioLdpc by remember { mutableStateOf(true) }

    var addWifiFor by remember { mutableStateOf<String?>(null) }

    var deleteIfaceFor by remember { mutableStateOf<String?>(null) }
    var shareIfaceFor by remember { mutableStateOf<WirelessIface?>(null) }

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

    LaunchedEffect(Unit) {
        load()
        features = client.features(config)
    }

    fun updateRadio(section: String, transform: (WirelessRadio) -> WirelessRadio) {
        radios = radios.orEmpty().map { if (it.section == section) transform(it) else it }
    }

    fun updateIface(section: String, transform: (WirelessIface) -> WirelessIface) {
        radios = radios.orEmpty().map { radio ->
            radio.copy(ifaces = radio.ifaces.map { if (it.section == section) transform(it) else it })
        }
    }

    // ---- 添加/编辑 WiFi 弹窗：表单填充与 uci 值构建 ----

    /** 用网卡现有配置填充设备页签。 */
    fun fillDeviceForm(radio: WirelessRadio) {
        radioChannel = radio.channel ?: "auto"
        radioHtmode = radio.htmode ?: ""
        radioTxpower = radio.txpower ?: ""
        radioCountry = radio.country ?: ""
        // uci hwmode 与 LuCI 一致使用 ''/n/ac/ax/be；兼容旧式 11ax 写法。
        radioHwmode = radio.hwmode?.removePrefix("11")?.takeIf { it.isNotBlank() } ?: ""
        radioCellDensity = radio.extra["cell_density"] ?: "0"
        radioDistance = radio.extra["distance"] ?: ""
        radioFrag = radio.extra["frag"] ?: ""
        radioRts = radio.extra["rts"] ?: ""
        radioBeaconInt = radio.extra["beacon_int"] ?: ""
        radioDtimPeriod = radio.extra["dtim_period"] ?: ""
        radioNoscan = radio.extra["noscan"] == "1"
        radioVendorVht = radio.extra["vendor_vht"] == "1"
        radioLegacyRates = radio.extra["legacy_rates"] == "1"
        radioRxldpc = radio.extra["rxldpc"] != "0"
        radioLdpc = radio.extra["ldpc"] != "0"
    }

    /** 打开弹窗时拉取网络列表（uci get network），供「网络」选择器。 */
    fun loadNetworkOptions() {
        scope.launch {
            networkOptions = runCatching {
                withContext(Dispatchers.IO) { client.listNetworks(config) }
            }.getOrDefault(emptyList())
        }
    }

    /** 用接口现有配置填充接口页签；null 时填 LuCI 默认值（添加模式）。 */
    fun fillIfaceForm(iface: WirelessIface?) {
        if (iface == null) {
            ifaceMode = "ap"
            ifaceSsid = ""
            ifaceNetwork = "lan"
            ifaceEncryption = "psk2"
            ifaceCipher = "auto"
            ifaceKey = ""
            ifaceHidden = false
            ifaceWmm = true
            ifaceIsolate = false
            ifaceMacfilter = ""
            ifaceMaclist = ""
            ifaceBssid = ""
            ifaceShortPreamble = true
            dlgMeshId = ""
            dlgMeshFwding = true
            dlgMeshRssi = ""
            dlgPmf = "0"
            dlgPmfMaxTimeout = ""
            dlgPmfRetryTimeout = ""
            dlgKrack = false
            dlgWps = false
            dlgAuthServer = ""
            dlgAuthPort = ""
            dlgAuthSecret = ""
            dlgDynamicVlan = ""
            dlgPerStaVif = false
            dlgMulticastToUnicast = false
            dlgBridgeIsolate = false
            dlgIfname = ""
            dlgMacaddr = ""
            dlgGroupRekey = ""
            dlgSkipInactivity = false
            dlgMaxInactivity = ""
            dlgMaxListenInterval = ""
            dlgDisassocLowAck = true
            dlgIeee80211r = false
            dlgNasid = ""
            dlgMobilityDomain = ""
            dlgReassocDeadline = ""
            dlgFtOverDs = ""
            dlgFtPskLocal = true
            dlgR0Lifetime = ""
            dlgR1KeyHolder = ""
            dlgPmkR1Push = false
            dlgR0kh = ""
            dlgR1kh = ""
            dlgIeee80211k = false
            dlgRrmNeighbor = false
            dlgRrmBeacon = false
            dlgTimeAdv = ""
            dlgTimeZone = ""
            dlgWnm = false
            dlgWnmNoKeys = false
            dlgBssTransition = false
            dlgProxyArp = false
        } else {
            val wds = iface.extra["wds"] == "1"
            ifaceMode = when (iface.mode) {
                "ap" -> if (wds) "ap-wds" else "ap"
                "sta" -> if (wds) "sta-wds" else "sta"
                else -> iface.mode ?: "ap"
            }
            ifaceSsid = iface.ssid
            ifaceNetwork = iface.network ?: "lan"
            // uci 的 encryption 可能形如 psk2+ccmp：基础方式与算法拆开显示。
            ifaceEncryption = iface.encryption?.substringBefore('+') ?: "none"
            ifaceCipher = Regex("\\+([a-z0-9+]+)$").find(iface.encryption.orEmpty())
                ?.groupValues?.get(1)?.takeIf { it != "aes" } ?: "auto"
            ifaceKey = iface.key ?: ""
            ifaceHidden = iface.hidden
            ifaceWmm = iface.wmm
            ifaceIsolate = iface.isolate
            ifaceMacfilter = iface.macfilter ?: ""
            ifaceMaclist = iface.maclist.joinToString("\n")
            ifaceBssid = iface.bssid ?: ""
            ifaceShortPreamble = iface.shortPreamble
            dlgMeshId = iface.extra["mesh_id"] ?: ""
            dlgMeshFwding = iface.extra["mesh_fwding"] != "0"
            dlgMeshRssi = iface.extra["mesh_rssi_threshold"] ?: ""
            dlgPmf = iface.extra["ieee80211w"] ?: "0"
            dlgPmfMaxTimeout = iface.extra["ieee80211w_max_timeout"] ?: ""
            dlgPmfRetryTimeout = iface.extra["ieee80211w_retry_timeout"] ?: ""
            dlgKrack = iface.extra["wpa_disable_eapol_key_retries"] == "1"
            dlgWps = iface.extra["wps_pushbutton"] == "1"
            dlgAuthServer = iface.extra["auth_server"] ?: ""
            dlgAuthPort = iface.extra["auth_port"] ?: ""
            dlgAuthSecret = iface.extra["auth_secret"] ?: ""
            dlgDynamicVlan = iface.extra["dynamic_vlan"] ?: ""
            dlgPerStaVif = iface.extra["per_sta_vif"] == "1"
            dlgMulticastToUnicast = iface.extra["multicast_to_unicast_all"] == "1"
            dlgBridgeIsolate = iface.extra["bridge_isolate"] == "1"
            dlgIfname = iface.extra["ifname"] ?: ""
            dlgMacaddr = iface.extra["macaddr"] ?: ""
            dlgGroupRekey = iface.extra["wpa_group_rekey"] ?: ""
            dlgSkipInactivity = iface.extra["skip_inactivity_poll"] == "1"
            dlgMaxInactivity = iface.extra["max_inactivity"] ?: ""
            dlgMaxListenInterval = iface.extra["max_listen_interval"] ?: ""
            dlgDisassocLowAck = iface.extra["disassoc_low_ack"] != "0"
            dlgIeee80211r = iface.extra["ieee80211r"] == "1"
            dlgNasid = iface.extra["nasid"] ?: ""
            dlgMobilityDomain = iface.extra["mobility_domain"] ?: ""
            dlgReassocDeadline = iface.extra["reassociation_deadline"] ?: ""
            dlgFtOverDs = iface.extra["ft_over_ds"] ?: ""
            dlgFtPskLocal = iface.extra["ft_psk_generate_local"] != "0"
            dlgR0Lifetime = iface.extra["r0_key_lifetime"] ?: ""
            dlgR1KeyHolder = iface.extra["r1_key_holder"] ?: ""
            dlgPmkR1Push = iface.extra["pmk_r1_push"] == "1"
            dlgR0kh = iface.extra["r0kh"] ?: ""
            dlgR1kh = iface.extra["r1kh"] ?: ""
            dlgIeee80211k = iface.extra["ieee80211k"] == "1"
            dlgRrmNeighbor = iface.extra["rrm_neighbor_report"] != "0"
            dlgRrmBeacon = iface.extra["rrm_beacon_report"] != "0"
            dlgTimeAdv = iface.extra["time_advertisement"] ?: ""
            dlgTimeZone = iface.extra["time_zone"] ?: ""
            dlgWnm = iface.extra["wnm_sleep_mode"] == "1"
            dlgWnmNoKeys = iface.extra["wnm_sleep_mode_no_keys"] == "1"
            dlgBssTransition = iface.extra["bss_transition"] == "1"
            dlgProxyArp = iface.extra["proxy_arp"] == "1"
        }
        dlgKeyError = null
        dlgGroup = "iface"
        dlgDeviceTab = "general"
        ifaceSectionTab = "general"
    }

    /** 打开弹窗时拉取网络列表、该网卡的信道表/功率表/国家表（LuCI 同款数据源）。 */
    fun loadDialogData(radioSection: String?) {
        loadNetworkOptions()
        if (radioSection == null) return
        channelChoices = emptyList()
        txPowerChoices = emptyList()
        countryChoices = emptyList()
        scope.launch {
            val fr = runCatching {
                withContext(Dispatchers.IO) { client.freqList(config, radioSection) }
            }.getOrDefault(emptyList())
            if (fr.isNotEmpty()) {
                channelChoices = listOf("auto" to "auto") + fr.map { (ch, mhz, dfs) ->
                    "$ch" to "$ch ($mhz MHz)" + if (dfs) "（DFS）" else ""
                }
            }
            val tx = runCatching {
                withContext(Dispatchers.IO) { client.txPowerList(config, radioSection) }
            }.getOrDefault(emptyList())
            if (tx.isNotEmpty()) txPowerChoices = tx.map { p -> p.toString() to "$p dBm" }
            val co = runCatching {
                withContext(Dispatchers.IO) { client.countryList(config, radioSection) }
            }.getOrDefault(emptyList())
            if (co.isNotEmpty()) countryChoices = co
        }
    }

    /** 模式选择值（ap-wds/sta-wds）拆解为 uci 的 mode + wds。 */
    fun dlgRealMode(): String = ifaceMode.removeSuffix("-wds")

    /** 接口表单的 uci 选项（extra），空串表示删除该选项。 */
    fun ifaceExtra(): Map<String, String> {
        val m = mutableMapOf<String, String>()
        val mode = dlgRealMode()
        val eap = ifaceEncryption in EAP_ENCRYPTIONS
        m["wds"] = if (ifaceMode.endsWith("-wds")) "1" else ""
        m["mesh_id"] = if (mode == "mesh") dlgMeshId.trim() else ""
        m["mesh_fwding"] = if (mode == "mesh" && dlgMeshFwding) "1" else "0"
        m["mesh_rssi_threshold"] = if (mode == "mesh") dlgMeshRssi.trim() else ""
        m["ieee80211w"] = dlgPmf
        m["ieee80211w_max_timeout"] = if (dlgPmf == "1" || dlgPmf == "2") dlgPmfMaxTimeout.trim() else ""
        m["ieee80211w_retry_timeout"] = if (dlgPmf == "1" || dlgPmf == "2") dlgPmfRetryTimeout.trim() else ""
        m["wpa_disable_eapol_key_retries"] = if (dlgKrack) "1" else "0"
        m["wps_pushbutton"] = if (dlgWps) "1" else "0"
        m["auth_server"] = if (eap) dlgAuthServer.trim() else ""
        m["auth_port"] = if (eap) dlgAuthPort.trim() else ""
        m["auth_secret"] = if (eap) dlgAuthSecret.trim() else ""
        m["dynamic_vlan"] = if (eap) dlgDynamicVlan else ""
        m["per_sta_vif"] = if (eap && dlgPerStaVif) "1" else ""
        m["multicast_to_unicast_all"] = if (dlgMulticastToUnicast) "1" else "0"
        m["bridge_isolate"] = if (dlgBridgeIsolate) "1" else "0"
        m["ifname"] = dlgIfname.trim()
        m["macaddr"] = dlgMacaddr.trim()
        m["wpa_group_rekey"] = dlgGroupRekey.trim()
        m["skip_inactivity_poll"] = if (dlgSkipInactivity) "1" else "0"
        m["max_inactivity"] = dlgMaxInactivity.trim()
        m["max_listen_interval"] = dlgMaxListenInterval.trim()
        m["disassoc_low_ack"] = if (dlgDisassocLowAck) "1" else "0"
        m["ieee80211r"] = if (dlgIeee80211r) "1" else "0"
        m["nasid"] = if (dlgIeee80211r) dlgNasid.trim() else ""
        m["mobility_domain"] = if (dlgIeee80211r) dlgMobilityDomain.trim() else ""
        m["reassociation_deadline"] = if (dlgIeee80211r) dlgReassocDeadline.trim() else ""
        m["ft_over_ds"] = if (dlgIeee80211r) dlgFtOverDs else ""
        m["ft_psk_generate_local"] = if (dlgIeee80211r) (if (dlgFtPskLocal) "1" else "0") else ""
        m["r0_key_lifetime"] = if (dlgIeee80211r) dlgR0Lifetime.trim() else ""
        m["r1_key_holder"] = if (dlgIeee80211r) dlgR1KeyHolder.trim() else ""
        m["pmk_r1_push"] = if (dlgIeee80211r && dlgPmkR1Push) "1" else ""
        m["r0kh"] = if (dlgIeee80211r) dlgR0kh.trim() else ""
        m["r1kh"] = if (dlgIeee80211r) dlgR1kh.trim() else ""
        m["ieee80211k"] = if (dlgIeee80211k) "1" else "0"
        m["rrm_neighbor_report"] = if (dlgIeee80211k && dlgRrmNeighbor) "1" else ""
        m["rrm_beacon_report"] = if (dlgIeee80211k && dlgRrmBeacon) "1" else ""
        m["time_advertisement"] = dlgTimeAdv
        m["time_zone"] = dlgTimeZone.trim()
        m["wnm_sleep_mode"] = if (dlgWnm) "1" else "0"
        m["wnm_sleep_mode_no_keys"] = if (dlgWnmNoKeys) "1" else "0"
        m["bss_transition"] = if (dlgBssTransition) "1" else "0"
        m["proxy_arp"] = if (dlgProxyArp) "1" else "0"
        return m
    }

    /** uci 的 encryption 选项值：算法非 auto 时以 `加密+算法` 合并写入（LuCI 同款）。 */
    fun dlgEncValue(): String =
        if (ifaceCipher != "auto" && ifaceEncryption in CIPHER_ENCRYPTIONS) {
            "$ifaceEncryption+$ifaceCipher"
        } else {
            ifaceEncryption
        }

    /** 添加 WiFi 时 `uci add` 的 values（与表单一致；空值跳过）。 */
    fun addIfaceValues(): Map<String, Any> {
        val mode = dlgRealMode()
        val values = mutableMapOf<String, Any>()
        values["mode"] = mode
        if (mode == "mesh") values["mesh_id"] = dlgMeshId.trim() else values["ssid"] = ifaceSsid.trim()
        val nets = ifaceNetwork.trim().split(Regex("[,，\\s]+")).filter { it.isNotBlank() }
        if (nets.size == 1) values["network"] = nets[0] else if (nets.isNotEmpty()) values["network"] = nets
        values["encryption"] = dlgEncValue()
        if (ifaceEncryption in PSK_ENCRYPTIONS && ifaceKey.isNotEmpty()) values["key"] = ifaceKey
        if (ifaceHidden) values["hidden"] = "1"
        if (!ifaceWmm) values["wmm"] = "0"
        if (ifaceIsolate) values["isolate"] = "1"
        if (ifaceMacfilter == "allow" || ifaceMacfilter == "deny") values["macfilter"] = ifaceMacfilter
        val macs = ifaceMaclist.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (macs.isNotEmpty()) values["maclist"] = macs
        if (ifaceBssid.isNotBlank()) values["bssid"] = ifaceBssid.trim()
        if (!ifaceShortPreamble) values["short_preamble"] = "0"
        ifaceExtra().forEach { (k, v) ->
            when {
                v.isEmpty() -> {}
                v == "1" && k in WirelessClient.DEFAULT_ON_FLAGS -> {}
                v == "0" && k !in WirelessClient.DEFAULT_ON_FLAGS -> {}
                v.contains('\n') -> values[k] = v.split('\n').filter { it.isNotBlank() }
                else -> values[k] = v
            }
        }
        return values
    }

    /** 设备表单的 uci 选项（extra），空串表示删除该选项。 */
    fun radioExtra(): Map<String, String> = mapOf(
        "cell_density" to radioCellDensity,
        "distance" to radioDistance.trim(),
        "frag" to radioFrag.trim(),
        "rts" to radioRts.trim(),
        "beacon_int" to radioBeaconInt.trim(),
        "dtim_period" to radioDtimPeriod.trim(),
        "noscan" to if (radioNoscan) "1" else "0",
        "vendor_vht" to if (radioVendorVht) "1" else "0",
        "legacy_rates" to if (radioLegacyRates) "1" else "0",
        "rxldpc" to if (radioRxldpc) "1" else "0",
        "ldpc" to if (radioLdpc) "1" else "0"
    )

    /**
     * extra 选项 diff：值变化才写；空串表示删除该选项。原配置没有该选项且新值等于
     * 固件默认时跳过写入（默认开启的开关见 WirelessClient.DEFAULT_ON_FLAGS——它们写 "1"
     * 冗余、关 "0" 必须写；其余开关与选项写 "0" 冗余），保持 uci 配置干净。
     */
    fun diffExtra(section: String, put: (String, String, Any) -> Unit, oldExtra: Map<String, String>, newExtra: Map<String, String>) {
        for (key in (newExtra.keys + oldExtra.keys)) {
            val nv = newExtra[key] ?: ""
            val ov = oldExtra[key] ?: ""
            if (nv == ov) continue
            if (ov.isEmpty() && nv == if (key in WirelessClient.DEFAULT_ON_FLAGS) "1" else "0") continue
            put(section, key, nv)
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
            diffExtra(radio.section, { s, k, v -> put(s, k, v) }, old?.extra ?: emptyMap(), radio.extra)
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
                if (iface.macfilter != o?.macfilter) {
                    put(iface.section, "macfilter", iface.macfilter ?: "")
                }
                if (iface.maclist != (o?.maclist ?: emptyList<String>())) {
                    put(iface.section, "maclist", iface.maclist)
                }
                if (iface.disabled != (o?.disabled ?: false)) {
                    put(iface.section, "disabled", if (iface.disabled) "1" else "0")
                }
                diffExtra(iface.section, { s, k, v -> put(s, k, v) }, o?.extra ?: emptyMap(), iface.extra)
            }
        }
        return changes
    }

    val changes = buildChanges()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
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

        // 无线配置的读写与应用全部走路由器 ubus（uci/iwinfo/luci-rpc），无需 SSH；
        // 仅网卡「重启」按钮使用 SSH，未配置 SSH 时该按钮禁用。
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
                    AppCard(contentPadding = 14.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Router,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                radio.section,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Spacer(Modifier.weight(1f))
                            SmallSwitch(
                                checked = !radio.disabled,
                                onCheckedChange = { on ->
                                    updateRadio(radio.section) { it.copy(disabled = !on) }
                                }
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            StatCell(
                                "信道",
                                radio.liveChannel?.toString() ?: radio.channel ?: "auto",
                                Modifier.weight(1f)
                            )
                            StatCell(
                                "频段",
                                bandLabel(radio.band).ifEmpty { radio.liveHwmodesText ?: "-" },
                                Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            StatCell(
                                "发射功率",
                                radio.liveTxpower?.let { "$it dBm" } ?: "-",
                                Modifier.weight(1f)
                            )
                            StatCell(
                                "速率",
                                radio.liveRateMbits?.let { "%.1f Mbit/s".format(java.util.Locale.US, it) } ?: "-",
                                Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
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
                                enabled = !busy && sshEnabled,
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
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
                                enabled = !busy,
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) { Text("扫描") }
                            TextButton(
                                onClick = {
                                    fillIfaceForm(null)
                                    fillDeviceForm(radio)
                                    loadDialogData(radio.section)
                                    addWifiFor = radio.section
                                },
                                enabled = !busy,
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) { Text("添加 WiFi") }
                        }
                    }
                }

                // —— WiFi（接口，LuCI 信息形态）——没有接口时不显示该分区（不提示） ——
                val allIfaces = radios.orEmpty().flatMap { it.ifaces }
                if (allIfaces.isNotEmpty()) {
                    Text(
                        "WiFi",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                allIfaces.forEach { iface ->
                    AppCard(contentPadding = 14.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Wifi,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                iface.ssid.ifEmpty { "（未设置 SSID）" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            SmallSwitch(
                                checked = !iface.disabled,
                                onCheckedChange = { on ->
                                    updateIface(iface.section) { it.copy(disabled = !on) }
                                }
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            StatCell(
                                "加密",
                                iface.liveEncryption ?: encryptionLabel(iface.encryption),
                                Modifier.weight(1.4f)
                            )
                            StatCell(
                                "信号",
                                iface.liveSignal?.let { "$it dBm" } ?: "-",
                                Modifier.weight(1f)
                            )
                            StatCell(
                                "客户端",
                                iface.clientCount?.toString() ?: "-",
                                Modifier.weight(1f)
                            )
                        }
                        StatCell(
                            "BSSID",
                            iface.liveBssid ?: iface.bssid ?: "-",
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            // 圆形二维码分享按钮（紧贴「删除」左侧），背景透明。
                            Surface(
                                onClick = { shareIfaceFor = iface },
                                enabled = !busy,
                                shape = CircleShape,
                                color = Color.Transparent,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Outlined.QrCode2,
                                        contentDescription = "分享二维码",
                                        tint = colors.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                            TextButton(
                                onClick = { deleteIfaceFor = iface.section },
                                enabled = !busy,
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) { Text("删除", color = colors.error) }
                            Button(
                                onClick = {
                                    fillIfaceForm(iface)
                                    radios.orEmpty().firstOrNull { it.section == iface.device }
                                        ?.let { fillDeviceForm(it) }
                                    loadDialogData(iface.device)
                                    editIfaceFor = iface.section
                                },
                                enabled = !busy,
                                shape = AppShapes.pill,
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp)
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

        // —— 横屏：WiFi 分享二维码从右侧滑出面板（竖屏为底部弹窗） ——
        if (isLandscape) {
            shareIfaceFor?.let { shareIface ->
                WifiShareSidePanel(shareIface, onDismiss = { shareIfaceFor = null })
            }
        }
    }

    // ---- 添加/编辑 WiFi 对话框（对齐 LuCI：设备配置[常规/高级] + 接口配置[常规/无线安全/MAC 过滤/高级/WLAN 漫游]） ----
    val editingSection = editIfaceFor
    val addingDevice = addWifiFor
    if (editingSection != null || addingDevice != null) {
        val editing = editingSection != null
        val dialogRadio = radios.orEmpty().firstOrNull { r -> r.section == (addingDevice
            ?: radios.orEmpty().firstOrNull { r2 -> r2.ifaces.any { it.section == editingSection } }?.section) }
        val dialogRadioSection = dialogRadio?.section
        val dialogBand = dialogRadio?.band
        val dialogHtmodes = dialogRadio?.availableHtmodes ?: emptyList()
        val dialogHwmodes = dialogRadio?.availableHwmodes ?: emptyList()
        AppDialog(
            title = if (editing) "编辑 $editingSection" else "添加 WiFi（$addingDevice）",
            confirmLabel = if (editing) "保存" else "添加",
            confirmEnabled = when {
                editing -> true
                dlgRealMode() == "mesh" -> dlgMeshId.isNotBlank()
                else -> ifaceSsid.isNotBlank()
            },
            onConfirm = {
                // 密码校验：PSK/SAE 家族必须 ≥8 位（WPA 规范），不通过则红字提示且不关闭弹窗。
                if (ifaceEncryption in PSK_ENCRYPTIONS && ifaceKey.length < 8) {
                    dlgKeyError = "WiFi 密码至少 8 位（当前 ${ifaceKey.length} 位）"
                    return@AppDialog
                }
                dlgKeyError = null
                val encValue = dlgEncValue()
                val iface = editingSection?.let { sec ->
                    radios.orEmpty().flatMap { it.ifaces }.firstOrNull { it.section == sec }
                }
                val radioSection = addingDevice ?: iface?.device
                if (radioSection == null) return@AppDialog
                if (iface != null) {
                    updateIface(iface.section) {
                        it.copy(
                            mode = dlgRealMode(),
                            ssid = ifaceSsid.trim(),
                            // uci 的 network 是空格分隔的列表值；选择器里用逗号展示，这里转回。
                            network = ifaceNetwork.split(Regex("[,，\\s]+"))
                                .filter { it.isNotBlank() }.joinToString(" ").ifBlank { "lan" },
                            encryption = encValue,
                            key = if (ifaceEncryption in PSK_ENCRYPTIONS) ifaceKey else null,
                            hidden = ifaceHidden,
                            wmm = ifaceWmm,
                            isolate = ifaceIsolate,
                            macfilter = ifaceMacfilter.ifBlank { null },
                            maclist = ifaceMaclist.lines().map { m -> m.trim() }.filter { m -> m.isNotEmpty() },
                            bssid = ifaceBssid.trim(),
                            shortPreamble = ifaceShortPreamble,
                            extra = ifaceExtra()
                        )
                    }
                }
                updateRadio(radioSection) {
                    it.copy(
                        channel = radioChannel.trim(),
                        htmode = radioHtmode.trim(),
                        txpower = radioTxpower.trim(),
                        country = radioCountry.trim(),
                        hwmode = radioHwmode.trim(),
                        extra = radioExtra()
                    )
                }
                editIfaceFor = null
                addWifiFor = null
                scope.launch {
                    busy = true
                    message = null
                    try {
                        if (addingDevice != null) {
                            withContext(Dispatchers.IO) { client.addIface(config, addingDevice, addIfaceValues()) }
                        }
                        withContext(Dispatchers.IO) {
                            client.apply(config, buildChanges(), if (sshEnabled) ssh else null) { phase -> setMsg(phase, false) }
                        }
                        setMsg(if (addingDevice != null) "WiFi 已添加并重载无线。" else "已应用，Wi-Fi 正在重载。", false)
                        load()
                    } catch (e: Exception) {
                        setMsg(e.message ?: "操作失败。", true)
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = { if (!busy) { editIfaceFor = null; addWifiFor = null } }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SmoothOptionSwitcher(
                    options = listOf("device" to "网卡", "iface" to "接口"),
                    selected = dlgGroup,
                    onSelect = { dlgGroup = it },
                    modifier = Modifier.fillMaxWidth()
                )
                if (dlgGroup == "device") {
                    SmoothOptionSwitcher(
                        options = listOf("general" to "常规设置", "advanced" to "高级设置"),
                        selected = dlgDeviceTab,
                        onSelect = { dlgDeviceTab = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    SmoothOptionSwitcher(
                        options = listOf(
                            "general" to "常规设置",
                            "security" to "无线安全",
                            "macfilter" to "MAC 过滤",
                            "advanced" to "高级设置",
                            "roaming" to "WLAN 漫游"
                        ),
                        selected = ifaceSectionTab,
                        onSelect = { ifaceSectionTab = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // 分区内容固定高度 + 滚动：切换页签时窗口尺寸恒定。
                val sectionBodyHeight = minOf(
                    360.dp,
                    androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.44f
                )
                ThinScrollbarColumn(
                    modifier = Modifier.height(sectionBodyHeight),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when {
                        dlgGroup == "device" && dlgDeviceTab == "general" -> {
                            SelectRow("工作频率", hwmodeDisplay(radioHwmode, radioHtmode)) {
                                selectState = SelectState(
                                    "选择工作频率", hwmodeOptions(dialogHwmodes, features), radioHwmode
                                ) { v ->
                                    radioHwmode = v
                                    if (v.isBlank()) {
                                        radioHtmode = ""
                                    } else {
                                        // 切换协议后保持频宽数值，前缀跟随协议。
                                        val width = Regex("\\d+").find(radioHtmode)?.value ?: when (v) {
                                            "ac", "ax", "be" -> "80"
                                            else -> "20"
                                        }
                                        val prefix = when (v) {
                                            "n" -> "HT"
                                            "ac" -> "VHT"
                                            "ax" -> "HE"
                                            "be" -> "EHT"
                                            else -> "HT"
                                        }
                                        radioHtmode = "$prefix$width"
                                    }
                                }
                            }
                            SelectRow("频宽", htmodeLabel(radioHtmode)) {
                                selectState = SelectState(
                                    "选择频宽",
                                    htmodeOptions(effectiveHwmode(radioHwmode, radioHtmode), dialogHtmodes),
                                    radioHtmode
                                ) { v -> radioHtmode = v }
                            }
                            SelectRow("信道", radioChannel.ifBlank { "auto" }) {
                                selectState = SelectState(
                                    "选择信道",
                                    if (channelChoices.isNotEmpty()) channelChoices else channelOptions(dialogBand),
                                    radioChannel
                                ) { v -> radioChannel = v }
                            }
                            SelectRow("最大发射功率", radioTxpower.ifBlank { "驱动默认" }) {
                                selectState = SelectState(
                                    "选择最大发射功率",
                                    if (txPowerChoices.isNotEmpty()) listOf("" to "驱动默认") + txPowerChoices else txpowerOptions(),
                                    radioTxpower
                                ) { v -> radioTxpower = v }
                            }
                            SelectRow("国家代码", radioCountry.ifBlank { "驱动默认" }) {
                                selectState = SelectState(
                                    "选择国家代码",
                                    listOf("" to "驱动默认") + if (countryChoices.isNotEmpty()) countryChoices else COUNTRY_OPTIONS,
                                    radioCountry
                                ) { v -> radioCountry = v }
                            }
                            if (dialogBand == "2g") {
                                SettingRow("允许旧 802.11b 速率", radioLegacyRates) { radioLegacyRates = it }
                            }
                        }
                        dlgGroup == "device" -> {
                            SelectRow(
                                "覆盖密度",
                                when (radioCellDensity) {
                                    "1" -> "普通"
                                    "2" -> "高"
                                    "3" -> "很高"
                                    else -> "禁用"
                                }
                            ) {
                                selectState = SelectState(
                                    "选择覆盖密度",
                                    listOf("0" to "禁用", "1" to "普通", "2" to "高", "3" to "很高"),
                                    radioCellDensity
                                ) { v -> radioCellDensity = v }
                            }
                            OutlinedTextField(
                                value = radioDistance,
                                onValueChange = { radioDistance = it },
                                singleLine = true,
                                label = { Text("距离优化（米，留空=auto）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = radioFrag,
                                onValueChange = { radioFrag = it },
                                singleLine = true,
                                label = { Text("分片阈值（留空=关闭）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = radioRts,
                                onValueChange = { radioRts = it },
                                singleLine = true,
                                label = { Text("RTS/CTS 阈值（留空=关闭）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = radioBeaconInt,
                                onValueChange = { radioBeaconInt = it },
                                singleLine = true,
                                label = { Text("信标间隔（默认 100）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = radioDtimPeriod,
                                onValueChange = { radioDtimPeriod = it },
                                singleLine = true,
                                label = { Text("DTIM 间隔") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            SettingRow("强制 40MHz 模式", radioNoscan) { radioNoscan = it }
                            if (dialogBand == "2g") {
                                SettingRow("启用 256-QAM", radioVendorVht) { radioVendorVht = it }
                            }
                            SettingRow("Rx LDPC", radioRxldpc) { radioRxldpc = it }
                            SettingRow("Tx LDPC", radioLdpc) { radioLdpc = it }
                        }
                        ifaceSectionTab == "security" -> {
                            SelectRow(
                                "加密",
                                securityOptions(features).firstOrNull { it.first == ifaceEncryption }?.second
                                    ?: ifaceEncryption
                            ) {
                                selectState = SelectState(
                                    "选择加密方式", securityOptions(features), ifaceEncryption
                                ) { v -> ifaceEncryption = v }
                            }
                            if (ifaceEncryption in CIPHER_ENCRYPTIONS) {
                                SelectRow(
                                    "算法",
                                    CIPHER_OPTIONS.firstOrNull { it.first == ifaceCipher }?.second ?: "auto"
                                ) {
                                    selectState = SelectState(
                                        "选择算法", CIPHER_OPTIONS, ifaceCipher
                                    ) { v -> ifaceCipher = v }
                                }
                            }
                            if (ifaceEncryption in PSK_ENCRYPTIONS) {
                                OutlinedTextField(
                                    value = ifaceKey,
                                    onValueChange = {
                                        ifaceKey = it
                                        dlgKeyError = null
                                    },
                                    singleLine = true,
                                    label = { Text("密码（至少 8 位）") },
                                    isError = dlgKeyError != null,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (dlgKeyError != null) {
                                    Text(
                                        dlgKeyError.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.error
                                    )
                                }
                            }
                            SelectRow(
                                "管理帧保护 (802.11w)",
                                when (dlgPmf) {
                                    "1" -> "可选"
                                    "2" -> "必需"
                                    else -> "禁用"
                                }
                            ) {
                                selectState = SelectState(
                                    "选择管理帧保护",
                                    listOf("0" to "禁用", "1" to "可选", "2" to "必需"),
                                    dlgPmf
                                ) { v -> dlgPmf = v }
                            }
                            if (dlgPmf == "1" || dlgPmf == "2") {
                                OutlinedTextField(
                                    value = dlgPmfMaxTimeout,
                                    onValueChange = { dlgPmfMaxTimeout = it },
                                    singleLine = true,
                                    label = { Text("802.11w 最大超时（默认 1000）") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = dlgPmfRetryTimeout,
                                    onValueChange = { dlgPmfRetryTimeout = it },
                                    singleLine = true,
                                    label = { Text("802.11w 重试超时（默认 201）") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            SettingRow("KRACK 对策（禁用 EAPOL 密钥重传）", dlgKrack) { dlgKrack = it }
                            SettingRow("WPS 按钮模式", dlgWps) { dlgWps = it }
                            if (ifaceEncryption in EAP_ENCRYPTIONS) {
                                OutlinedTextField(
                                    value = dlgAuthServer,
                                    onValueChange = { dlgAuthServer = it },
                                    singleLine = true,
                                    label = { Text("RADIUS 认证服务器") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = dlgAuthPort,
                                    onValueChange = { dlgAuthPort = it },
                                    singleLine = true,
                                    label = { Text("RADIUS 认证端口") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = dlgAuthSecret,
                                    onValueChange = { dlgAuthSecret = it },
                                    singleLine = true,
                                    label = { Text("RADIUS 认证密钥") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                SelectRow(
                                    "RADIUS 动态 VLAN",
                                    when (dlgDynamicVlan) {
                                        "1" -> "可选"
                                        "2" -> "必需"
                                        else -> "禁用"
                                    }
                                ) {
                                    selectState = SelectState(
                                        "选择动态 VLAN",
                                        listOf("" to "禁用", "1" to "可选", "2" to "必需"),
                                        dlgDynamicVlan
                                    ) { v -> dlgDynamicVlan = v }
                                }
                                SettingRow("RADIUS 每 STA VLAN", dlgPerStaVif) { dlgPerStaVif = it }
                            }
                        }
                        ifaceSectionTab == "macfilter" -> {
                            SelectRow(
                                "MAC 地址过滤",
                                MACFILTER_OPTIONS.firstOrNull { it.first == ifaceMacfilter }?.second ?: "已禁用"
                            ) {
                                selectState = SelectState(
                                    "选择 MAC 过滤", MACFILTER_OPTIONS, ifaceMacfilter
                                ) { v -> ifaceMacfilter = v }
                            }
                            if (ifaceMacfilter == "allow" || ifaceMacfilter == "deny") {
                                OutlinedTextField(
                                    value = ifaceMaclist,
                                    onValueChange = { ifaceMaclist = it },
                                    label = { Text("MAC 列表（每行一个）") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        ifaceSectionTab == "roaming" -> {
                            SettingRow("802.11r 快速切换", dlgIeee80211r) { dlgIeee80211r = it }
                            if (dlgIeee80211r) {
                                OutlinedTextField(
                                    value = dlgNasid,
                                    onValueChange = { dlgNasid = it },
                                    singleLine = true,
                                    label = { Text("NAS ID") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = dlgMobilityDomain,
                                    onValueChange = { dlgMobilityDomain = it },
                                    singleLine = true,
                                    label = { Text("移动域（4 位十六进制）") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = dlgReassocDeadline,
                                    onValueChange = { dlgReassocDeadline = it },
                                    singleLine = true,
                                    label = { Text("重关联期限（TU，默认 20000）") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                SelectRow(
                                    "FT 协议",
                                    when (dlgFtOverDs) {
                                        "0" -> "FT over the Air"
                                        "1" -> "FT over DS"
                                        else -> "默认"
                                    }
                                ) {
                                    selectState = SelectState(
                                        "选择 FT 协议",
                                        listOf("" to "默认", "0" to "FT over the Air", "1" to "FT over DS"),
                                        dlgFtOverDs
                                    ) { v -> dlgFtOverDs = v }
                                }
                                SettingRow("本地生成 PMK", dlgFtPskLocal) { dlgFtPskLocal = it }
                                OutlinedTextField(
                                    value = dlgR0Lifetime,
                                    onValueChange = { dlgR0Lifetime = it },
                                    singleLine = true,
                                    label = { Text("R0 密钥生命周期（分钟，默认 10000）") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = dlgR1KeyHolder,
                                    onValueChange = { dlgR1KeyHolder = it },
                                    singleLine = true,
                                    label = { Text("R1 密钥持有者（12 位十六进制）") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                SettingRow("PMK R1 推送", dlgPmkR1Push) { dlgPmkR1Push = it }
                                OutlinedTextField(
                                    value = dlgR0kh,
                                    onValueChange = { dlgR0kh = it },
                                    label = { Text("外部 R0KH 列表（每行：MAC,NAS-ID,密钥）") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = dlgR1kh,
                                    onValueChange = { dlgR1kh = it },
                                    label = { Text("外部 R1KH 列表（每行：MAC,R1KH-ID,密钥）") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            SettingRow("802.11k RRM（无线电资源测量）", dlgIeee80211k) { dlgIeee80211k = it }
                            if (dlgIeee80211k) {
                                SettingRow("邻居报告", dlgRrmNeighbor) { dlgRrmNeighbor = it }
                                SettingRow("信标报告", dlgRrmBeacon) { dlgRrmBeacon = it }
                            }
                            SelectRow(
                                "时间通告 (802.11v)",
                                if (dlgTimeAdv == "2") "启用" else "禁用"
                            ) {
                                selectState = SelectState(
                                    "选择时间通告",
                                    listOf("" to "禁用", "2" to "启用"),
                                    dlgTimeAdv
                                ) { v -> dlgTimeAdv = v }
                            }
                            OutlinedTextField(
                                value = dlgTimeZone,
                                onValueChange = { dlgTimeZone = it },
                                singleLine = true,
                                label = { Text("时区通告 (802.11v)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            SettingRow("WNM 睡眠模式", dlgWnm) { dlgWnm = it }
                            SettingRow("WNM 睡眠模式修复", dlgWnmNoKeys) { dlgWnmNoKeys = it }
                            SettingRow("BSS 切换 (802.11v)", dlgBssTransition) { dlgBssTransition = it }
                            SettingRow("ProxyARP (802.11v)", dlgProxyArp) { dlgProxyArp = it }
                        }
                        ifaceSectionTab == "advanced" -> {
                            SettingRow("隔离客户端", ifaceIsolate) { ifaceIsolate = it }
                            SettingRow("多播转单播", dlgMulticastToUnicast) { dlgMulticastToUnicast = it }
                            SettingRow("隔离网桥端口", dlgBridgeIsolate) { dlgBridgeIsolate = it }
                            OutlinedTextField(
                                value = dlgIfname,
                                onValueChange = { dlgIfname = it },
                                singleLine = true,
                                label = { Text("接口名（覆盖默认名）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = dlgMacaddr,
                                onValueChange = { dlgMacaddr = it },
                                singleLine = true,
                                label = { Text("MAC 地址覆盖（留空=驱动默认）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            SettingRow("短前导码", ifaceShortPreamble) { ifaceShortPreamble = it }
                            OutlinedTextField(
                                value = dlgGroupRekey,
                                onValueChange = { dlgGroupRekey = it },
                                singleLine = true,
                                label = { Text("GTK 重装密钥间隔（秒，默认 600）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            SettingRow("禁用不活动轮询", dlgSkipInactivity) { dlgSkipInactivity = it }
                            OutlinedTextField(
                                value = dlgMaxInactivity,
                                onValueChange = { dlgMaxInactivity = it },
                                singleLine = true,
                                label = { Text("站点不活动限制（秒，默认 300）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = dlgMaxListenInterval,
                                onValueChange = { dlgMaxListenInterval = it },
                                singleLine = true,
                                label = { Text("最大监听间隔（默认 65535）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            SettingRow("低确认时断开客户端", dlgDisassocLowAck) { dlgDisassocLowAck = it }
                        }
                        else -> {
                            SelectRow("模式", modeLabel(ifaceMode)) {
                                selectState = SelectState(
                                    "选择模式", modeOptions(features?.hostapdMesh == true), ifaceMode
                                ) { v -> ifaceMode = v }
                            }
                            if (dlgRealMode() == "mesh") {
                                OutlinedTextField(
                                    value = dlgMeshId,
                                    onValueChange = { dlgMeshId = it },
                                    singleLine = true,
                                    label = { Text("Mesh ID") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                SettingRow("转发 Mesh 对端流量", dlgMeshFwding) { dlgMeshFwding = it }
                                OutlinedTextField(
                                    value = dlgMeshRssi,
                                    onValueChange = { dlgMeshRssi = it },
                                    singleLine = true,
                                    label = { Text("加入 Mesh 的 RSSI 阈值（0=不使用）") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                OutlinedTextField(
                                    value = ifaceSsid,
                                    onValueChange = { ifaceSsid = it },
                                    singleLine = true,
                                    label = { Text("SSID（Wi-Fi 名称）") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            // LuCI 中 BSSID 仅在 Client/Ad-Hoc 模式下出现。
                            if (dlgRealMode() == "sta" || dlgRealMode() == "adhoc") {
                                OutlinedTextField(
                                    value = ifaceBssid,
                                    onValueChange = { ifaceBssid = it },
                                    singleLine = true,
                                    label = { Text("BSSID（锁定对端 MAC）") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            SelectRow("网络", ifaceNetwork.ifBlank { "未选择" }) {
                                networkPicked = ifaceNetwork.split(Regex("[,，\\s]+"))
                                    .filter { it.isNotBlank() }.toSet()
                                networkPickerOpen = true
                            }
                            SettingRow("隐藏 ESSID", ifaceHidden) { ifaceHidden = it }
                            SettingRow("WMM 模式", ifaceWmm) { ifaceWmm = it }
                        }
                    }
                }
            }
        }
    }

    // ---- 网络选择器（多选，LuCI 网络下拉同源：uci get network 的 interface 段） ----
    if (networkPickerOpen) {
        AppDialog(
            title = "选择网络（可多选）",
            confirmLabel = "确定",
            onConfirm = {
                ifaceNetwork = networkPicked.toList().sorted().joinToString(",")
                networkPickerOpen = false
            },
            onDismiss = { networkPickerOpen = false }
        ) {
            if (networkOptions.isEmpty()) {
                Text(
                    "未能读取网络列表，请稍后重试。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            } else {
                ThinScrollbarColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    networkOptions.forEach { name ->
                        val picked = name in networkPicked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    networkPicked = if (picked) networkPicked - name else networkPicked + name
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (picked) "●" else "○",
                                color = if (picked) colors.primary else colors.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(name, color = colors.onSurface)
                        }
                    }
                }
            }
        }
    }

    // ---- 应用更改确认：列出将写入的段，应用走 SSH commit+wifi reload（无 SSH 时 uci apply） ----
    if (showApplyConfirm) {
        AppDialog(
            title = "应用更改",
            message = buildString {
                append("将以下 ${changes.size} 个段的更改写入路由器并重载无线：\n\n")
                append(changes.keys.joinToString("、"))
                append("\n\n已开启 SSH 时通过 SSH 提交并立即重载（与 LuCI 保存应用一致）；")
                append("未开启 SSH 时使用 uci apply，确认窗口 90 秒，超时未确认会自动回滚。")
            },
            confirmLabel = "应用",
            onConfirm = {
                showApplyConfirm = false
                scope.launch {
                    busy = true
                    message = null
                    try {
                        withContext(Dispatchers.IO) {
                            client.apply(config, buildChanges(), if (sshEnabled) ssh else null) { phase -> setMsg(phase, false) }
                        }
                        setMsg("已应用，Wi-Fi 正在重载。", false)
                        load()
                    } catch (e: Exception) {
                        setMsg(e.message ?: "应用失败。", true)
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = { if (!busy) showApplyConfirm = false }
        )
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
                        "选择频宽",
                        htmodeOptions(effectiveHwmode(hwmode, radioHtmode), radio?.availableHtmodes ?: emptyList()),
                        radioHtmode
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
                        "选择国家代码", COUNTRY_OPTIONS, radioCountry
                    ) { v -> radioCountry = v }
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
                        withContext(Dispatchers.IO) {
                            client.deleteIface(config, sec)
                            // uci delete 仅 staged，这里统一提交并重载（uci apply）。
                            client.apply(config, emptyMap(), if (sshEnabled) ssh else null) { phase -> setMsg(phase, false) }
                        }
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

    // ---- WiFi 分享二维码：竖屏底部弹窗（横屏为右侧滑出面板） ----
    if (!isLandscape) {
        shareIfaceFor?.let { shareIface ->
            ModalBottomSheet(
                onDismissRequest = { shareIfaceFor = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = colors.surface,
                contentColor = colors.onSurface
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 22.dp, bottom = 30.dp)
                ) {
                    WifiShareContent(shareIface)
                }
            }
        }
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
                    ThinScrollbarColumn(modifier = Modifier.heightIn(max = 380.dp)) {
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
            ThinScrollbarColumn(modifier = Modifier.heightIn(max = 340.dp)) {
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

/** 信息格：小号「标签 + 值」横排单元，用于卡片内的两列信息网格。 */
@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 缩小版的开关：默认 M3 Switch 视觉上太大，整体缩放约 3/4。 */
@Composable
private fun SmallSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.scale(0.75f)
    )
}

/** WiFi 分享二维码内容：标题 + 白底二维码 + WiFi 名称 + 灰字密码（竖屏/横屏共用）。 */
@Composable
private fun WifiShareContent(shareIface: WirelessIface) {
    val colors = LocalAppColors.current
    val qrContent = wifiQrContent(
        shareIface.ssid, shareIface.key, shareIface.encryption, shareIface.hidden
    )
    val qrBitmap = remember(qrContent) { wifiQrBitmap(qrContent) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "分享二维码",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface
        )
        Spacer(Modifier.height(16.dp))
        // 白底容器保证深色模式下二维码同样可被相机识别。
        Surface(
            shape = AppShapes.block,
            color = Color.White
        ) {
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "WiFi 二维码",
                    modifier = Modifier
                        .padding(10.dp)
                        .size(240.dp)
                )
            } else {
                Text(
                    "二维码生成失败。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            shareIface.ssid.ifEmpty { "（未设置 SSID）" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
            maxLines = 2
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                shareIface.encryption == "none" -> "密码：无（开放网络）"
                shareIface.key.isNullOrBlank() -> "密码：未获取"
                else -> "密码：${shareIface.key}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
    }
}

/** 横屏右侧滑出的分享面板：遮罩点击关闭，预测性返回时面板跟手向右滑出。 */
@Composable
private fun WifiShareSidePanel(iface: WirelessIface, onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    var backProgress by remember { mutableStateOf(0f) }
    PredictiveBackHandler { progress ->
        try {
            progress.collect { backProgress = it.progress }
            onDismiss()
        } catch (_: CancellationException) {
            backProgress = 0f
        }
    }
    val p = PredictiveBackEasing.transform(backProgress).coerceIn(0f, 1f)
    Box(Modifier.fillMaxSize()) {
        // 半透明遮罩：点击空白关闭，随返回手势逐渐变透。
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.32f * (1f - p)))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
        )
        Surface(
            shape = AppShapes.card,
            color = colors.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(top = 12.dp, bottom = 12.dp, end = 12.dp)
                .fillMaxHeight()
                .width(300.dp)
                .graphicsLayer {
                    alpha = 1f - 0.4f * p
                    translationX = size.width * 0.3f * p
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                WifiShareContent(iface)
            }
        }
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
        SmallSwitch(checked = checked, onCheckedChange = onChange)
    }
}
