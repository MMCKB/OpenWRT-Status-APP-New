package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder

/** OpenClash 服务的 init.d 状态。 */
data class OpenClashServiceState(
    val present: Boolean,
    val enabled: Boolean,
    val running: Boolean
)

/** /etc/config/openclash 主配置段的常用字段。 */
data class OpenClashSettings(
    val section: String,
    val enable: Boolean,
    val enMode: String,
    val proxyMode: String,
    val coreVersion: String,
    val configPath: String,
    val dashboardPort: Int,
    val dashboardPassword: String,
    val enableRedirectDns: Boolean,
    val intranetAllowed: Boolean,
    val enableUdpProxy: Boolean,
    val disableUdpQuic: Boolean,
    val autoUpdate: Boolean
)

/** 一个订阅（uci `config subscribe` 段）。 */
data class OpenClashSubscribe(val section: String, val name: String, val address: String)

/** Clash 外部控制器的策略组（仅 Selector 可手动切换）。 */
data class ClashProxyGroup(val name: String, val now: String, val options: List<String>)

/**
 * OpenClash 原生数据层：
 * - 状态/UCI/文件走 ubus（rc、uci、file 对象）；
 * - Clash 外部控制器绑定在路由器本机，节点切换与策略组通过 SSH curl 127.0.0.1:9090；
 * - 日志、订阅更新、文件增删走 SSH exec。
 */
class OpenClashClient(private val rpc: UbusRpcClient = UbusRpcClient()) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun call(
        config: RouterConfig,
        target: String,
        method: String,
        params: JsonObject
    ): JsonElement = withContext(Dispatchers.IO) {
        val endpoint = rpc.buildEndpoint(config.ip, config.port, config.useHttps)
        val token = rpc.login(endpoint, config.username, config.password, config.allowInsecureTls)
        rpc.call(endpoint, token, target, method, params, config.allowInsecureTls)
    }

    // --- 服务状态与控制 ----------------------------------------------------------

    suspend fun serviceState(config: RouterConfig): OpenClashServiceState {
        val res = call(config, "rc", "list", EMPTY)
        val entry = (res as? JsonObject)?.get("openclash") as? JsonObject
            ?: return OpenClashServiceState(present = false, enabled = false, running = false)
        return OpenClashServiceState(
            present = true,
            enabled = entry.boolAny("enabled"),
            running = entry.boolAny("running")
        )
    }

    /** init.d 动作：start / stop / restart / enable / disable。 */
    suspend fun serviceAction(config: RouterConfig, action: String) {
        call(
            config, "rc", "init",
            buildJsonObject {
                put("name", JsonPrimitive("openclash"))
                put("action", JsonPrimitive(action))
            }
        )
    }

    // --- 主配置段 ----------------------------------------------------------------

    suspend fun readSettings(config: RouterConfig): OpenClashSettings? {
        val res = call(config, "uci", "get", buildJsonObject { put("config", JsonPrimitive("openclash")) })
        val sections = (res as? JsonObject)?.sectionValues() ?: return null
        val main = sections.firstOrNull { it.second[".type"]?.str() == "openclash" } ?: return null
        val s = main.second
        return OpenClashSettings(
            section = main.first,
            enable = s.boolAny("enable"),
            enMode = s.str("en_mode") ?: "fake-ip",
            proxyMode = s.str("proxy_mode") ?: "rule",
            coreVersion = s.str("core_version") ?: "",
            configPath = s.str("config_path") ?: "",
            dashboardPort = s.str("cn_port")?.toIntOrNull() ?: 9090,
            dashboardPassword = s.str("dashboard_password") ?: "",
            enableRedirectDns = s.boolAny("enable_redirect_dns"),
            intranetAllowed = s.boolAny("intranet_allowed"),
            enableUdpProxy = s.boolAny("enable_udp_proxy"),
            disableUdpQuic = s.boolAny("disable_udp_quic"),
            autoUpdate = s.boolAny("auto_update")
        )
    }

    suspend fun setMainOptions(config: RouterConfig, section: String, values: Map<String, String>) {
        call(
            config, "uci", "set",
            buildJsonObject {
                put("config", JsonPrimitive("openclash"))
                put("section", JsonPrimitive(section))
                put("values", buildJsonObject { values.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
            }
        )
        commit(config)
    }

    suspend fun deleteMainOption(config: RouterConfig, section: String, option: String) {
        call(
            config, "uci", "delete",
            buildJsonObject {
                put("config", JsonPrimitive("openclash"))
                put("section", JsonPrimitive(section))
                put("option", JsonPrimitive(option))
            }
        )
        commit(config)
    }

    // --- 订阅 --------------------------------------------------------------------

    suspend fun listSubscribes(config: RouterConfig): List<OpenClashSubscribe> {
        val res = call(config, "uci", "get", buildJsonObject { put("config", JsonPrimitive("openclash")) })
        val sections = (res as? JsonObject)?.sectionValues() ?: return emptyList()
        return sections.mapNotNull { (name, obj) ->
            if (obj[".type"]?.str() != "subscribe") return@mapNotNull null
            OpenClashSubscribe(
                section = name,
                name = obj.str("name") ?: "未命名订阅",
                address = obj.str("address") ?: ""
            )
        }
    }

    suspend fun addSubscribe(config: RouterConfig, name: String, address: String): String {
        val res = call(
            config, "uci", "add",
            buildJsonObject {
                put("config", JsonPrimitive("openclash"))
                put("type", JsonPrimitive("subscribe"))
                put("values", buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("address", JsonPrimitive(address))
                })
            }
        )
        commit(config)
        return (res as? JsonObject)?.get("section")?.jsonPrimitive?.content.orEmpty()
    }

    suspend fun deleteSubscribe(config: RouterConfig, section: String) {
        call(
            config, "uci", "delete",
            buildJsonObject {
                put("config", JsonPrimitive("openclash"))
                put("section", JsonPrimitive(section))
            }
        )
        commit(config)
    }

    // --- 配置文件 ----------------------------------------------------------------

    suspend fun listConfigFiles(config: RouterConfig): List<String> {
        val res = call(config, "file", "list", buildJsonObject { put("path", JsonPrimitive(CONFIG_DIR)) })
        val entries = (res as? JsonObject)?.get("entries") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return entries.mapNotNull { entry ->
            (entry as? JsonObject)?.get("name")?.str()
        }.filter { it.endsWith(".yaml") || it.endsWith(".yml") }.sorted()
    }

    // --- Clash 外部控制器与日志（SSH） --------------------------------------------

    suspend fun tailLog(ssh: SshConfig, lines: Int = 200): String =
        SshExec.run(ssh, "tail -n $lines ${quote("/tmp/openclash.log")} 2>/dev/null", 20_000)

    /** 读取策略组（仅 Selector）；Clash 未运行或控制器不可达时返回空列表。 */
    suspend fun clashGroups(ssh: SshConfig, port: Int, secret: String): List<ClashProxyGroup> {
        val body = clashGet(ssh, port, secret, "/proxies")
        if (body.isBlank()) return emptyList()
        val proxies = runCatching {
            json.parseToJsonElement(body).jsonObject["proxies"]?.jsonObject
        }.getOrNull() ?: return emptyList()
        return proxies.entries.mapNotNull { (name, el) ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            if (obj.str("type") != "Selector") return@mapNotNull null
            val options = (obj["all"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: return@mapNotNull null
            ClashProxyGroup(name = name, now = obj.str("now") ?: "", options = options)
        }.sortedBy { it.name.lowercase() }
    }

    /** 切换策略组节点；Clash 成功时返回 204 空响应体。 */
    suspend fun clashSelectProxy(
        ssh: SshConfig,
        port: Int,
        secret: String,
        group: String,
        name: String
    ): Boolean {
        val payload = "{\"name\":\"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\"}"
        val output = clashPut(ssh, port, secret, "/proxies/${urlEncodePath(group)}", payload)
        return output.isBlank() || !output.contains("message")
    }

    private suspend fun clashGet(ssh: SshConfig, port: Int, secret: String, path: String): String {
        val auth = secret.takeIf { it.isNotBlank() }
            ?.let { "-H ${quote("Authorization: Bearer $it")} " } ?: ""
        return SshExec.run(ssh, "curl -s --max-time 4 ${auth}${quote("http://127.0.0.1:$port$path")}", 15_000)
    }

    private suspend fun clashPut(
        ssh: SshConfig,
        port: Int,
        secret: String,
        path: String,
        payload: String
    ): String {
        val auth = secret.takeIf { it.isNotBlank() }
            ?.let { "-H ${quote("Authorization: Bearer $it")} " } ?: ""
        return SshExec.run(
            ssh,
            "curl -s --max-time 4 -X PUT ${auth}${quote("http://127.0.0.1:$port$path")} -d ${quote(payload)}",
            15_000
        )
    }

    // --- 小工具 ------------------------------------------------------------------

    private fun JsonObject.sectionValues(): List<Pair<String, JsonObject>> {
        val values = this["values"]?.jsonObject ?: return emptyList()
        return values.entries.mapNotNull { (name, el) ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            name to obj
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.boolAny(key: String): Boolean = when (val v = this[key]) {
        is JsonPrimitive -> v.content.toBooleanStrictOrNull() ?: (v.content == "1")
        else -> false
    }

    private suspend fun commit(config: RouterConfig) {
        call(config, "uci", "commit", buildJsonObject { put("config", JsonPrimitive("openclash")) })
    }

    private fun quote(s: String): String = "'${s.replace("'", "'\\''")}'"

    private fun urlEncodePath(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private companion object {
        const val CONFIG_DIR = "/etc/openclash/config"
        val EMPTY = buildJsonObject {}
    }
}
