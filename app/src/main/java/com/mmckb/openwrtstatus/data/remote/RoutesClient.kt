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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 一条活动路由（ip -j route）。 */
data class RouteEntry(
    val iface: String,
    val device: String,
    val dest: String,
    val gateway: String,
    val source: String,
    val metric: String,
    val table: String,
    val protocol: String
)

/** 一条邻居表项（ip -j neigh，ARP/NDP）。 */
data class NeighEntry(
    val ip: String,
    val mac: String,
    val iface: String,
    val info: String
)

/** 一条路由策略规则（ip -j rule）。 */
data class RuleEntry(
    val priority: String,
    val iif: String,
    val src: String,
    val sport: String,
    val action: String,
    val ipproto: String,
    val oif: String,
    val dst: String,
    val dport: String,
    val table: String,
    val info: String
)

/** 路由页完整数据（IPv4 / IPv6 各三张表）。 */
data class RoutingData(
    val v4Neigh: List<NeighEntry>,
    val v4Routes: List<RouteEntry>,
    val v4Rules: List<RuleEntry>,
    val v6Neigh: List<NeighEntry>,
    val v6Routes: List<RouteEntry>,
    val v6Rules: List<RuleEntry>
)

/**
 * 路由表数据层（LuCI admin/status/routesj 的完整复刻，只读）：
 *  - 逻辑接口名映射来自 ubus `network.interface dump`；
 *  - 邻居/路由/规则来自 SSH 执行 `ip -4/-6 -j neigh|route|rule`（与 LuCI 同款命令），
 *    一次连接用标记行切分全部输出，减少往返。
 *  需要设备开启 SSH。
 */
class RoutesClient(private val rpc: UbusRpcClient = UbusRpcClient()) {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private suspend fun call(
        config: RouterConfig,
        target: String,
        method: String,
        params: JsonObject
    ) = withContext(Dispatchers.IO) {
        val endpoint = rpc.buildEndpoint(config.ip, config.port, config.useHttps)
        val token = rpc.login(endpoint, config.username, config.password, config.allowInsecureTls, true)
        rpc.call(endpoint, token, target, method, params, config.allowInsecureTls, true)
    }

    /** 设备名 → 逻辑接口名映射（network.interface dump 的 l3_device/device）。 */
    private suspend fun loadIfaceMap(config: RouterConfig): Map<String, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = call(
                    config, "network.interface", "dump", buildJsonObject { }
                )
                val ifaces = payload.jsonObject["interface"] as? JsonArray ?: JsonArray(emptyList())
                val map = linkedMapOf<String, String>()
                ifaces.forEach { el ->
                    val obj = el as? JsonObject ?: return@forEach
                    val name = (obj["interface"] as? JsonPrimitive)?.content ?: return@forEach
                    listOf("l3_device", "device").forEach { key ->
                        val dev = (obj[key] as? JsonPrimitive)?.content
                        if (!dev.isNullOrBlank() && !map.containsKey(dev)) map[dev] = name
                    }
                }
                map
            }.getOrDefault(emptyMap())
        }

    suspend fun load(config: RouterConfig, ssh: SshConfig?): RoutingData = withContext(Dispatchers.IO) {
        if (ssh == null) {
            throw RouterException(
                "路由表需要 SSH 访问。",
                "请在设备编辑页开启 SSH 后重试。"
            )
        }
        val ifaceMap = loadIfaceMap(config)
        val script = listOf(
            "echo __R4N__", "ip -4 -j neigh show",
            "echo __R4T__", "ip -4 -j route show table all",
            "echo __R4L__", "ip -4 -j rule show",
            "echo __R6N__", "ip -6 -j neigh show",
            "echo __R6T__", "ip -6 -j route show table all",
            "echo __R6L__", "ip -6 -j rule show"
        ).joinToString("; ")
        val out = SshExec.run(ssh, script, 30_000)
        val parts = out.split(Regex("__R[46][NTL]__"))
        fun section(i: Int): String = parts.getOrNull(i + 1)?.trim().orEmpty()
        RoutingData(
            v4Neigh = parseNeighbs(section(0), ifaceMap),
            v4Routes = parseRoutes(section(1), ifaceMap, v6 = false),
            v4Rules = parseRules(section(2)),
            v6Neigh = parseNeighbs(section(3), ifaceMap),
            v6Routes = parseRoutes(section(4), ifaceMap, v6 = true),
            v6Rules = parseRules(section(5))
        )
    }

    private fun parseArray(text: String): List<JsonObject> = runCatching {
        json.parseToJsonElement(text).jsonArray.mapNotNull { it as? JsonObject }
    }.getOrDefault(emptyList())

    /** 解析邻居表：跳过失败项；接口由设备映射；MAC 大写。 */
    private fun parseNeighbs(text: String, ifaceMap: Map<String, String>): List<NeighEntry> =
        parseArray(text).mapNotNull { n ->
            val dst = n["dst"]?.let { (it as? JsonPrimitive)?.content } ?: return@mapNotNull null
            // 与 LuCI 相同：跳过链路本地地址与失败的表项
            if (Regex("^fe[89a-f][0-9a-f]:").containsMatchIn(dst)) return@mapNotNull null
            val state = (n["state"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            if ("FAILED" in state) return@mapNotNull null
            val dev = n["dev"]?.let { (it as? JsonPrimitive)?.content } ?: ""
            val info = buildList {
                n["nud"]?.let { add("NUD: ${(it as? JsonPrimitive)?.content}") }
                if (n.containsKey("proxy")) add("Proxy: 是")
                if (n.containsKey("nomaster")) add("No master: 是")
                n["vrf"]?.let { add("VRF: ${(it as? JsonPrimitive)?.content}") }
            }.joinToString(" · ")
            NeighEntry(
                ip = dst,
                mac = (n["lladdr"] as? JsonPrimitive)?.content?.uppercase() ?: "—",
                iface = ifaceMap[dev] ?: "($dev)",
                info = info
            )
        }

    /** 解析路由表：default 归一为 0.0.0.0/0 或 ::/0，跳过链路本地与多播（IPv6）。 */
    private fun parseRoutes(text: String, ifaceMap: Map<String, String>, v6: Boolean): List<RouteEntry> =
        parseArray(text).mapNotNull { rt ->
            var dest = rt["dst"]?.let { (it as? JsonPrimitive)?.content } ?: return@mapNotNull null
            if (dest == "default") dest = if (v6) "::/0" else "0.0.0.0/0"
            if (v6 && (dest == "fe80::/64" || dest == "ff00::/8")) return@mapNotNull null
            val dev = rt["dev"]?.let { (it as? JsonPrimitive)?.content } ?: ""
            RouteEntry(
                iface = ifaceMap[dev] ?: "($dev)",
                device = dev,
                dest = dest,
                gateway = rt["gateway"]?.let { (it as? JsonPrimitive)?.content } ?: "—",
                source = rt["prefsrc"]?.let { (it as? JsonPrimitive)?.content }
                    ?: rt["from"]?.let { (it as? JsonPrimitive)?.content }
                    ?: "—",
                metric = rt["metric"]?.let { (it as? JsonPrimitive)?.content } ?: "—",
                table = rt["table"]?.let { (it as? JsonPrimitive)?.content } ?: "main",
                protocol = rt["protocol"]?.let { (it as? JsonPrimitive)?.content } ?: "—"
            )
        }

    /** 解析策略规则；附带 not/l3mdev/fwmark 等附加条件说明。 */
    private fun parseRules(text: String): List<RuleEntry> =
        parseArray(text).map { rl ->
            fun f(key: String): String = rl[key]?.let { (it as? JsonPrimitive)?.content } ?: "—"
            val info = buildList {
                rl["not"]?.let { add("Not: 是") }
                rl["nop"]?.let { add("No-op: 是") }
                rl["l3mdev"]?.let { add("L3Mdev: 是") }
                rl["fwmark"]?.let { add("Fwmark: ${(it as? JsonPrimitive)?.content}") }
                rl["to"]?.let { add("To: ${(it as? JsonPrimitive)?.content}") }
                rl["tos"]?.let { add("ToS: ${(it as? JsonPrimitive)?.content}") }
                rl["uidrange"]?.let { add("UID: ${(it as? JsonPrimitive)?.content}") }
                rl["goto"]?.let { add("Goto: ${(it as? JsonPrimitive)?.content}") }
                rl["nat"]?.let { add("NAT: ${(it as? JsonPrimitive)?.content}") }
            }.joinToString(" · ")
            RuleEntry(
                priority = f("priority"),
                iif = f("iif"),
                src = rl["src"]?.let { (it as? JsonPrimitive)?.content }?.let { s ->
                    rl["srclen"]?.let { (it as? JsonPrimitive)?.content }?.let { s + "/" + it } ?: s
                } ?: "任意",
                sport = f("sport"),
                action = f("action"),
                ipproto = rl["ipproto"]?.let { (it as? JsonPrimitive)?.content }
                    ?.substringAfter('-')?.uppercase() ?: "—",
                oif = f("oif"),
                dst = rl["dst"]?.let { (it as? JsonPrimitive)?.content }?.let { d ->
                    rl["dstlen"]?.let { (it as? JsonPrimitive)?.content }?.let { d + "/" + it } ?: d
                } ?: "任意",
                dport = f("dport"),
                table = f("table"),
                info = info
            )
        }
}
