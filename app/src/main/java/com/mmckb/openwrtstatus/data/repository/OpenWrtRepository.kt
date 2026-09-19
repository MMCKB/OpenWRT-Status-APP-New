package com.mmckb.openwrtstatus.data.repository

import com.mmckb.openwrtstatus.data.model.InterfaceInfo
import com.mmckb.openwrtstatus.data.model.LeaseInfo
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.RouterStatus
import com.mmckb.openwrtstatus.data.model.WirelessInfo
import com.mmckb.openwrtstatus.data.remote.UbusRpcClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fetches aggregated router status over the **rpcd ubus** JSON-RPC endpoint.
 *
 * Calls used (all read-only):
 *  - `system board`            → hostname, model, firmware release
 *  - `system info`             → uptime, load average, memory & swap
 *  - `network.interface dump`  → logical interfaces (state, IPv4, uptime, counters)
 *  - `network.device status`   → per-device byte counters (fallback for interface stats)
 *  - `network.wireless status` → radios / SSIDs / connected stations
 *
 * The optional calls degrade gracefully: a failure only adds a warning instead of
 * failing the whole refresh.
 */
class OpenWrtRepository(private val rpc: UbusRpcClient = UbusRpcClient()) {

    suspend fun fetchStatus(config: RouterConfig): RouterStatus {

        val endpoint = rpc.buildEndpoint(config.ip, config.port, config.useHttps)
        val token = rpc.login(endpoint, config.username, config.password, config.allowInsecureTls)

        val board = rpc.call(endpoint, token, "system", "board", EMPTY, config.allowInsecureTls)
        val info = rpc.call(endpoint, token, "system", "info", EMPTY, config.allowInsecureTls)

        val warnings = mutableListOf<String>()

        val dump = runCatching {
            rpc.call(endpoint, token, "network.interface", "dump", EMPTY, config.allowInsecureTls)
        }.onFailure { warnings += "网络接口状态暂不可用。" }.getOrElse { JsonNull }

        val devices = runCatching {
            rpc.call(endpoint, token, "network.device", "status", EMPTY, config.allowInsecureTls)
        }.onFailure { warnings += "设备流量计数暂不可用。" }.getOrElse { JsonNull }

        val wireless = runCatching {
            rpc.call(endpoint, token, "network.wireless", "status", EMPTY, config.allowInsecureTls)
        }.onFailure { warnings += "无线状态暂不可用。" }.getOrElse { JsonNull }

        var result = buildStatus(board, info, dump, devices, wireless, warnings)

        // 部分设备（如 25.x NSS 构建）不提供 network.wireless status，但有 UCI 无线配置：
        // 无线列表为空时回退解析 /etc/config/wireless 的 wifi-iface 段（同旧版方案），
        // 并撤掉「无线状态暂不可用」警告。
        if (result.wireless.isEmpty()) {
            runCatching {
                rpc.call(
                    endpoint,
                    token,
                    "uci",
                    "get",
                    buildJsonObject { put("config", JsonPrimitive("wireless")) },
                    config.allowInsecureTls
                )
            }.getOrNull()?.let { uciPayload ->
                val fallback = readWirelessUciFallback(uciPayload)
                if (fallback.isNotEmpty()) {
                    result = result.copy(
                        wireless = fallback,
                        warnings = result.warnings.filterNot { it == "无线状态暂不可用。" }
                    )
                }
            }
        }

        return result
    }

    /** 从 `uci get wireless` 的配置解析无线接口（只读回退：SSID/开关/接口名）。 */
    private fun readWirelessUciFallback(payload: JsonElement): List<WirelessInfo> {
        val root = payload.obj() ?: return emptyList()
        val values = (root["values"] ?: payload).obj() ?: return emptyList()
        return values.entries.mapNotNull { (sectionName, el) ->
            val section = el.obj() ?: return@mapNotNull null
            val sectionType = (section[".type"] as? JsonPrimitive)?.content
                ?: (section["type"] as? JsonPrimitive)?.content
            val ssid = (section["ssid"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            if (sectionType != "wifi-iface" || ssid == null) return@mapNotNull null
            val disabled = (section["disabled"] as? JsonPrimitive)?.content == "1"
            val name = (section["ifname"] as? JsonPrimitive)?.content
                ?: (section["device"] as? JsonPrimitive)?.content
                ?: (section[".name"] as? JsonPrimitive)?.content
                ?: sectionName
            val channel = (section["channel"] as? JsonPrimitive)?.content ?: "配置"
            WirelessInfo(name = name, ssid = ssid, up = !disabled, channel = channel, clients = null)
        }

    private fun buildStatus(
        board: JsonElement,
        info: JsonElement,
        dump: JsonElement,
        deviceCounters: JsonElement,
        wireless: JsonElement,
        warnings: MutableList<String>
    ): RouterStatus {
        val boardObj = board.obj()
        val infoObj = info.obj()

        val release = boardObj?.get("release").obj()
        val firmware = release?.get("description")?.str()
            ?: release?.get("version")?.str()
            ?: boardObj?.get("release")?.str()

        val memory = infoObj?.get("memory").obj()
        val swap = infoObj?.get("swap").obj()

        val memTotal = memory?.get("total")?.long() ?: 0L
        val memAvailable = listOf("free", "buffered", "cached")
            .mapNotNull { memory?.get(it)?.long() }
            .takeIf { it.isNotEmpty() }
            ?.sum()
            ?: memory?.get("available")?.long()
            ?: memory?.get("free")?.long()
            ?: 0L

        val swapTotal = swap?.get("total")?.long() ?: 0L
        val swapAvailable = swap?.get("free")?.long() ?: 0L

        val counters = readDeviceCounters(deviceCounters)

        return RouterStatus(
            online = true,
            hostname = boardObj?.get("hostname")?.str() ?: "OpenWrt",
            uptimeSeconds = infoObj?.get("uptime")?.long() ?: 0L,
            loadAverage = readLoad(infoObj?.get("load")),
            memoryTotalBytes = memTotal,
            memoryAvailableBytes = memAvailable,
            swapTotalBytes = swapTotal,
            swapAvailableBytes = swapAvailable,
            interfaces = readInterfaces(dump, counters),
            wireless = readWireless(wireless),
            leases = emptyList(),
            firmware = firmware,
            model = boardObj?.get("model")?.str() ?: boardObj?.get("system")?.str(),
            boardName = boardObj?.get("board_name")?.str(),
            cpuInfo = boardObj?.get("system")?.str(),
            kernel = boardObj?.get("kernel")?.str(),
            rootfsType = boardObj?.get("rootfs_type")?.str(),
            distribution = release?.get("distribution")?.str(),
            releaseVersion = release?.get("version")?.str(),
            releaseRevision = release?.get("revision")?.str(),
            target = release?.get("target")?.str(),
            localtime = infoObj?.get("localtime")?.long(),
            rootFsTotalBytes = infoObj?.get("root").obj()?.get("total")?.long() ?: 0L,
            rootFsFreeBytes = infoObj?.get("root").obj()?.get("free")?.long() ?: 0L,
            tmpTotalBytes = infoObj?.get("tmp").obj()?.get("total")?.long() ?: 0L,
            tmpFreeBytes = infoObj?.get("tmp").obj()?.get("free")?.long() ?: 0L,
            warnings = warnings
        )
    }

    /**
     * ubus reports load as either a float (newer builds) or a fixed-point integer scaled
     * by 65535 (older builds). Only divide when the value is clearly scaled.
     */
    private fun readLoad(element: JsonElement?): List<Double> {
        val array = element as? JsonArray ?: return emptyList()
        return array.take(3).mapNotNull { child ->
            val value = child.dbl() ?: return@mapNotNull null
            if (value > 100.0) value / 65535.0 else value
        }
    }

    private fun readDeviceCounters(payload: JsonElement): Map<String, Pair<Long, Long>> {
        val root = payload.obj() ?: return emptyMap()
        return root.mapNotNull { (name, value) ->
            val stats = value.obj()?.get("statistics").obj() ?: value.obj() ?: return@mapNotNull null
            val rx = stats["rx_bytes"]?.long() ?: return@mapNotNull null
            val tx = stats["tx_bytes"]?.long() ?: return@mapNotNull null
            name to (rx to tx)
        }.toMap()
    }

    private fun readInterfaces(
        dump: JsonElement,
        counters: Map<String, Pair<Long, Long>>
    ): List<InterfaceInfo> {
        val root = dump.obj() ?: return emptyList()
        val list = root["interface"] as? JsonArray
            ?: root["interfaces"] as? JsonArray
            ?: return emptyList()

        return list.mapIndexedNotNull { index, raw ->
            val item = raw.obj() ?: return@mapIndexedNotNull null
            val name = item["interface"]?.str() ?: item["name"]?.str() ?: "接口 ${index + 1}"

            val deviceRaw = item["l3_device"] ?: item["device"]
            val device = deviceRaw.obj()?.get("name")?.str() ?: deviceRaw?.str() ?: "—"

            val stats = item["statistics"].obj()
            val counter = counters[device]
            val rx = stats?.get("rx_bytes")?.long()
                ?: item["rx_bytes"]?.long()
                ?: counter?.first
                ?: 0L
            val tx = stats?.get("tx_bytes")?.long()
                ?: item["tx_bytes"]?.long()
                ?: counter?.second
                ?: 0L

            val ipv4 = (item["ipv4-address"] as? JsonArray)
                ?.mapNotNull { entry ->
                    entry.obj()?.get("address")?.str() ?: entry.str()
                }
                ?: emptyList()

            val ipv6 = (item["ipv6-address"] as? JsonArray)
                ?.mapNotNull { entry ->
                    entry.obj()?.get("address")?.str() ?: entry.str()
                }
                ?: emptyList()

            InterfaceInfo(
                name = name,
                device = device,
                up = item["up"]?.bool() ?: false,
                ipv4 = ipv4,
                ipv6 = ipv6,
                uptimeSeconds = item["uptime"]?.long() ?: 0L,
                rxBytes = rx,
                txBytes = tx
            )
        }
    }

    private fun readWireless(payload: JsonElement): List<WirelessInfo> {
        val root = payload.obj() ?: return emptyList()
        // 兼容不同固件的外层包装：radios/wireless/radio 键或直接是 radio map。
        val candidates = listOf(root["radios"], root["wireless"], root["radio"], payload)
        val radiosElement = candidates.firstOrNull { candidate ->
            when (candidate) {
                is JsonArray -> candidate.isNotEmpty()
                is JsonObject -> candidate.isNotEmpty()
                else -> false
            }
        } ?: payload
        val radioEntries: List<Pair<String, JsonElement>> = when (radiosElement) {
            is JsonArray -> radiosElement.mapIndexed { index, value -> "radio$index" to value }
            is JsonObject -> radiosElement.entries.map { it.key to it.value }
            else -> emptyList()
        }
        return radioEntries.flatMap { (radioName, radioValue) ->
            val radio = radioValue.obj() ?: return@flatMap emptyList()
            val radioConfig = radio["config"].obj()
            val rawInterfaces = radio["interfaces"] ?: radio["interface"]
            val entries = (rawInterfaces as? JsonArray)?.toList()
                ?: rawInterfaces.obj()?.values?.toList()
                ?: listOf(radioValue)

            entries.mapIndexedNotNull { index, raw ->
                val item = raw.obj() ?: return@mapIndexedNotNull null
                val config = item["config"].obj()

                val ssid = config?.get("ssid")?.str()
                    ?: item["ssid"]?.str()
                    ?: radioConfig?.get("ssid")?.str()
                    ?: return@mapIndexedNotNull null

                val disabled = item["disabled"]?.truthy()
                    ?: config?.get("disabled")?.truthy()
                    ?: radio["disabled"]?.truthy()
                    ?: radioConfig?.get("disabled")?.truthy()
                    ?: false

                val state = item["up"] ?: item["state"] ?: item["status"] ?: radio["up"] ?: radio["state"]
                val hasConfig = config?.get("mode")?.str() != null || config?.get("ssid") != null
                val up = !disabled && (state?.truthy() ?: hasConfig)

                val stations = item["stations"] as? JsonArray
                val assocArray = item["assoclist"] as? JsonArray
                val assoc = item["assoclist"].obj()
                val clientsArray = item["clients"] as? JsonArray
                val clients = when {
                    stations != null -> stations.size
                    assocArray != null -> assocArray.size
                    assoc != null -> assoc.size
                    clientsArray != null -> clientsArray.size
                    else -> null
                }

                WirelessInfo(
                    name = item["ifname"]?.str() ?: item["name"]?.str() ?: "$radioName·${index + 1}",
                    ssid = ssid,
                    up = up,
                    channel = item["channel"]?.display()
                        ?: radio["channel"]?.display()
                        ?: config?.get("channel")?.display()
                        ?: "自动",
                    clients = clients
                )
            }
        }
    }

    /** Parses `/tmp/dhcp.leases` lines: `<expiry> <mac> <ip> <name> <clientid>`. */
    fun parseLeases(raw: String): List<LeaseInfo> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 4) return@mapNotNull null
                val expires = parts[0].toLongOrNull() ?: return@mapNotNull null
                val mac = parts[1]
                val ip = parts[2]
                val name = parts[3].takeIf { it != "*" && it.isNotBlank() } ?: "未知设备"
                LeaseInfo(mac = mac, ip = ip, name = name, expiresAt = expires)
            }
            .toList()
    }

    // --- Json navigation helpers -------------------------------------------------

    private fun JsonElement?.obj(): JsonObject? =
        this?.takeIf { it is JsonObject }?.jsonObject

    private fun JsonElement?.str(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        val value = primitive.content
        return value.takeIf { it.isNotBlank() && it != "—" }
    }

    private fun JsonElement?.display(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.content
    }

    private fun JsonElement?.long(): Long? =
        (this as? JsonPrimitive)?.content?.toLongOrNull()

    private fun JsonElement?.dbl(): Double? =
        (this as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun JsonElement?.bool(): Boolean? =
        (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun JsonElement?.truthy(): Boolean? {
        val primitive = this as? JsonPrimitive ?: return null
        val content = primitive.content
        content.toBooleanStrictOrNull()?.let { return it }
        return when (content.trim().lowercase()) {
            "1", "yes", "on", "up", "active", "enabled", "running" -> true
            "0", "no", "off", "down", "inactive", "disabled" -> false
            else -> null
        }
    }

    private companion object {
        val EMPTY = buildJsonObject {}
    }
}
