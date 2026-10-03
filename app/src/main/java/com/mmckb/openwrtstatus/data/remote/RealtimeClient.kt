package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            invalidate()
            val retry = session(config)
            rpc.call(retry.endpoint, retry.sid, "luci", "getRealtimeStats", params, config.allowInsecureTls)
        }
        parseRows(res)
    }

    /**
     * 流量采样：不同固件的 getRealtimeStats 流量模式参数名不同（interface / device），
     * 按回退链依次尝试并记住本固件接受的参数名（ubus 代码 2 = 参数不被接受）。
     */
    suspend fun trafficRows(config: RouterConfig, device: String): List<List<Double>> {
        val tried = linkedSetOf<String>()
        trafficParamName?.let { tried.add(it) }
        tried.add("interface")
        tried.add("device")
        var lastError: Exception? = null
        for (name in tried) {
            try {
                val rows = realtimeStats(
                    config, "traffic",
                    buildJsonObject { put(name, JsonPrimitive(device)) }
                )
                trafficParamName = name
                return rows
            } catch (e: RouterException) {
                lastError = e
            }
        }
        throw lastError ?: RouterException("流量数据获取失败。", "路由器不支持流量实时统计。")
    }

    private var trafficParamName: String? = null

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
            if (e is kotlinx.coroutines.CancellationException) throw e
            invalidate()
            val retry = session(config)
            rpc.call(retry.endpoint, retry.sid, "iwinfo", "info", params, config.allowInsecureTls)
        }
        val o = res as? JsonObject ?: return@withContext null
        val signal = (o["signal"] as? JsonPrimitive)?.doubleOrNull
        val noise = (o["noise"] as? JsonPrimitive)?.doubleOrNull
        if (signal != null && noise != null) signal to noise else null
    }

    /** 可选流量的设备列表：network.device status 的全部设备（权威来源，排除 lo）。 */
    suspend fun interfaces(config: RouterConfig): List<String> = withContext(Dispatchers.IO) {
        val s = session(config)
        val res = try {
            rpc.call(s.endpoint, s.sid, "network.device", "status", JsonObject(emptyMap()), config.allowInsecureTls)
        } catch (e: Exception) {
            invalidate()
            val retry = session(config)
            rpc.call(retry.endpoint, retry.sid, "network.device", "status", JsonObject(emptyMap()), config.allowInsecureTls)
        }
        val out = linkedSetOf<String>()
        (res as? JsonObject)?.forEach { (name, _) ->
            if (name.isNotBlank() && name != "lo") out.add(name)
        }
        out.toList()
    }

    /** 无线接口列表：iwinfo devices（权威来源；现代固件接口名为 phy0-ap0 等）。 */
    suspend fun radios(config: RouterConfig): List<String> = withContext(Dispatchers.IO) {
        val s = session(config)
        val res = try {
            rpc.call(s.endpoint, s.sid, "iwinfo", "devices", JsonObject(emptyMap()), config.allowInsecureTls)
        } catch (e: Exception) {
            invalidate()
            val retry = session(config)
            rpc.call(retry.endpoint, retry.sid, "iwinfo", "devices", JsonObject(emptyMap()), config.allowInsecureTls)
        }
        val list = ((res as? JsonObject)?.get("devices") as? JsonArray) ?: JsonArray(emptyList())
        list.mapNotNull { el ->
            ((el as? JsonObject)?.get("name") as? JsonPrimitive)
                ?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
        }
    }

    // ===== SSH 采集（部分固件的 rpcd 会话 ACL 拒绝 iwinfo/network.*，走 SSH 绕开） =====

    /** 全部接口的收发字节数（/proc/net/dev，排除 lo）。 */
    suspend fun trafficSamples(ssh: SshConfig): Map<String, Pair<Long, Long>> =
        withContext(Dispatchers.IO) {
            val out = runCatching { SshExec.run(ssh, "cat /proc/net/dev 2>/dev/null", 10_000) }
                .getOrDefault("")
            val map = linkedMapOf<String, Pair<Long, Long>>()
            out.lineSequence().forEach { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) return@forEach
                val name = line.substring(0, idx).trim()
                if (name.isEmpty() || name == "lo") return@forEach
                val f = line.substring(idx + 1).trim().split(Regex("\\s+"))
                if (f.size >= 9) {
                    val rx = f[0].toLongOrNull() ?: return@forEach
                    val tx = f[8].toLongOrNull() ?: return@forEach
                    map[name] = rx to tx
                }
            }
            map
        }

    /** 可选流量的接口列表（SSH 路径）。 */
    suspend fun interfacesViaSsh(ssh: SshConfig): List<String> =
        trafficSamples(ssh).keys.toList()

    /** 无线接口列表（SSH：ubus iwinfo devices，回退 /sys/class/net）。 */
    suspend fun radiosViaSsh(ssh: SshConfig): List<String> = withContext(Dispatchers.IO) {
        val ubusOut = runCatching {
            SshExec.run(ssh, "ubus -S call iwinfo devices 2>/dev/null", 10_000)
        }.getOrDefault("")
        val fromJson = runCatching {
            (Json.parseToJsonElement(ubusOut.trim()).jsonObject["devices"] as? JsonArray)
                ?.mapNotNull { el ->
                    ((el as? JsonObject)?.get("name") as? JsonPrimitive)
                        ?.takeIf { it !is JsonNull }?.content
                }.orEmpty()
        }.getOrDefault(emptyList())
        if (fromJson.isNotEmpty()) return@withContext fromJson
        runCatching {
            SshExec.run(
                ssh,
                "ls /sys/class/net 2>/dev/null | grep -E '^(wlan[0-9]+|phy[0-9]+-ap[0-9]+)'",
                10_000
            )
        }.getOrDefault("")
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    /** 无线接口信号/噪声（SSH iwinfo info，dBm）。 */
    suspend fun wirelessInfoViaSsh(ssh: SshConfig, device: String): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            val out = runCatching {
                SshExec.run(
                    ssh,
                    "ubus -S call iwinfo info '{\"device\":\"" + device + "\"}' 2>/dev/null",
                    10_000
                )
            }.getOrDefault("")
            val obj = runCatching { Json.parseToJsonElement(out.trim()).jsonObject }.getOrNull()
                ?: return@withContext null
            val signal = (obj["signal"] as? JsonPrimitive)?.doubleOrNull
            val noise = (obj["noise"] as? JsonPrimitive)?.doubleOrNull
            if (signal != null && noise != null) signal to noise else null
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
