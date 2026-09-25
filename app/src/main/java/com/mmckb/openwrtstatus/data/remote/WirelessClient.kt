package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import kotlinx.coroutines.Dispatchers
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
    val availableHtmodes: List<String> = emptyList()
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
    val clientCount: Int? = null
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
 * 无线设置数据层：uci get/set/commit + iwinfo 实时数据全部走 ubus（无需 SSH）。
 * 应用变更后调用 netifd 的 network reload 重新加载无线。
 */
class WirelessClient(private val rpc: UbusRpcClient = UbusRpcClient()) {

    private suspend fun call(
        config: RouterConfig,
        target: String,
        method: String,
        params: kotlinx.serialization.json.JsonObject
    ): kotlinx.serialization.json.JsonElement = withContext(Dispatchers.IO) {
        val endpoint = rpc.buildEndpoint(config.ip, config.port, config.useHttps)
        val token = rpc.login(endpoint, config.username, config.password, config.allowInsecureTls)
        rpc.call(endpoint, token, target, method, params, config.allowInsecureTls)
    }

    private suspend fun iwinfo(config: RouterConfig, method: String, device: String): JsonObject? =
        runCatching {
            call(
                config, "iwinfo", method,
                buildJsonObject { put("device", kotlinx.serialization.json.JsonPrimitive(device)) }
            ).jsonObject
        }.getOrNull()

    private fun str(section: JsonObject, key: String): String? =
        (section[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun bool(section: JsonObject, key: String): Boolean =
        (section[key] as? JsonPrimitive)?.content == "1"

    private fun wpaName(wpa: Int): String = when (wpa) {
        1 -> "WPA"
        2 -> "WPA2"
        3 -> "WPA3"
        else -> "WPA"
    }

    /** 读取无线配置（uci）+ iwinfo 实时数据合并。 */
    suspend fun load(config: RouterConfig): List<WirelessRadio> = withContext(Dispatchers.IO) {
        val payload = call(
            config, "uci", "get",
            buildJsonObject { put("config", kotlinx.serialization.json.JsonPrimitive("wireless")) }
        )
        val values = (payload.jsonObject["values"] as? JsonObject) ?: return@withContext emptyList()

        val radios = mutableListOf<WirelessRadio>()
        val ifaces = mutableListOf<WirelessIface>()
        for ((sectionName, el) in values) {
            val sec = el as? JsonObject ?: continue
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
                        ifaces = emptyList()
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
                        disabled = bool(sec, "disabled")
                    )
                )
            }
        }
        if (radios.isEmpty()) return@withContext emptyList()

        // iwinfo 实时数据：radio 级 + 接口级。
        val wifiIfaces = runCatching {
            (iwinfo(config, "devices", "wireless")?.get("devices") as? JsonArray)
                ?.mapNotNull { dev ->
                    ((dev as? JsonObject)?.get("name") as? JsonPrimitive)?.content
                }
                ?: emptyList()
        }.getOrDefault(emptyList())

        radios.map { radio ->
            var r = radio
            iwinfo(config, "info", radio.section)?.let { info ->
                val chan = (info["channel"] as? JsonPrimitive)?.content?.toIntOrNull()
                val txp = (info["txpower"] as? JsonPrimitive)?.content?.toIntOrNull()
                val noise = (info["noise"] as? JsonPrimitive)?.content?.toIntOrNull()
                val htmodes = (info["htmodes"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                r = r.copy(
                    liveChannel = chan,
                    liveTxpower = txp,
                    liveNoise = noise,
                    availableHtmodes = htmodes,
                    liveHwmodesText = str(info, "hwmodes_text")
                )
            }
            val phy = runCatching {
                iwinfo(config, "info", radio.section)?.get("phy")?.let { p -> (p as? JsonPrimitive)?.content }
            }.getOrNull().orEmpty()
            val ifn = wifiIfaces.firstOrNull { it.startsWith("$phy-") }
            if (ifn != null) {
                iwinfo(config, "info", ifn)?.let { info ->
                    val liveB = str(info, "bssid")
                    val liveSsid = str(info, "ssid")
                    val liveMode = str(info, "mode")
                    val signal = (info["signal"] as? JsonPrimitive)?.content?.toIntOrNull()
                    val noise = (info["noise"] as? JsonPrimitive)?.content?.toIntOrNull()
                    val enc = info["encryption"]?.jsonObject ?: kotlinx.serialization.json.buildJsonObject { }
                    val encEnabled = (enc["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                    val wpa = (enc["wpa"] as? JsonPrimitive)?.content?.toIntOrNull()
                    val ciphers = (enc["ciphers"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                    val liveEnc = when {
                        !encEnabled -> "无加密"
                        wpa != null -> "${wpaName(wpa)} (${ciphers.joinToString("/")})"
                        else -> "已加密"
                    }
                    val assoc = runCatching {
                        (call(
                            config, "iwinfo", "assoclist",
                            buildJsonObject { put("device", kotlinx.serialization.json.JsonPrimitive(ifn)) }
                        ).jsonObject["results"] as? JsonArray)?.size
                    }.getOrNull()
                    r = r.copy(ifaces = r.ifaces.map { f ->
                        if (f.device == radio.section) {
                            f.copy(
                                liveIfname = ifn,
                                liveBssid = liveB ?: f.liveBssid,
                                liveSsid = liveSsid ?: f.liveSsid,
                                liveMode = liveMode ?: f.liveMode,
                                liveEncryption = liveEnc,
                                liveSignal = signal,
                                liveNoise = noise,
                                clientCount = assoc
                            )
                        } else f
                    })
                }
            }
            r
        }
    }

    /**
     * 应用变更：逐段 uci set → uci commit wireless → network reload。
     * values 值支持 String（标量）、Boolean（0/1）与 List<String>（uci 列表）。
     */
    suspend fun apply(
        config: RouterConfig,
        changes: Map<String, Map<String, Any>>
    ) = withContext(Dispatchers.IO) {
        for ((section, values) in changes) {
            call(
                config, "uci", "set",
                buildJsonObject {
                    put("config", kotlinx.serialization.json.JsonPrimitive("wireless"))
                    put("section", kotlinx.serialization.json.JsonPrimitive(section))
                    put("values", buildJsonObject {
                        values.forEach { (k, v) ->
                            when (v) {
                                is List<*> -> put(
                                    k, JsonArray(v.map { kotlinx.serialization.json.JsonPrimitive(it.toString()) })
                                )
                                is Boolean -> put(k, kotlinx.serialization.json.JsonPrimitive(if (v) "1" else "0"))
                                else -> put(k, kotlinx.serialization.json.JsonPrimitive(v.toString()))
                            }
                        }
                    })
                }
            )
        }
        commitAndReload(config)
    }

    /** 添加 WiFi 接口（LuCI「添加」按钮）：uci add → commit → network reload。 */
    suspend fun addIface(
        config: RouterConfig,
        device: String,
        ssid: String,
        key: String?,
        encryption: String
    ) = withContext(Dispatchers.IO) {
        call(
            config, "uci", "add",
            buildJsonObject {
                put("config", kotlinx.serialization.json.JsonPrimitive("wireless"))
                put("type", kotlinx.serialization.json.JsonPrimitive("wifi-iface"))
                put("values", buildJsonObject {
                    put("device", kotlinx.serialization.json.JsonPrimitive(device))
                    put("mode", kotlinx.serialization.json.JsonPrimitive("ap"))
                    put("ssid", kotlinx.serialization.json.JsonPrimitive(ssid))
                    put("network", kotlinx.serialization.json.JsonPrimitive("lan"))
                    put("encryption", kotlinx.serialization.json.JsonPrimitive(encryption))
                    if (!key.isNullOrEmpty()) put("key", kotlinx.serialization.json.JsonPrimitive(key))
                })
            }
        )
        commitAndReload(config)
    }

    /** 删除接口段：uci delete → commit → network reload。 */
    suspend fun deleteIface(config: RouterConfig, section: String) = withContext(Dispatchers.IO) {
        call(
            config, "uci", "delete",
            buildJsonObject {
                put("config", kotlinx.serialization.json.JsonPrimitive("wireless"))
                put("section", kotlinx.serialization.json.JsonPrimitive(section))
            }
        )
        commitAndReload(config)
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

    private suspend fun commitAndReload(config: RouterConfig) {
        call(
            config, "uci", "commit",
            buildJsonObject { put("config", kotlinx.serialization.json.JsonPrimitive("wireless")) }
        )
        call(config, "network", "reload", buildJsonObject { })
    }
}

class WirelessApplyException(message: String, cause: Exception? = null) : Exception(message, cause)
