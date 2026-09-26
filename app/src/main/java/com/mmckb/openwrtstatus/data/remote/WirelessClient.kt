package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

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
     * 应用变更：逐段 uci set/delete → `uci apply {rollback:true}` → `uci confirm`。
     * 空字符串值表示删除该选项（LuCI rmempty 语义）；换行分隔的字符串按 uci 列表写入；
     * Boolean 写成 "1"/"0"；List 直接作为 uci 列表写入。
     * 无可应用变更时 rpcd 返回 ubus 代码 5（NO_DATA），视为成功。
     * [onPhase] 逐阶段回报进度（会在 IO 线程回调），供界面提示当前状态。
     */
    suspend fun apply(
        config: RouterConfig,
        changes: Map<String, Map<String, Any>>,
        onPhase: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        onPhase("正在写入配置…")
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
        applyAndReload(config, onPhase)
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
     * 添加 WiFi 接口（仅 staged 的 `uci add`，不提交）：
     * [values] 值支持 String（空串跳过）、Boolean（"1"/"0"）与 List<String>（uci 列表）。
     * 返回后由 [apply] 与其余待应用变更一起提交并重载。
     */
    suspend fun addIface(
        config: RouterConfig,
        device: String,
        values: Map<String, Any>
    ) = withContext(Dispatchers.IO) {
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

    /** 删除接口段（仅 staged 的 `uci delete`，不提交），提交由 [apply] 统一完成。 */
    suspend fun deleteIface(config: RouterConfig, section: String) = withContext(Dispatchers.IO) {
        call(
            config, "uci", "delete",
            buildJsonObject {
                put("config", kotlinx.serialization.json.JsonPrimitive("wireless"))
                put("section", kotlinx.serialization.json.JsonPrimitive(section))
            },
            fast = true
        )
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
