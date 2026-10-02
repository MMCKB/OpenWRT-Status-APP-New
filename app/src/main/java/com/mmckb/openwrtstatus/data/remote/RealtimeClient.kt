package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 实时监控数据层（LuCI admin/status/realtime 的复刻）。
 *
 * 数据源与 LuCI 一致：ubus `luci getRealtimeStats`（rpcd luci 插件提供，老式
 * realtime 页与新式前端均轮询它），负载/流量/连接三模式每次返回约 60 个历史采样
 * （`[时间, 值1, 值2, …]` 行数组）；无线页信号/噪声轮询 `iwinfo info`（同
 * channel_analysis 的 rpc 声明）。
 *
 * ubus 会话按配置缓存（轮询每 2 秒一次，不能每次都登录）；会话失效时自动重登一次。
 */
class RealtimeClient(private val rpc: UbusRpcClient = UbusRpcClient()) {

    private data class Session(val endpoint: String, val sid: String)

    private var cachedSession: Session? = null
    private var cachedKey: String? = null

    private suspend fun session(config: RouterConfig): Session {
        val endpoint = rpc.buildEndpoint(config.ip, config.port, config.useHttps)
        val key = "${config.username}|$endpoint|${config.allowInsecureTls}"
        cachedSession?.takeIf { cachedKey == key }?.let { return it }
        val sid = rpc.login(endpoint, config.username, config.password, config.allowInsecureTls)
        cachedSession = Session(endpoint, sid)
        cachedKey = key
        return cachedSession!!
    }

    private suspend fun invalidate() {
        cachedSession = null
        cachedKey = null
    }

    /**
     * 拉取实时采样行（每行 `[时间, 值…]`，取双精度）。会话失效自动重登一次。
     */
    suspend fun realtimeStats(
        config: RouterConfig,
        mode: String,
        extra: JsonObject = JsonObject(emptyMap())
    ): List<List<Double>> = withContext(Dispatchers.IO) {
        val s = session(config)
        val params = buildJsonObject {
            put("mode", JsonPrimitive(mode))
            extra.forEach { (k, v) -> put(k, v) }
        }
        val res = try {
            rpc.call(s.endpoint, s.sid, "luci", "getRealtimeStats", params, config.allowInsecureTls)
        } catch (e: Exception) {
            invalidate()
            val retry = session(config)
            rpc.call(retry.endpoint, retry.sid, "luci", "getRealtimeStats", params, config.allowInsecureTls)
        }
        parseRows(res)
    }

    /** 本机无线信号/噪声（`iwinfo info`，dBm）。 */
    suspend fun wirelessInfo(
        config: RouterConfig,
        device: String
    ): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val s = session(config)
        val params = buildJsonObject { put("device", JsonPrimitive(device)) }
        val res = try {
            rpc.call(s.endpoint, s.sid, "iwinfo", "info", params, config.allowInsecureTls)
        } catch (e: Exception) {
            invalidate()
            val retry = session(config)
            rpc.call(retry.endpoint, retry.sid, "iwinfo", "info", params, config.allowInsecureTls)
        }
        val o = res as? JsonObject ?: return@withContext null
        val signal = (o["signal"] as? JsonPrimitive)?.doubleOrNull
        val noise = (o["noise"] as? JsonPrimitive)?.doubleOrNull
        if (signal != null && noise != null) signal to noise else null
    }

    /** 可选流量的设备列表（network.interface dump 里的 l3_device/device，去重，排除 lo）。 */
    suspend fun interfaces(config: RouterConfig): List<String> = withContext(Dispatchers.IO) {
        val s = session(config)
        val res = try {
            rpc.call(s.endpoint, s.sid, "network.interface", "dump", JsonObject(emptyMap()), config.allowInsecureTls)
        } catch (e: Exception) {
            invalidate()
            val retry = session(config)
            rpc.call(retry.endpoint, retry.sid, "network.interface", "dump", JsonObject(emptyMap()), config.allowInsecureTls)
        }
        val list = ((res as? JsonObject)?.get("interface") as? JsonArray) ?: JsonArray(emptyList())
        val out = linkedSetOf<String>()
        for (el in list) {
            val o = el as? JsonObject ?: continue
            val dev = (o["l3_device"] ?: o["device"])?.let {
                (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content
            } ?: continue
            if (dev.isNotBlank() && dev != "lo") out.add(dev)
        }
        out.toList()
    }

    /** 无线设备（wlan 接口）列表，用于信号页：取 network.wireless status 的接口 ifname。 */
    suspend fun radios(config: RouterConfig): List<String> = withContext(Dispatchers.IO) {
        val s = session(config)
        val res = try {
            rpc.call(s.endpoint, s.sid, "network.wireless", "status", JsonObject(emptyMap()), config.allowInsecureTls)
        } catch (e: Exception) {
            invalidate()
            val retry = session(config)
            rpc.call(retry.endpoint, retry.sid, "network.wireless", "status", JsonObject(emptyMap()), config.allowInsecureTls)
        }
        val out = linkedSetOf<String>()
        val root = res as? JsonObject
        root?.forEach { (_, radioEl) ->
            val radio = radioEl as? JsonObject ?: return@forEach
            val ifaces = radio["interfaces"] as? JsonArray ?: JsonArray(emptyList())
            for (el in ifaces) {
                val o = el as? JsonObject ?: continue
                val name = o["ifname"]?.let {
                    (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content
                }
                if (!name.isNullOrBlank()) out.add(name)
            }
        }
        out.toList()
    }

    private fun parseRows(res: kotlinx.serialization.json.JsonElement): List<List<Double>> {
        val arr = when (res) {
            is JsonArray -> res
            is JsonObject -> res["result"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return arr.mapNotNull { row ->
            (row as? JsonArray)?.mapNotNull { cell ->
                (cell as? JsonPrimitive)?.doubleOrNull
            }?.takeIf { it.isNotEmpty() }
        }
    }
}
