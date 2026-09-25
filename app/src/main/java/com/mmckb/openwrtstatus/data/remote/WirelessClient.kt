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
    val band: String?,
    val disabled: Boolean,
    val ifaces: List<WirelessIface>
)

/** 一个无线接口（wifi-iface 段）。 */
data class WirelessIface(
    val section: String,
    val device: String,
    val ssid: String,
    val key: String?,
    val encryption: String?,
    val hidden: Boolean,
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
                        band = str(sec, "band"),
                        disabled = bool(sec, "disabled"),
                        ifaces = emptyList()
                    )
                )
                "wifi-iface" -> ifaces.add(
                    WirelessIface(
                        section = sectionName,
                        device = str(sec, "device") ?: "",
                        ssid = str(sec, "ssid") ?: "",
                        key = str(sec, "key"),
                        encryption = str(sec, "encryption"),
                        hidden = bool(sec, "hidden"),
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
}

class WirelessApplyException(message: String, cause: Exception? = null) : Exception(message, cause)
