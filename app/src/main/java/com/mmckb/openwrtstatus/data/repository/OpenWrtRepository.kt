package com.mmckb.openwrtstatus.data.repository

import com.mmckb.openwrtstatus.data.model.DeviceInfo
import com.mmckb.openwrtstatus.data.model.InterfaceStat
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.RouterStatus
import com.mmckb.openwrtstatus.data.remote.LuciRpcClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fetches aggregated router status via the LuCI JSON-RPC API.
 *
 * Exposed LuCI RPC methods used here:
 *  - `sys.system.info`  → hostname, load average, memory & swap
 *  - `sys.uptime`       → seconds since boot
 *  - `sys.net.arp`      → connected devices (IP/MAC/interface)
 *  - `sys.net.deviceinfo` → per-interface cumulative rx/tx bytes
 */
class OpenWrtRepository(private val rpc: LuciRpcClient = LuciRpcClient()) {

    suspend fun fetchStatus(config: RouterConfig): RouterStatus {
        if (config.useMock) return MockData.sample()

        val scheme = if (config.useHttps) "https" else "http"
        val baseUrl = "$scheme://${config.ip}:${config.port}"
        val token = rpc.login(baseUrl, config.username, config.password)

        val systemInfo = rpc.call(baseUrl, token, "sys", "system.info")
        val arp = rpc.call(baseUrl, token, "sys", "net.arp")
        val deviceInfo = rpc.call(baseUrl, token, "sys", "net.deviceinfo")
        val uptime = runCatching { rpc.call(baseUrl, token, "sys", "uptime") }.getOrElse { JsonNull }

        return parse(systemInfo, arp, deviceInfo, uptime)
    }

    private fun parse(
        systemInfo: JsonElement,
        arp: JsonElement,
        deviceInfo: JsonElement,
        uptime: JsonElement
    ): RouterStatus {
        val info = systemInfo.jsonObject
        val hostname = info["hostname"]
            ?.takeIf { it !is JsonNull }
            ?.jsonPrimitive?.content ?: "OpenWrt"

        // ubus reports load average scaled by 65536; divide to get the familiar value.
        val load = info["load"]?.let { el ->
            if (el is JsonArray) el.mapNotNull { if (it is JsonNull) null else runCatching { it.jsonPrimitive.double }.getOrNull() }
            else emptyList()
        }?.map { it / 65536.0 } ?: emptyList()

        val mem = info["memory"]?.takeIf { it is JsonObject }?.jsonObject
        val swap = info["swap"]?.takeIf { it is JsonObject }?.jsonObject
        val memTotal = mem?.get("total").toLongOrNull() ?: 0L
        val memFree = mem?.get("free").toLongOrNull() ?: 0L
        val swapTotal = swap?.get("total").toLongOrNull() ?: 0L
        val swapFree = swap?.get("free").toLongOrNull() ?: 0L

        val uptimeSeconds = if (uptime !is JsonNull) {
            uptime.toLongOrNull() ?: 0L
        } else {
            info["uptime"]?.takeIf { it !is JsonNull }?.toLongOrNull() ?: 0L
        }

        val firmware = info["release"]?.takeIf { it is JsonObject }
            ?.jsonObject?.get("version")
            ?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        val model = info["model"]
            ?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

        return RouterStatus(
            online = true,
            hostname = hostname,
            uptimeSeconds = uptimeSeconds,
            loadAverage = load,
            memoryTotalBytes = memTotal,
            memoryFreeBytes = memFree,
            swapTotalBytes = swapTotal,
            swapFreeBytes = swapFree,
            devices = parseArp(arp),
            interfaces = parseDeviceInfo(deviceInfo),
            firmware = firmware,
            model = model
        )
    }

    private fun parseArp(arp: JsonElement): List<DeviceInfo> {
        if (arp !is JsonArray) return emptyList()
        return arp.mapNotNull { el ->
            if (el !is JsonObject) return@mapNotNull null
            val ip = pick(el, "IP", "ip") ?: return@mapNotNull null
            val mac = pick(el, "MAC", "mac") ?: "—"
            val iface = pick(el, "DEVICE", "device", "iface", "INTERFACE")
            val name = pick(el, "NAME", "name")
            DeviceInfo(ip, mac, iface, name)
        }
    }

    private fun parseDeviceInfo(deviceInfo: JsonElement): List<InterfaceStat> {
        if (deviceInfo !is JsonObject) return emptyList()
        return deviceInfo.mapNotNull { (name, value) ->
            if (value !is JsonObject) return@mapNotNull null
            val rx = value["rx_bytes"].toLongOrNull() ?: value["rx"].toLongOrNull() ?: 0L
            val tx = value["tx_bytes"].toLongOrNull() ?: value["tx"].toLongOrNull() ?: 0L
            InterfaceStat(name, rx, tx)
        }
    }

    private fun pick(obj: JsonObject, vararg keys: String): String? {
        for (k in keys) {
            val v = obj[k] ?: obj[k.lowercase()] ?: obj[k.uppercase()]
            if (v != null && v !is JsonNull) return v.jsonPrimitive.content
        }
        return null
    }

    private fun JsonElement?.toLongOrNull(): Long? {
        val el = this ?: return null
        if (el is JsonNull) return null
        return runCatching { el.jsonPrimitive.long }.getOrNull()
            ?: runCatching { el.jsonPrimitive.content.toLongOrNull() }.getOrNull()
    }
}
