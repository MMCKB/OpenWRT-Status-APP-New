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
    val disabled: Boolean,
    val ifaces: List<WirelessIface>
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
    val disabled: Boolean
)

/**
 * 无线设置数据层：全部通过 ubus 的 uci get/set/commit 完成（无需 SSH），
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

    private fun str(section: JsonObject, key: String): String? =
        (section[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun bool(section: JsonObject, key: String): Boolean =
        (section[key] as? JsonPrimitive)?.content == "1"

    /** 读取无线配置：radio 按配置顺序，接口挂到所属 radio 下。 */
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
                        disabled = bool(sec, "disabled")
                    )
                )
            }
        }
        if (radios.isEmpty()) return@withContext emptyList()
        radios.map { radio ->
            radio.copy(ifaces = ifaces.filter { it.device == radio.section })
        }
    }

    /**
     * 应用变更：逐段 uci set → uci commit wireless → network reload。
     * [changes] 的 key 是 section 名，value 是该段要写入的选项。
     * network reload 失败时抛出异常并提示手动重载。
     */
    suspend fun apply(
        config: RouterConfig,
        changes: Map<String, Map<String, String>>
    ) = withContext(Dispatchers.IO) {
        for ((section, values) in changes) {
            call(
                config, "uci", "set",
                buildJsonObject {
                    put("config", kotlinx.serialization.json.JsonPrimitive("wireless"))
                    put("section", kotlinx.serialization.json.JsonPrimitive(section))
                    put("values", buildJsonObject { values.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) } })
                }
            )
        }
        call(
            config, "uci", "commit",
            buildJsonObject { put("config", kotlinx.serialization.json.JsonPrimitive("wireless")) }
        )
        try {
            call(config, "network", "reload", buildJsonObject { })
        } catch (e: Exception) {
            throw WirelessApplyException("配置已写入但重载无线失败，请在路由器上执行「wifi reload」。", e)
        }
    }

    /** 添加 WiFi 接口（LuCI「添加 Wi-Fi 接口」）：uci add → commit → network reload。 */
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
        call(
            config, "uci", "commit",
            buildJsonObject { put("config", kotlinx.serialization.json.JsonPrimitive("wireless")) }
        )
        call(config, "network", "reload", buildJsonObject { })
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
        call(
            config, "uci", "commit",
            buildJsonObject { put("config", kotlinx.serialization.json.JsonPrimitive("wireless")) }
        )
        call(config, "network", "reload", buildJsonObject { })
    }
}

class WirelessApplyException(message: String, cause: Exception? = null) : Exception(message, cause)
