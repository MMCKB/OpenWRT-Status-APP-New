package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** LuCI 特性表（ubus `luci getFeatures`）中影响无线选项展示的子集。 */
data class LuciFeatures(
    val hostapdMesh: Boolean,
    val hostapd11r: Boolean,
    val hostapdEap: Boolean,
    val hostapdSae: Boolean,
    val hostapdSuiteb192: Boolean,
    val hostapdOwe: Boolean,
    val hostapdWep: Boolean,
    val hostapdWps: Boolean,
    val hostapd11ac: Boolean,
    val hostapd11ax: Boolean,
    val hostapd11be: Boolean
) {
    /** getFeatures 不可用时的回退：按完整版 wpad（无 WEP）处理。 */
    companion object {
        val FALLBACK = LuciFeatures(
            hostapdMesh = true, hostapd11r = true, hostapdEap = true, hostapdSae = true,
            hostapdSuiteb192 = true, hostapdOwe = true, hostapdWep = false, hostapdWps = true,
            hostapd11ac = true, hostapd11ax = true, hostapd11be = false
        )
    }
}

/** 一个无线 radio（wifi-device 段）及其下的接口（wifi-iface 段）。 */
data class WirelessRadio(
    val section: String,
    val channel: String?,
    val htmode: String?,
    val txpower: String?,
    val country: String?,
    val band: String?,
    val hwmode: String? = null,
    val disabled: Boolean,
    val ifaces: List<WirelessIface>,
    // iwinfo 实时数据
    val liveChannel: Int? = null,
    val liveTxpower: Int? = null,
    val liveNoise: Int? = null,
    val liveHwmodesText: String? = null,
    val availableHtmodes: List<String> = emptyList(),
    val availableHwmodes: List<String> = emptyList(),
    /** 当前速率（Mbit/s，LuCI 网卡行「速率」同源：接口 iwinfo bitrate 或最快客户端速率）。 */
    val liveRateMbits: Double? = null,
    /** typed 字段之外的 uci 选项（cell_density/distance/noscan/ieee80211r 等），值为字符串；列表以换行连接。 */
    val extra: Map<String, String> = emptyMap()
)

/** 一个无线接口（wifi-iface 段）。 */
data class WirelessIface(
    val section: String,
    val device: String,
    val mode: String?,
    val ssid: String,
    val network: String?,
    val key: String?,
    val encryption: String?,
    val hidden: Boolean,
    val isolate: Boolean,
    val wmm: Boolean,
    val bssid: String? = null,
    val dtim: String? = null,
    val beaconInt: String? = null,
    val frag: String? = null,
    val rts: String? = null,
    val shortPreamble: Boolean = true,
    val macfilter: String? = null,
    val maclist: List<String> = emptyList(),
    val disabled: Boolean,
    // iwinfo 实时数据
    val liveIfname: String? = null,
    val liveBssid: String? = null,
    val liveMode: String? = null,
    val liveEncryption: String? = null,
    val liveSsid: String? = null,
    val liveSignal: Int? = null,
    val liveNoise: Int? = null,
    val liveRateMbits: Double? = null,
    val clientCount: Int? = null,
    /** typed 字段之外的 uci 选项（ifname/macaddr/ieee80211w/802.11r 漫游等），值为字符串；列表以换行连接。 */
    val extra: Map<String, String> = emptyMap()
)

/** iwinfo 扫描到的邻近网络。 */
data class ScanNet(
    val ssid: String,
    val bssid: String,
    val channel: Int?,
    val signal: Int?,
    val encrypted: Boolean
)

/**
 * 无线设置数据层：uci 读写 + 实时数据全部走 ubus（无需 SSH）。
 *
 * 数据源（与本机路由器实测的 rpcd/uhttpd 会话 ACL 对齐）：
 * - `uci get/set/add/delete`：读改配置，全部放行；
 * - `luci-rpc getWirelessDevices`（LuCI 自己的数据通路）：一次返回 radio/接口的
 *   ifname、iwinfo 与已连接客户端，`iwinfo devices` 与 `network.*` 在部分固件上
 *   被 ACL 拒绝，不能依赖；
 * - **应用变更走 `uci apply {timeout, rollback:true}` + `uci confirm`**（LuCI 同款），
 *   而不是 `uci commit` + `network reload`——会话 ACL 拒绝 commit/reload，且 apply
 *   自带回滚保护：应用后若 unable confirm（例如无线被配挂导致手机断网），rpcd 会在
 *   超时后自动还原配置，避免把用户锁在路由器外面。
 */
class WirelessClient(private val rpc: UbusRpcClient = UbusRpcClient()) {

    companion object {
        /** load() 解析时排除的 radio typed/系统键，其余进入 extra。 */
        private val RADIO_TYPED_KEYS = setOf(
            "channel", "htmode", "txpower", "country", "band", "hwmode", "disabled",
            "type", "path"
        )

        /** load() 解析时排除的 iface typed/系统键，其余进入 extra。 */
        private val IFACE_TYPED_KEYS = setOf(
            "device", "mode", "ssid", "network", "key", "encryption", "hidden",
            "isolate", "wmm", "bssid", "dtim", "beacon_int", "frag", "rts",
            "short_preamble", "macfilter", "maclist", "disabled"
        )

        /** 固件/驱动默认开启的开关：关闭时必须显式写 0，不能靠缺省。 */
        internal val DEFAULT_ON_FLAGS = setOf("wmm", "short_preamble", "disassoc_low_ack", "rxldpc", "ldpc", "ft_psk_generate_local")

        /** uci apply 的确认窗口（秒），与 LuCI 的 apply_timeout 对齐：无线重载会让手机断网片刻，窗口内连回即确认成功。 */
        internal const val APPLY_CONFIRM_TIMEOUT_SEC = 90
    }

    private suspend fun call(
        config: RouterConfig,
        target: String,
        method: String,
        params: kotlinx.serialization.json.JsonObject,
        fast: Boolean = false
    ): kotlinx.serialization.json.JsonElement = withContext(Dispatchers.IO) {
        val endpoint = rpc.buildEndpoint(config.ip, config.port, config.useHttps)
        val token = rpc.login(endpoint, config.username, config.password, config.allowInsecureTls, fast)
        rpc.call(endpoint, token, target, method, params, config.allowInsecureTls, fast)
    }

    private suspend fun iwinfo(
        config: RouterConfig,
        method: String,
        device: String,
        fast: Boolean = false
    ): JsonObject? =
        runCatching {
            call(
                config, "iwinfo", method,
                buildJsonObject { put("device", kotlinx.serialization.json.JsonPrimitive(device)) },
                fast
            ).jsonObject
        }.getOrNull()

    private fun str(section: JsonObject, key: String): String? =
        (section[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun bool(section: JsonObject, key: String): Boolean =
        (section[key] as? JsonPrimitive)?.content == "1"

    private fun int(element: kotlinx.serialization.json.JsonElement?): Int? =
        (element as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()

    private fun long(element: kotlinx.serialization.json.JsonElement?): Long? =
        (element as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()

    /** uci 选项值 → extra 字符串：列表以换行连接。 */
    private fun extraValue(element: kotlinx.serialization.json.JsonElement?): String? = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
        is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
        else -> null
    }

    private fun wpaName(wpa: Int): String = when (wpa) {
        1 -> "WPA"
        2 -> "WPA2"
        3 -> "WPA3"
        else -> "WPA"
    }

    private fun encryptionText(enc: JsonObject?): String? {
        enc ?: return null
        val enabled = (enc["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        if (!enabled) return "无加密"
        val wpa = (enc["wpa"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            ?.maxOrNull()
            ?: (enc["wpa"] as? JsonPrimitive)?.content?.toIntOrNull()
        val ciphers = (enc["ciphers"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        return when {
            wpa != null -> "${wpaName(wpa)} (${ciphers.joinToString("/")})"
            else -> "已加密"
        }
    }

    /** 读取无线配置（uci）+ 实时数据合并。全部走快速档：路由器不可达时秒级失败，不拖住界面。 */
    suspend fun load(config: RouterConfig): List<WirelessRadio> = withContext(Dispatchers.IO) {
        val payload = call(
            config, "uci", "get",
            buildJsonObject { put("config", kotlinx.serialization.json.JsonPrimitive("wireless")) },
            fast = true
        )
        val values = (payload.jsonObject["values"] as? JsonObject) ?: return@withContext emptyList()

        val radios = mutableListOf<WirelessRadio>()
        val ifaces = mutableListOf<WirelessIface>()
        for ((sectionName, el) in values) {
            val sec = el as? JsonObject ?: continue
            val extra = sec.mapNotNull { (k, v) ->
                if (k.startsWith(".")) null
                else extraValue(v)?.let { k to it }
            }.toMap()
            when (str(sec, ".type")) {
                "wifi-device" -> radios.add(
                    WirelessRadio(
                        section = sectionName,
                        channel = str(sec, "channel"),
                        htmode = str(sec, "htmode"),
                        txpower = str(sec, "txpower"),
                        country = str(sec, "country"),
                        band = str(sec, "band"),
                        hwmode = str(sec, "hwmode"),
                        disabled = bool(sec, "disabled"),
                        ifaces = emptyList(),
                        extra = extra.filterKeys { it !in RADIO_TYPED_KEYS }
                    )
                )
                "wifi-iface" -> ifaces.add(
                    WirelessIface(
                        section = sectionName,
                        device = str(sec, "device") ?: "",
                        mode = str(sec, "mode") ?: "ap",
                        ssid = str(sec, "ssid") ?: "",
                        network = sec["network"]?.let { n ->
                            when (n) {
                                is JsonArray -> n.mapNotNull { (it as? JsonPrimitive)?.content }
                                    .joinToString(",")
                                is JsonPrimitive -> n.content
                                else -> null
                            }
                        },
                        key = str(sec, "key"),
                        encryption = str(sec, "encryption"),
                        hidden = bool(sec, "hidden"),
                        isolate = bool(sec, "isolate"),
                        wmm = if (sec.containsKey("wmm")) bool(sec, "wmm") else true,
                        bssid = str(sec, "bssid"),
                        dtim = str(sec, "dtim"),
                        beaconInt = str(sec, "beacon_int"),
                        frag = str(sec, "frag"),
                        rts = str(sec, "rts"),
                        shortPreamble = if (sec.containsKey("short_preamble")) bool(sec, "short_preamble") else true,
                        macfilter = str(sec, "macfilter"),
                        maclist = ((sec["maclist"] as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.content }
                            ?: str(sec, "maclist")?.split(Regex("[, ]+"))?.filter { m -> m.isNotEmpty() }
                            ?: emptyList()),
                        disabled = bool(sec, "disabled"),
                        extra = extra.filterKeys { it !in IFACE_TYPED_KEYS }
                    )
                )
            }
        }
        if (radios.isEmpty()) return@withContext emptyList()

        // 关键：把解析出的接口挂到所属 radio（缺失 device 字段时归入第一个网卡），
        // 否则 WiFi 永远显示「无 WiFi 接口」。
        val attached = radios.map { radio ->
            radio.copy(ifaces = ifaces.filter { it.device == radio.section })
        }
        val orphans = ifaces.filter { f -> attached.none { r -> r.ifaces.any { it.section == f.section } } }
        val attachedFinal = if (orphans.isNotEmpty() && attached.isNotEmpty()) {
            attached.mapIndexed { index, radio ->
                if (index == 0) radio.copy(ifaces = radio.ifaces + orphans) else radio
            }
        } else attached

        val merged = mergeLiveData(config, attachedFinal)
        merged.map { radio ->
            radio.copy(liveRateMbits = radio.ifaces.firstOrNull()?.liveRateMbits)
        }
    }

    /**
     * 实时数据合并：优先 `luci-rpc getWirelessDevices`（LuCI 同款数据通路，
     * 一次拿到 ifname/加密/客户端数/速率）；失败时回退到 iwinfo info/assoclist 逐个查询。
     */
    private suspend fun mergeLiveData(
        config: RouterConfig,
        radios: List<WirelessRadio>
    ): List<WirelessRadio> {
        val viaLuci = runCatching { liveViaLuciRpc(config, radios) }.getOrNull()
        if (viaLuci != null) return viaLuci
        return liveViaIwinfo(config, radios)
    }

    private suspend fun liveViaLuciRpc(
        config: RouterConfig,
        radios: List<WirelessRadio>
    ): List<WirelessRadio>? {
        val payload = call(config, "luci-rpc", "getWirelessDevices", buildJsonObject { }, fast = true)
        val root = payload.jsonObject
        return radios.map { radio ->
            var r = radio
            val live = root[radio.section]?.jsonObject
            live?.get("iwinfo")?.jsonObject?.let { info ->
                r = r.copy(
                    liveChannel = int(info["channel"]),
                    liveTxpower = int(info["txpower"]),
                    liveNoise = int(info["noise"]),
                    availableHtmodes = (info["htmodes"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                    availableHwmodes = (info["hwmodes"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                    liveHwmodesText = str(info, "hwmodes_text")
                )
            }
            val ifaceEls = (live?.get("interfaces") as? JsonArray) ?: JsonArray(emptyList())
            r = r.copy(ifaces = r.ifaces.map { iface ->
                val entry = ifaceEls.firstOrNull {
                    (it as? JsonObject)?.get("section")?.let { s -> (s as? JsonPrimitive)?.content } == iface.section
                } as? JsonObject ?: return@map iface
                val info = entry["iwinfo"]?.jsonObject
                val stations = entry["stations"] as? JsonArray ?: JsonArray(emptyList())
                val stationRate = stations.mapNotNull { st ->
                    val obj = st as? JsonObject ?: return@mapNotNull null
                    long(obj["rate"])
                        ?: ((obj["rx"] as? JsonObject)?.get("rate"))?.let { long(it) }
                        ?: ((obj["tx"] as? JsonObject)?.get("rate"))?.let { long(it) }
                }.maxOrNull()
                val bitrateKbits = info?.get("bitrate")?.let {
                    (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
                        ?: (it as? JsonObject)?.get("rate")?.let { rr -> long(rr) }
                }
                iface.copy(
                    liveIfname = str(entry, "ifname") ?: iface.liveIfname,
                    liveBssid = info?.let { str(it, "bssid") } ?: iface.liveBssid,
                    liveSsid = info?.let { str(it, "ssid") } ?: iface.liveSsid,
                    liveMode = info?.let { str(it, "mode") } ?: iface.liveMode,
                    liveEncryption = info?.get("encryption")?.jsonObject?.let { encryptionText(it) }
                        ?: iface.liveEncryption,
                    liveSignal = info?.let { int(it["signal"]) } ?: iface.liveSignal,
                    liveNoise = info?.let { int(it["noise"]) } ?: iface.liveNoise,
                    clientCount = stations.size.takeIf { it > 0 } ?: 0,
                    liveRateMbits = (bitrateKbits ?: stationRate)?.let { it / 1000.0 }
                )
            })
            r
        }
    }

    /** 旧回退路径：iwinfo devices（部分固件被 ACL 拒绝）+ info + assoclist。 */
    private suspend fun liveViaIwinfo(
        config: RouterConfig,
        radios: List<WirelessRadio>
    ): List<WirelessRadio> {
        val wifiIfaces = runCatching {
            (iwinfo(config, "devices", "wireless", fast = true)?.get("devices") as? JsonArray)
                ?.mapNotNull { dev ->
                    ((dev as? JsonObject)?.get("name") as? JsonPrimitive)?.content
                }
                ?: emptyList()
        }.getOrDefault(emptyList())

        return radios.map { radio ->
            var r = radio
            val info = iwinfo(config, "info", radio.section, fast = true)
            info?.let { radioInfo ->
                r = r.copy(
                    liveChannel = int(radioInfo["channel"]),
                    liveTxpower = int(radioInfo["txpower"]),
                    liveNoise = int(radioInfo["noise"]),
                    availableHtmodes = (radioInfo["htmodes"] as? JsonArray)
                        ?.mapNotNull { mode -> (mode as? JsonPrimitive)?.content } ?: emptyList(),
                    availableHwmodes = (radioInfo["hwmodes"] as? JsonArray)
                        ?.mapNotNull { mode -> (mode as? JsonPrimitive)?.content } ?: emptyList(),
                    liveHwmodesText = str(radioInfo, "hwmodes_text")
                )
            }
            val phyName = info?.get("phy")?.let { p -> (p as? JsonPrimitive)?.content }
            val ifn = wifiIfaces.firstOrNull { it.startsWith("$phyName-") }
            if (ifn != null) {
                iwinfo(config, "info", ifn, fast = true)?.let { ifaceInfo ->
                    val assoc = runCatching {
                        (call(
                            config, "iwinfo", "assoclist",
                            buildJsonObject { put("device", kotlinx.serialization.json.JsonPrimitive(ifn)) },
                            fast = true
                        ).jsonObject["results"] as? JsonArray)
                    }.getOrNull()
                    val maxRate = assoc.orEmpty().mapNotNull { entry ->
                        val obj = entry as? JsonObject ?: return@mapNotNull null
                        ((obj["rx"] as? JsonObject)?.get("rate"))?.let { long(it) }
                            ?: ((obj["tx"] as? JsonObject)?.get("rate"))?.let { long(it) }
                    }.maxOrNull()
                    val bitrateKbits = ifaceInfo["bitrate"]?.let {
                        (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
                    }
                    r = r.copy(ifaces = r.ifaces.map { f ->
                        if (f.device == radio.section) {
                            f.copy(
                                liveIfname = ifn,
                                liveBssid = str(ifaceInfo, "bssid") ?: f.liveBssid,
                                liveSsid = str(ifaceInfo, "ssid") ?: f.liveSsid,
                                liveMode = str(ifaceInfo, "mode") ?: f.liveMode,
                                liveEncryption = ifaceInfo["encryption"]?.jsonObject?.let { encryptionText(it) }
                                    ?: f.liveEncryption,
                                liveSignal = int(ifaceInfo["signal"]),
                                liveNoise = int(ifaceInfo["noise"]),
                                clientCount = assoc?.size,
                                liveRateMbits = (bitrateKbits ?: maxRate)?.let { it / 1000.0 }
                            )
                        } else f
                    })
                }
            }
            r
        }
    }

    /**
     * 应用变更：逐段 uci set/delete（staged）后提交。
     * [ssh] 非空时走与旧版 OpenWRT-Status-APP 相同的方式：SSH 直跑
     * `uci commit wireless && wifi reload`——立即生效，无确认/回滚流程。
     * [ssh] 为空时退回 ubus `uci apply {rollback}` + confirm（90 秒确认窗口，超时自动还原）。
     * [onPhase] 逐阶段回报进度（会在 IO 线程回调），供界面提示当前状态。
     */
    suspend fun apply(
        config: RouterConfig,
        changes: Map<String, Map<String, Any>>,
        ssh: SshConfig? = null,
        onPhase: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (ssh != null) {
            onPhase("正在写入并重载无线…")
            // 与旧版 OpenWRT-Status-APP 一致：uci set + commit + reload 在同一条 SSH 脚本内完成，
            // 不依赖 ubus 暂存的跨进程传递。
            val script = buildShellScript("wireless", changes) +
                "uci commit wireless && wifi reload && echo __WIRELESS_APPLY_OK__"
            val committed = runCatching {
                SshExec.run(ssh, script, 30_000).contains("__WIRELESS_APPLY_OK__")
            }.getOrDefault(false)
            if (committed) return@withContext
            onPhase("SSH 提交失败，改用 uci apply 提交…")
            ubusWrite(config, changes, onPhase)
            applyAndReload(config, onPhase)
        } else {
            onPhase("正在写入配置…")
            ubusWrite(config, changes, onPhase)
            applyAndReload(config, onPhase)
        }
    }

    /** 把 changes 转成 uci set/delete 的 shell 命令（空串=删除，换行/列表=add_list）。 */
    private fun buildShellScript(configName: String, changes: Map<String, Map<String, Any>>): String {
        val sb = StringBuilder()
        for ((section, values) in changes) {
            val sec = shq(section)
            for ((key, value) in values) {
                val keyQ = shq(key)
                when {
                    value is String && value.isEmpty() ->
                        sb.append("uci -q delete ").append(configName).append(".").append(sec).append(".").append(keyQ).append("; ")
                    value is String && value.contains('\n') -> {
                        sb.append("uci -q delete ").append(configName).append(".").append(sec).append(".").append(keyQ).append("; ")
                        for (line in value.split('\n').filter { it.isNotBlank() }) {
                            sb.append("uci add_list ").append(configName).append(".").append(sec).append(".").append(keyQ)
                                .append("=").append(shq(line)).append("; ")
                        }
                    }
                    value is List<*> -> {
                        sb.append("uci -q delete ").append(configName).append(".").append(sec).append(".").append(keyQ).append("; ")
                        for (item in value) {
                            sb.append("uci add_list ").append(configName).append(".").append(sec).append(".").append(keyQ)
                                .append("=").append(shq(item.toString())).append("; ")
                        }
                    }
                    value is Boolean -> sb.append("uci set ").append(configName).append(".").append(sec).append(".").append(keyQ)
                        .append("='").append(if (value) "1" else "0").append("'; ")
                    else -> sb.append("uci set ").append(configName).append(".").append(sec).append(".").append(keyQ)
                        .append("=").append(shq(value.toString())).append("; ")
                }
            }
        }
        return sb.toString()
    }

    /** 单引号 shell 转义（' → '\''）。 */
    private fun shq(v: String): String = "'" + v.replace("'", "'\\''") + "'"

    /** 无 SSH 后备：ubus 逐段 uci set/delete（staged）。空字符串值表示删除该选项。 */
    private suspend fun ubusWrite(config: RouterConfig, changes: Map<String, Map<String, Any>>, onPhase: (String) -> Unit) = withContext(Dispatchers.IO) {
        for ((section, values) in changes) {
            val deletions = values.filterKeys { it.isEmpty() }.keys
            val writes = values.filterNot { it.value is String && (it.value as String).isEmpty() }
            if (writes.isNotEmpty()) {
                call(
                    config, "uci", "set",
                    buildJsonObject {
                        put("config", kotlinx.serialization.json.JsonPrimitive("wireless"))
                        put("section", kotlinx.serialization.json.JsonPrimitive(section))
                        put("values", buildJsonObject {
                            writes.forEach { (k, v) ->
                                when {
                                    v is List<*> && v.isNotEmpty() -> put(
                                        k, JsonArray(v.map { kotlinx.serialization.json.JsonPrimitive(it.toString()) })
                                    )
                                    v is List<*> -> {}
                                    v is Boolean -> put(k, kotlinx.serialization.json.JsonPrimitive(if (v) "1" else "0"))
                                    v is String && v.contains('\n') -> put(
                                        k, JsonArray(v.split('\n').filter { it.isNotBlank() }
                                            .map { kotlinx.serialization.json.JsonPrimitive(it) })
                                    )
                                    else -> put(k, kotlinx.serialization.json.JsonPrimitive(v.toString()))
                                }
                            }
                        })
                    },
                    fast = true
                )
            }
            for (opt in deletions) {
                call(
                    config, "uci", "delete",
                    buildJsonObject {
                        put("config", kotlinx.serialization.json.JsonPrimitive("wireless"))
                        put("section", kotlinx.serialization.json.JsonPrimitive(section))
                        put("option", kotlinx.serialization.json.JsonPrimitive(opt))
                    },
                    fast = true
                )
            }
        }
    }

    /**
     * 提交所有 staged 变更并重载无线（LuCI apply 协议）：
     * `uci apply {timeout, rollback:true}` 后延时确认 `uci confirm`，确认失败在超时窗口内
     * 每 250ms 重试；全程无法确认时 rpcd 自动回滚——本方法抛出异常明确告知用户配置已还原。
     */
    private suspend fun applyAndReload(config: RouterConfig, onPhase: (String) -> Unit) {
        val deadline = System.currentTimeMillis() + APPLY_CONFIRM_TIMEOUT_SEC * 1000L
        onPhase("正在应用并重载无线…")
        try {
            call(
                config, "uci", "apply",
                buildJsonObject {
                    put("timeout", kotlinx.serialization.json.JsonPrimitive(APPLY_CONFIRM_TIMEOUT_SEC))
                    put("rollback", kotlinx.serialization.json.JsonPrimitive(true))
                },
                fast = true
            )
        } catch (e: RouterException) {
            if (e.ubusCode != 5) throw e // 5 = 无变更可应用，同样视为成功
        }
        delay(1000)
        onPhase("等待确认应用（最长 ${APPLY_CONFIRM_TIMEOUT_SEC} 秒，手机重连 Wi-Fi 后自动完成）…")
        while (true) {
            try {
                call(config, "uci", "confirm", buildJsonObject { }, fast = true)
                return
            } catch (e: RouterException) {
                if (System.currentTimeMillis() >= deadline) {
                    throw RouterException(
                        "未能确认应用，配置已被路由器自动还原。",
                        "本次修改会导致 Wi-Fi 重启、手机短暂断开，重连后本可自动确认。请等手机连回 Wi-Fi 后重试；若多次失败，请先修改不影响当前连接的部分（如另一频段的网卡）。",
                        e.ubusCode
                    )
                }
                delay(250)
            }
        }
    }

    /**
     * 添加 WiFi 接口。SSH 可用时一条 shell 完成 add+rename+set+commit（同旧版应用，
     * reload 由随后的 [apply] 统一执行）；无 SSH 时 ubus 暂存并由 [apply] 提交。
     * [values] 值支持 String（空串跳过）、Boolean（"1"/"0"）与 List<String>（uci 列表）。
     */
    suspend fun addIface(
        config: RouterConfig,
        device: String,
        values: Map<String, Any>,
        ssh: SshConfig? = null,
        onPhase: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (ssh != null) {
            onPhase("正在创建无线接口…")
            val name = "app" + java.lang.Long.toString(System.currentTimeMillis(), 36)
            val sets = StringBuilder()
            sets.append("uci set wireless.").append(shq(name)).append(".device=").append(shq(device)).append("; ")
            values.forEach { (k, v) ->
                when {
                    v is String && v.isEmpty() -> {}
                    v is List<*> && v.isNotEmpty() -> {
                        sets.append("uci -q delete wireless.").append(shq(name)).append(".").append(shq(k)).append("; ")
                        for (item in v) sets.append("uci add_list wireless.").append(shq(name)).append(".").append(shq(k)).append("=").append(shq(item.toString())).append("; ")
                    }
                    v is Boolean -> sets.append("uci set wireless.").append(shq(name)).append(".").append(shq(k)).append("=").append(shq(if (v) "1" else "0")).append("; ")
                    v is String && v.contains('\n') -> {
                        sets.append("uci -q delete wireless.").append(shq(name)).append(".").append(shq(k)).append("; ")
                        for (line in v.split('\n').filter { it.isNotBlank() }) sets.append("uci add_list wireless.").append(shq(name)).append(".").append(shq(k)).append("=").append(shq(line)).append("; ")
                    }
                    else -> sets.append("uci set wireless.").append(shq(name)).append(".").append(shq(k)).append("=").append(shq(v.toString())).append("; ")
                }
            }
            val script = "S=\$(uci add wireless wifi-iface) && uci rename wireless.\$S=" + shq(name) + " && " + sets + "uci commit wireless; echo __WIRELESS_ADD_OK__"
            val ok = runCatching { SshExec.run(ssh, script, 30_000).contains("__WIRELESS_ADD_OK__") }.getOrDefault(false)
            if (ok) return@withContext
            onPhase("SSH 创建失败，改用 ubus 暂存…")
        }
        call(
            config, "uci", "add",
            buildJsonObject {
                put("config", kotlinx.serialization.json.JsonPrimitive("wireless"))
                put("type", kotlinx.serialization.json.JsonPrimitive("wifi-iface"))
                put("values", buildJsonObject {
                    put("device", kotlinx.serialization.json.JsonPrimitive(device))
                    values.forEach { (k, v) ->
                        when {
                            v is String && v.isEmpty() -> {}
                            v is List<*> && v.isNotEmpty() -> put(
                                k, JsonArray(v.map { kotlinx.serialization.json.JsonPrimitive(it.toString()) })
                            )
                            v is List<*> -> {}
                            v is Boolean -> put(k, kotlinx.serialization.json.JsonPrimitive(if (v) "1" else "0"))
                            v is String && v.contains('\n') -> put(
                                k, JsonArray(v.split('\n').filter { it.isNotBlank() }
                                    .map { kotlinx.serialization.json.JsonPrimitive(it) })
                            )
                            else -> put(k, kotlinx.serialization.json.JsonPrimitive(v.toString()))
                        }
                    }
                })
            },
            fast = true
        )
    }

    /** 删除接口段：SSH 可用一条 shell 完成 delete+commit+reload；否则 ubus 暂存后由调用方 [apply] 提交。 */
    suspend fun deleteIface(
        config: RouterConfig,
        section: String,
        ssh: SshConfig? = null,
        onPhase: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (ssh != null) {
            onPhase("正在删除并重载无线…")
            val script = "uci -q delete wireless." + shq(section) + "; uci commit wireless && wifi reload && echo __WIRELESS_APPLY_OK__"
            val ok = runCatching { SshExec.run(ssh, script, 30_000).contains("__WIRELESS_APPLY_OK__") }.getOrDefault(false)
            if (ok) return@withContext
            onPhase("SSH 删除失败，改用 uci apply 提交…")
        }
        call(
            config, "uci", "delete",
            buildJsonObject {
                put("config", kotlinx.serialization.json.JsonPrimitive("wireless"))
                put("section", kotlinx.serialization.json.JsonPrimitive(section))
            },
            fast = true
        )
        if (ssh == null) applyAndReload(config, onPhase)
    }

    /** 路由器上的网络（/etc/config/network 的 interface 段名），供 WiFi 的「网络」选择。 */
    suspend fun listNetworks(config: RouterConfig): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = call(
                config, "uci", "get",
                buildJsonObject { put("config", kotlinx.serialization.json.JsonPrimitive("network")) },
                fast = true
            )
            val values = payload.jsonObject["values"] as? JsonObject ?: return@runCatching emptyList()
            values.mapNotNull { (name, el) ->
                val sec = el as? JsonObject ?: return@mapNotNull null
                if (str(sec, ".type") != "interface") return@mapNotNull null
                (str(sec, ".name") ?: name).takeUnless { it == "loopback" }
            }.sorted()
        }.getOrDefault(emptyList())
    }

    /** LuCI 特性表（决定 Mesh 模式/802.11r/EAP/SAE/OWE 等选项是否出现）。 */
    suspend fun features(config: RouterConfig): LuciFeatures? = withContext(Dispatchers.IO) {
        parseFeatures(runCatching { call(config, "luci", "getFeatures", buildJsonObject { }, fast = true) }.getOrNull())
            ?: parseFeatures(runCatching { call(config, "luci-rpc", "getFeatures", buildJsonObject { }, fast = true) }.getOrNull())
    }

    private fun parseFeatures(payload: kotlinx.serialization.json.JsonElement?): LuciFeatures? {
        val root = payload?.jsonObject ?: return null
        val hostapd = root["hostapd"]?.jsonObject ?: return null
        fun flag(key: String): Boolean = (hostapd[key] as? JsonPrimitive)?.content == "true"
        return LuciFeatures(
            hostapdMesh = flag("mesh"),
            hostapd11r = flag("11r"),
            hostapdEap = flag("eap"),
            hostapdSae = flag("sae"),
            hostapdSuiteb192 = flag("suiteb192"),
            hostapdOwe = flag("owe"),
            hostapdWep = flag("wep"),
            hostapdWps = flag("wps"),
            hostapd11ac = flag("11ac"),
            hostapd11ax = flag("11ax"),
            hostapd11be = flag("11be")
        )
    }

    /** 网卡实际可用信道（iwinfo freqlist）：Triple(信道, 频率 MHz, 是否 DFS/no-IR)。 */
    suspend fun freqList(config: RouterConfig, device: String): List<Triple<Int, Int, Boolean>> = withContext(Dispatchers.IO) {
        runCatching {
            (iwinfo(config, "freqlist", device, fast = true)?.get("results") as? JsonArray)
                ?.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val channel = int(obj["channel"]) ?: return@mapNotNull null
                    val mhz = int(obj["mhz"]) ?: return@mapNotNull null
                    val noIr = ((obj["flags"] as? JsonArray)
                        ?.any { flag -> (flag as? JsonPrimitive)?.content == "no_ir" }) == true
                    Triple(channel, mhz, noIr)
                }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    /** 网卡实际支持的发射功率档（dBm，来自 iwinfo txpowerlist）。 */
    suspend fun txPowerList(config: RouterConfig, device: String): List<Int> = withContext(Dispatchers.IO) {
        runCatching {
            (iwinfo(config, "txpowerlist", device, fast = true)?.get("results") as? JsonArray)
                ?.mapNotNull { el ->
                    (el as? JsonObject)?.get("dbm")?.let { int(it) }
                }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    /** 国家代码表（来自 iwinfo countrylist），返回 (ISO 代码, "代码 - 国家名")。 */
    suspend fun countryList(config: RouterConfig, device: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            (iwinfo(config, "countrylist", device, fast = true)?.get("results") as? JsonArray)
                ?.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val iso = str(obj, "iso3166") ?: return@mapNotNull null
                    val name = str(obj, "country") ?: ""
                    iso to "$iso - $name"
                }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    /** 扫描 radio 附近的网络（iwinfo scan）。 */
    suspend fun scan(config: RouterConfig, device: String): List<ScanNet> = withContext(Dispatchers.IO) {
        val info = iwinfo(config, "scan", device) ?: return@withContext emptyList()
        (info["results"] as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val enc = obj["encryption"]?.jsonObject ?: kotlinx.serialization.json.buildJsonObject { }
            ScanNet(
                ssid = str(obj, "ssid") ?: "",
                bssid = str(obj, "bssid") ?: "",
                channel = (obj["channel"] as? JsonPrimitive)?.content?.toIntOrNull(),
                signal = (obj["signal"] as? JsonPrimitive)?.content?.toIntOrNull(),
                encrypted = (enc["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
            )
        }.orEmpty()
    }
}

class WirelessApplyException(message: String, cause: Exception? = null) : Exception(message, cause)
