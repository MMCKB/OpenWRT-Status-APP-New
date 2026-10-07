package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 一个逻辑接口的完整状态（ubus network.interface dump 条目）。 */
data class IfaceDetail(
    val name: String,
    val proto: String?,
    val up: Boolean,
    val device: String,
    val uptimeSeconds: Long,
    val ipv4: List<String>,
    val ipv6: List<String>,
    val gateways: List<String>,
    val dns: List<String>
)

/** 网络-接口数据层（LuCI admin/network/interfaces 的状态部分，ubus 同源）。 */
class NetworkInterfacesClient(private val rpc: UbusRpcClient = UbusRpcClient()) {

    suspend fun load(config: RouterConfig): List<IfaceDetail> = withContext(Dispatchers.IO) {
        val endpoint = rpc.buildEndpoint(config.ip, config.port, config.useHttps)
        val token = rpc.login(endpoint, config.username, config.password, config.allowInsecureTls)
        val res = rpc.call(endpoint, token, "network.interface", "dump", JsonObject(emptyMap()), config.allowInsecureTls)
        val list = ((res as? JsonObject)?.get("interface") as? JsonArray) ?: JsonArray(emptyList())
        list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["interface"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            IfaceDetail(
                name = name,
                proto = (o["proto"] as? JsonPrimitive)?.content,
                up = o["up"]?.let { (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() } ?: false,
                device = (o["l3_device"] ?: o["device"])?.let { (it as? JsonPrimitive)?.content } ?: "—",
                uptimeSeconds = (o["uptime"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                ipv4 = o.stringList("ipv4-address"),
                ipv6 = o.stringList("ipv6-address"),
                gateways = o.routeGateways(),
                dns = o.stringList("dns-server")
            )
        }.sortedWith(compareByDescending<IfaceDetail> { it.up }.thenBy { it.name })
    }

    private fun JsonObject.stringList(key: String): List<String> =
        ((this[key] as? JsonArray))
            ?.mapNotNull { el -> ((el as? JsonObject)?.get("address") ?: el)?.let { (it as? JsonPrimitive)?.content } }
            ?.filter { !it.isNullOrBlank() }
            ?: emptyList()

    private fun JsonObject.routeGateways(): List<String> =
        ((this["route"] as? JsonArray))
            ?.mapNotNull { r -> ((r as? JsonObject)?.get("nexthop") as? JsonPrimitive)?.content }
            ?.filter { it.isNotBlank() && it != "0.0.0.0" && it != "::" }
            ?: emptyList()
}
