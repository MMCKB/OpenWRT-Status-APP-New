package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject

/** 接口运行状态（network.interface dump + network.device status 合并）。 */
data class IfaceDetail(
    val name: String,
    val proto: String?,
    val protoI18n: String?,
    val up: Boolean,
    val device: String,
    val carrier: Boolean?,
    val uptimeSeconds: Long,
    val mac: String?,
    val rxBytes: Long,
    val rxPackets: Long,
    val txBytes: Long,
    val txPackets: Long,
    val ipv4: List<String>,
    val ipv6: List<String>,
    val gateways: List<String>,
    val dns: List<String>,
    val zone: String?,
    val autostart: Boolean,
    val disabled: Boolean
)

/** 接口 UCI 配置（network 段 + 关联 dhcp 段 + 防火墙 zone）。 */
data class IfaceUci(
    val section: String,
    val proto: String,
    val device: String?,
    val auto: Boolean,
    val ipaddr: String?,
    val netmask: String?,
    val gateway: String?,
    val dns: List<String>,
    val peerdns: Boolean?,
    val defaultroute: Boolean?,
    val metric: String?,
    val mtu: String?,
    val pppoeUser: String?,
    val pppoePass: String?,
    val zone: String?,
    val dhcpSection: String?,
    val dhcpIgnore: Boolean?,
    val dhcpStart: String?,
    val dhcpLimit: String?,
    val dhcpLeasetime: String?,
    val dhcpForce: Boolean?,
    val dhcpDynamic: Boolean?
)

/** 可选设备列表项（network.device status + /sys/class/net）。 */
data class NetDeviceChoice(val name: String, val up: Boolean)

/**
 * 网络-接口数据层（LuCI admin/network/network 的复刻）：
 *  - 状态：ubus `network.interface dump` + `network.device status` + `uci get firewall`（zone 配色）；
 *  - 配置：ubus `uci get network/dhcp`，编辑保存走 SSH（uci 写回 + reload，与 LuCI apply 等效）。
 */
class NetworkInterfacesClient(private val rpc: UbusRpcClient = UbusRpcClient()) {

    /** 状态 + 配置合并加载。 */
    suspend fun loadAll(config: RouterConfig): Pair<List<IfaceDetail>, List<IfaceUci>> =
        withContext(Dispatchers.IO) {
            val endpoint = rpc.buildEndpoint(config.ip, config.port, config.useHttps)
            val token = rpc.login(endpoint, config.username, config.password, config.allowInsecureTls, true)

            val dump = runCatching {
                rpc.call(endpoint, token, "network.interface", "dump", JsonObject(emptyMap()), config.allowInsecureTls, true)
            }.getOrNull()
            val devStatus = runCatching {
                rpc.call(endpoint, token, "network.device", "status", JsonObject(emptyMap()), config.allowInsecureTls, true)
            }.getOrNull()
            val netUci = runCatching {
                rpc.call(endpoint, token, "uci", "get", uciParams("network"), config.allowInsecureTls, true)
            }.getOrNull()
            val dhcpUci = runCatching {
                rpc.call(endpoint, token, "uci", "get", uciParams("dhcp"), config.allowInsecureTls, true)
            }.getOrNull()
            val fwUci = runCatching {
                rpc.call(endpoint, token, "uci", "get", uciParams("firewall"), config.allowInsecureTls, true)
            }.getOrNull()

            val details = parseDetails(dump, devStatus, fwUci)
            val ucis = parseUcis(
                valuesJson(netUci), valuesJson(dhcpUci), zoneMap(valuesJson(fwUci))
            )
            details to ucis
        }

    /** SSH 保存接口（uci 写回 + reload；一条脚本，按回显判定成败）。 */
    suspend fun saveIface(
        config: RouterConfig,
        ssh: SshConfig,
        section: String,
        isNew: Boolean,
        values: Map<String, String>,
        dns: List<String>,
        dhcp: Map<String, String>
    ) = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        if (isNew) {
            sb.append("S=$(uci -q add network interface) && uci rename network.\\\$S=").append(shq(section)).append(" && ")
        }
        for ((k, v) in values) {
            if (k == "dns") continue // dns 单独按列表处理
            val key = shq(k)
            if (v.isEmpty()) sb.append("uci -q delete network.").append(shq(section)).append('.').append(key).append("; ")
            else sb.append("uci set network.").append(shq(section)).append('.').append(key)
                .append("='").append(v.replace("'", "'\\''")).append("'; ")
        }
        sb.append("uci -q delete network.").append(shq(section)).append(".dns; ")
        for (line in dns) {
            sb.append("uci add_list network.").append(shq(section)).append(".dns=").append(shq(line)).append("; ")
        }
        sb.append("D=''; ")
            .append("for s in $(uci -q show dhcp | sed -n 's/^\\(.*\\)=dhcp$/\\1/p'); do ")
            .append("[ \"$(uci -q get dhcp.$s.interface)\" = ").append(shq(section)).append(" ] && D=$s; done; ")
        if (dhcp.containsKey("__enabled__")) {
            val enabled = dhcp["__enabled__"] == "1"
            if (enabled) {
                sb.append("[ -z \"\$D\" ] && { D=$(uci -q add dhcp dhcp); uci set dhcp.\$D.interface=").append(shq(section)).append("; }; ")
                for ((k, v) in dhcp) {
                    if (k == "__enabled__") continue
                    if (v.isEmpty()) sb.append("uci -q delete dhcp.\$D.").append(shq(k)).append("; ")
                    else sb.append("uci set dhcp.\$D.").append(shq(k)).append("='").append(v.replace("'", "'\\''")).append("'; ")
                }
                sb.append("uci -q delete dhcp.\$D.ignore; ")
            } else {
                sb.append("[ -z \"\$D\" ] && { D=$(uci -q add dhcp dhcp); uci set dhcp.\$D.interface=").append(shq(section)).append("; }; ")
                sb.append("uci set dhcp.\$D.ignore='1'; ")
            }
        }
        sb.append("uci commit network; uci commit dhcp; ")
        sb.append("/etc/init.d/network reload >/dev/null 2>&1; ")
        sb.append("/etc/init.d/dnsmasq restart >/dev/null 2>&1; ")
        sb.append("echo __NIF_SAVE_OK__")
        val ok = SshExec.run(ssh, sb.toString(), 30_000).contains("__NIF_SAVE_OK__")
        if (!ok) throw RouterException("保存失败：接口配置未能写入。", "请检查 SSH 连接后重试。")
    }

    suspend fun deleteIface(config: RouterConfig, ssh: SshConfig, section: String, ifname: String) =
        withContext(Dispatchers.IO) {
            val script = StringBuilder()
                .append("uci -q delete network.").append(shq(section)).append("; ")
                .append("for s in $(uci -q show dhcp | sed -n 's/^\\(.*\\)=dhcp$/\\1/p'); do ")
                .append("[ \"$(uci -q get dhcp.$s.interface)\" = ").append(shq(ifname)).append(" ] && uci -q delete dhcp.$s; done; ")
                .append("uci commit network; uci commit dhcp; ")
                .append("/etc/init.d/network reload >/dev/null 2>&1; ")
                .append("echo __NIF_DEL_OK__")
            val ok = SshExec.run(ssh, script, 30_000).contains("__NIF_DEL_OK__")
            if (!ok) throw RouterException("删除失败：接口配置未能写入。", "请检查 SSH 连接后重试。")
        }


    /** 仅加载 UCI 配置（编辑弹窗用）。 */
    suspend fun loadUcis(config: RouterConfig): List<IfaceUci> =
        withContext(Dispatchers.IO) {
            val endpoint = rpc.buildEndpoint(config.ip, config.port, config.useHttps)
            val token = rpc.login(endpoint, config.username, config.password, config.allowInsecureTls, true)
            val netUci = runCatching {
                rpc.call(endpoint, token, "uci", "get", uciParams("network"), config.allowInsecureTls, true)
            }.getOrNull()
            val dhcpUci = runCatching {
                rpc.call(endpoint, token, "uci", "get", uciParams("dhcp"), config.allowInsecureTls, true)
            }.getOrNull()
            val fwUci = runCatching {
                rpc.call(endpoint, token, "uci", "get", uciParams("firewall"), config.allowInsecureTls, true)
            }.getOrNull()
            parseUcis(valuesJson(netUci), valuesJson(dhcpUci), parseZoneMap(valuesJson(fwUci)))
        }


    // ---- 解析 ------------------------------------------------------------------

    private fun parseDetails(dump: kotlinx.serialization.json.JsonElement?, devStatus: kotlinx.serialization.json.JsonElement?, fwUci: kotlinx.serialization.json.JsonElement?): List<IfaceDetail> {
        val root = (dump as? JsonObject) ?: return emptyList()
        val list = root["interface"] as? JsonArray ?: JsonArray(emptyList())
        val devs = (devStatus as? JsonObject) ?: JsonObject(emptyMap())
        val zones = parseZoneMap(valuesJson(fwUci))

        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["interface"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val dev = (o["l3_device"] ?: o["device"])?.let { (it as? JsonPrimitive)?.content } ?: ""
            val devObj = devs[dev] as? JsonObject
            val proto = (o["proto"] as? JsonPrimitive)?.content
            val routes = (o["route"] as? JsonArray)?.mapNotNull { r ->
                ((r as? JsonObject)?.get("nexthop") as? JsonPrimitive)?.content
            }?.filter { it.isNotBlank() && it != "0.0.0.0" && it != "::" } ?: emptyList()
            IfaceDetail(
                name = name,
                proto = proto,
                protoI18n = protoI18n(proto),
                up = (o["up"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
                device = dev,
                carrier = devObj?.get("carrier")?.let { (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() },
                uptimeSeconds = (o["uptime"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                mac = devObj?.get("macaddr")?.let { (it as? JsonPrimitive)?.content },
                rxBytes = (devObj?.get("statistics") as? JsonObject)?.let { s ->
                    (s["rx_bytes"] as? JsonPrimitive)?.content?.toLongOrNull() } ?: 0L,
                rxPackets = (devObj?.get("statistics") as? JsonObject)?.let { s ->
                    (s["rx_packets"] as? JsonPrimitive)?.content?.toLongOrNull() } ?: 0L,
                txBytes = (devObj?.get("statistics") as? JsonObject)?.let { s ->
                    (s["tx_bytes"] as? JsonPrimitive)?.content?.toLongOrNull() } ?: 0L,
                txPackets = (devObj?.get("statistics") as? JsonObject)?.let { s ->
                    (s["tx_packets"] as? JsonPrimitive)?.content?.toLongOrNull() } ?: 0L,
                ipv4 = addrList(o, "ipv4-address", withMask = true),
                ipv6 = addrList(o, "ipv6-address", withMask = true),
                gateways = routes,
                dns = o.stringList("dns-server"),
                zone = zones[name],
                autostart = (o["autostart"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
                disabled = false
            )
        }.sortedWith(compareByDescending<IfaceDetail> { it.up }.thenBy { it.name })
    }

    private fun parseUcis(netValues: JsonObject, dhcpValues: JsonObject, zones: Map<String, String>): List<IfaceUci> {
        val out = mutableListOf<IfaceUci>()
        for ((_, el) in netValues.entries) {
            val sec = el as? JsonObject ?: continue
            if (str(sec, ".type") != "interface") continue
            val section = str(sec, ".name") ?: continue
            val dhcpEntry = dhcpValues.entries.firstOrNull { (_, de) ->
                (de as? JsonObject)?.let { str(it, "interface") == section } == true
            }
            val dsec = dhcpEntry?.value as? JsonObject
            out.add(
                IfaceUci(
                    section = section,
                    proto = str(sec, "proto") ?: "none",
                    device = str(sec, "device"),
                    auto = str(sec, "auto") != "0",
                    ipaddr = str(sec, "ipaddr"),
                    netmask = str(sec, "netmask"),
                    gateway = str(sec, "gateway"),
                    dns = (sec["dns"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                        ?: str(sec, "dns")?.split(Regex("[, ]+"))?.filter { it.isNotEmpty() }
                        ?: emptyList(),
                    peerdns = str(sec, "peerdns")?.let { it == "1" },
                    defaultroute = str(sec, "defaultroute")?.let { it != "0" },
                    metric = str(sec, "metric"),
                    mtu = str(sec, "mtu"),
                    pppoeUser = str(sec, "username"),
                    pppoePass = str(sec, "password"),
                    zone = zones[section],
                    dhcpSection = dhcpEntry?.key,
                    dhcpIgnore = dsec?.let { str(it, "ignore") == "1" },
                    dhcpStart = dsec?.let { str(it, "start") },
                    dhcpLimit = dsec?.let { str(it, "limit") },
                    dhcpLeasetime = dsec?.let { str(it, "leasetime") },
                    dhcpForce = dsec?.let { str(it, "force") == "1" },
                    dhcpDynamic = dsec?.let { str(it, "dynamicdhcp") != "0" }
                )
            )
        }
        return out.sortedBy { it.section }
    }

    private fun parseZoneMap(fwValues: JsonObject): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for ((_, el) in fwValues.entries) {
            val sec = el as? JsonObject ?: continue
            if (str(sec, ".type") != "zone") continue
            val zoneName = str(sec, "name") ?: continue
            val networks = (sec["network"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: str(sec, "network")?.split(Regex("[, ]+"))?.filter { it.isNotEmpty() }
                ?: emptyList()
            for (n in networks) map[n] = zoneName
        }
        return map
    }

    private fun protoI18n(proto: String?): String? = when (proto) {
        "static" -> "静态地址"
        "dhcp" -> "DHCP 客户端"
        "none" -> "未配置"
        "pppoe" -> "PPPoE"
        "ppp" -> "PPP"
        "qmi" -> "QMI 蜂窝"
        "ncm" -> "NCM 蜂窝"
        "mbim" -> "MBIM 蜂窝"
        else -> proto
    }

    private fun addrList(o: JsonObject, key: String, withMask: Boolean): List<String> =
        ((o[key] as? JsonArray))?.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val addr = (obj["address"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val mask = (obj["mask"] as? JsonPrimitive)?.content
            if (withMask && !mask.isNullOrBlank()) "$addr/$mask" else addr
        } ?: emptyList()

    private fun JsonObject.stringList(key: String): List<String> =
        ((this[key] as? JsonArray))
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.filter { !it.isNullOrBlank() }
            ?: emptyList()

    private fun str(section: JsonObject, key: String): String? =
        (section[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun uciParams(config: String) = buildJsonObject { put("config", JsonPrimitive(config)) }

    private fun valuesJson(el: kotlinx.serialization.json.JsonElement?): JsonObject =
        ((el as? JsonObject)?.get("values") as? JsonObject) ?: JsonObject(emptyMap())

    private fun shq(v: String): String = "'" + v.replace("'", "'\\''") + "'"
}
