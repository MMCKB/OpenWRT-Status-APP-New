package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** LuCI 系统页（admin/system/system）的完整配置快照。 */
data class SystemData(
    val systemSection: String,
    val hostname: String,
    val description: String?,
    val notes: String?,
    val zonename: String?,
    val timezone: String?,
    val clockTimestyle: Boolean,
    val clockHourcycle: String?,
    val logSize: String?,
    val logIp: String?,
    val logPort: String?,
    val logProto: String?,
    val logFile: String?,
    val conloglevel: String?,
    val cronloglevel: String?,
    val zramSizeMb: String?,
    val zramCompAlgo: String?,
    val ntpSectionExists: Boolean,
    val ntpEnabled: Boolean,
    val ntpProvideServer: Boolean,
    val ntpUseDhcp: Boolean,
    val ntpServers: List<String>,
    val ntpInterface: String?,
    val luciLang: String?,
    val luciTheme: String?,
    val luciTablefilters: Boolean,
    val luciThemes: List<Pair<String, String>>,
    val luciLanguages: List<Pair<String, String>>
)

/** 时区：zonename → tzstring（与 LuCI getTimezones 同源）。 */
data class ZoneEntry(val zone: String, val tzstring: String)

/**
 * 系统管理数据层：uci system/luci 读写 + 时间/时区/服务，全部 ubus（写入 staged），
 * 提交走 SSH（与无线设置一致）；未开 SSH 时退回 ubus uci apply + confirm。
 */
class SystemClient(private val rpc: UbusRpcClient = UbusRpcClient()) {

    companion object {
        /** getTimezones 不可用时的常用时区兜底（标准 OpenWrt tzstring）。 */
        private val FALLBACK_ZONES = listOf(
            ZoneEntry("UTC", "UTC"),
            ZoneEntry("Asia/Shanghai", "CST-8"),
            ZoneEntry("Asia/Hong_Kong", "HKT-8"),
            ZoneEntry("Asia/Taipei", "CST-8"),
            ZoneEntry("Asia/Tokyo", "JST-9"),
            ZoneEntry("Asia/Seoul", "KST-9"),
            ZoneEntry("Asia/Singapore", "SGT-8"),
            ZoneEntry("America/New_York", "EST5EDT,M3.2.0,M11.1.0"),
            ZoneEntry("America/Los_Angeles", "PST8PDT,M3.2.0,M11.1.0"),
            ZoneEntry("Europe/London", "GMT0BST,M3.5.0/1,M10.5.0"),
            ZoneEntry("Europe/Berlin", "CET-1CEST,M3.5.0,M10.5.0/3"),
            ZoneEntry("Australia/Sydney", "AEST-10AEDT,M10.1.0,M4.3.0/3")
        )
    }

    private suspend fun call(
        config: RouterConfig,
        target: String,
        method: String,
        params: kotlinx.serialization.json.JsonObject,
        fast: Boolean = true
    ): kotlinx.serialization.json.JsonElement = withContext(Dispatchers.IO) {
        val endpoint = rpc.buildEndpoint(config.ip, config.port, config.useHttps)
        val token = rpc.login(endpoint, config.username, config.password, config.allowInsecureTls, fast)
        rpc.call(endpoint, token, target, method, params, config.allowInsecureTls, fast)
    }

    private fun str(section: JsonObject, key: String): String? =
        (section[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun bool(section: JsonObject, key: String): Boolean =
        (section[key] as? JsonPrimitive)?.content == "1"

    /** 读取系统页全部配置（uci get system + uci get luci）。 */
    suspend fun load(config: RouterConfig): SystemData? = withContext(Dispatchers.IO) {
        runCatching {
            val sys = call(
                config, "uci", "get",
                buildJsonObject { put("config", JsonPrimitive("system")) }
            ).jsonObject["values"]?.jsonObject ?: return@runCatching null

            var systemSection = ""
            var hostname = ""
            var description: String? = null
            var notes: String? = null
            var zonename: String? = null
            var timezone: String? = null
            var timestyle = false
            var hourcycle: String? = null
            var logSize: String? = null
            var logIp: String? = null
            var logPort: String? = null
            var logProto: String? = null
            var logFile: String? = null
            var conloglevel: String? = null
            var cronloglevel: String? = null
            var zramSize: String? = null
            var zramAlgo: String? = null
            var ntpExists = false
            var ntpEnabled = false
            var ntpProvide = false
            var ntpUseDhcp = false
            var ntpServers: List<String> = emptyList()
            var ntpInterface: String? = null

            for ((name, el) in sys) {
                val sec = el as? JsonObject ?: continue
                when (str(sec, ".type")) {
                    "system" -> if (systemSection.isEmpty()) {
                        systemSection = str(sec, ".name") ?: name
                        hostname = str(sec, "hostname") ?: ""
                        description = str(sec, "description")
                        notes = str(sec, "notes")
                        zonename = str(sec, "zonename")
                        timezone = str(sec, "timezone")
                        timestyle = bool(sec, "clock_timestyle")
                        hourcycle = str(sec, "clock_hourcycle")
                        logSize = str(sec, "log_size")
                        logIp = str(sec, "log_ip")
                        logPort = str(sec, "log_port")
                        logProto = str(sec, "log_proto")
                        logFile = str(sec, "log_file")
                        conloglevel = str(sec, "conloglevel")
                        cronloglevel = str(sec, "cronloglevel")
                        zramSize = str(sec, "zram_size_mb")
                        zramAlgo = str(sec, "zram_comp_algo")
                    }
                    "timeserver" -> if (str(sec, ".name") == "ntp") {
                        ntpExists = true
                        // LuCI 语义：uci 无 enabled 选项时视为启用，仅显式 0 为关闭
                        ntpEnabled = str(sec, "enabled") != "0"
                        ntpProvide = bool(sec, "enable_server")
                        ntpUseDhcp = if (sec.containsKey("use_dhcp")) bool(sec, "use_dhcp") else true
                        ntpServers = (sec["server"] as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.content }
                            ?: str(sec, "server")?.split(Regex("[, ]+"))?.filter { it.isNotEmpty() }
                            ?: emptyList()
                        ntpInterface = str(sec, "interface")
                    }
                }
            }

            // sysntpd 服务是否随系统启用（LuCI 的 NTP 开关判定条件之一）
            val sysntpdRcEnabled = runCatching {
                call(config, "rc", "list", buildJsonObject { put("name", JsonPrimitive("sysntpd")) })
                    .jsonObject["sysntpd"]?.jsonObject?.get("enabled")
                    ?.let { (it as? JsonPrimitive)?.content?.toBooleanStrictOrNull() } ?: true
            }.getOrDefault(true)

            // luci 配置：语言 / 主题 / 表格过滤器
            var luciLang: String? = null
            var luciTheme: String? = null
            var tablefilters = false
            val themes = mutableListOf<Pair<String, String>>()
            val languages = mutableListOf<Pair<String, String>>()
            runCatching {
                val luciPayload = call(
                    config, "uci", "get",
                    buildJsonObject { put("config", JsonPrimitive("luci")) }
                ).jsonObject["values"]?.jsonObject
                for ((name, el) in luciPayload ?: emptyMap<String, kotlinx.serialization.json.JsonElement>()) {
                    val sec = el as? JsonObject ?: continue
                    when (name) {
                        "main" -> {
                            luciLang = str(sec, "lang")
                            luciTheme = str(sec, "mediaurlbase")
                            tablefilters = bool(sec, "tablefilters")
                        }
                        // 语言/主题是命名段（.type=internal），实际选项在键值里：
                        // languages: zh_cn=简体中文…；themes: Aurora=/luci-static/aurora…
                        "languages" -> {
                            for ((k, v) in sec) {
                                if (k.startsWith(".")) continue
                                val title = (v as? JsonPrimitive)?.content ?: continue
                                languages.add(k to title)
                            }
                        }
                        "themes" -> {
                            for ((k, v) in sec) {
                                if (k.startsWith(".")) continue
                                val url = (v as? JsonPrimitive)?.content ?: continue
                                themes.add(url to k)
                            }
                        }
                    }
                }
            }

            SystemData(
                systemSection = systemSection,
                hostname = hostname,
                description = description,
                notes = notes,
                zonename = zonename,
                timezone = timezone,
                clockTimestyle = timestyle,
                clockHourcycle = hourcycle,
                logSize = logSize,
                logIp = logIp,
                logPort = logPort,
                logProto = logProto,
                logFile = logFile,
                conloglevel = conloglevel,
                cronloglevel = cronloglevel,
                zramSizeMb = zramSize,
                zramCompAlgo = zramAlgo,
                ntpSectionExists = ntpExists,
                // LuCI 判定还需 rc 服务启用；rc 不可用时按 uci 值
                ntpEnabled = ntpEnabled && sysntpdRcEnabled,
                ntpProvideServer = ntpProvide,
                ntpUseDhcp = ntpUseDhcp,
                ntpServers = ntpServers,
                ntpInterface = ntpInterface,
                luciLang = luciLang,
                luciTheme = luciTheme,
                luciTablefilters = tablefilters,
                luciThemes = themes,
                luciLanguages = languages
            )
        }.getOrNull()
    }

    /** 时区表（LuCI getTimezones 同源，445 项；失败用内置常用表兜底）。 */
    suspend fun timezones(config: RouterConfig): List<ZoneEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = call(config, "luci", "getTimezones", buildJsonObject { })
            val root = payload.jsonObject
            root.mapNotNull { (zone, el) ->
                val tz = (el as? JsonObject)?.get("tzstring")?.let { (it as? JsonPrimitive)?.content }
                    ?: (el as? JsonPrimitive)?.content
                    ?: return@mapNotNull null
                ZoneEntry(zone, tz)
            }.sortedBy { it.zone }.takeIf { it.isNotEmpty() }
        }.getOrNull() ?: FALLBACK_ZONES
    }

    /** 路器当前时间（epoch 秒）。 */
    suspend fun unixtime(config: RouterConfig): Long? = withContext(Dispatchers.IO) {
        runCatching {
            call(config, "luci", "getUnixtime", buildJsonObject { })
                .jsonObject["result"]?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() }
        }.getOrNull()
    }

    /** 把路由器时钟同步为 [epoch]（手机时间）。 */
    suspend fun setLocaltime(config: RouterConfig, epoch: Long) = withContext(Dispatchers.IO) {
        call(
            config, "luci", "setLocaltime",
            buildJsonObject { put("localtime", JsonPrimitive(epoch)) }
        )
    }

    /**
     * 应用变更：逐段 uci set/delete（staged，[values] 空串=删除选项）后提交。
     * [ssh] 非空走 SSH `uci commit ...` + 服务重载；否则 uci apply+confirm（90 秒窗口）。
     * changes 结构：config → section → option → value；section 需要新建时用
     * `section!name` 形式（如 "system!ntp"）。
     */
    suspend fun apply(
        config: RouterConfig,
        changes: Map<String, Map<String, Map<String, Any>>>,
        ssh: SshConfig? = null,
        onPhase: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (ssh != null) {
            onPhase("正在写入并重载服务…")
            var logTouched = false
            var zramTouched = false
            changes["system"]?.forEach { (_, values) ->
                if (values.keys.any { it.startsWith("log_") || it == "conloglevel" || it == "cronloglevel" }) logTouched = true
                if (values.keys.any { it.startsWith("zram_") }) zramTouched = true
            }
            val ntpTouched = changes["system"]?.containsKey("ntp") == true
            // 与旧版 OpenWRT-Status-APP 一致：uci set/delete + commit + 服务重载在同一条 SSH 脚本内完成
            val script = buildString {
                append(buildShellScript(changes))
                append("uci commit system; ")
                if (changes.containsKey("luci")) append("uci commit luci; ")
                append("/etc/init.d/system reload >/dev/null 2>&1; ")
                if (logTouched) append("/etc/init.d/log restart >/dev/null 2>&1; ")
                if (zramTouched) append("/etc/init.d/zram restart >/dev/null 2>&1; ")
                if (ntpTouched) append("/etc/init.d/sysntpd restart >/dev/null 2>&1; ")
                append("echo __SYS_APPLY_OK__")
            }
            val committed = runCatching {
                SshExec.run(ssh, script, 30_000).contains("__SYS_APPLY_OK__")
            }.getOrDefault(false)
            if (committed) return@withContext
            onPhase("SSH 提交失败，改用 uci apply 提交…")
            applyViaUbus(config, changes.keys.toList(), onPhase)
        } else {
            onPhase("正在写入配置…")
            ubusWrite(config, changes, onPhase)
            applyViaUbus(config, changes.keys.toList(), onPhase)
        }
    }

    /** 把 changes 转成 uci set/delete 的 shell 命令；`section!name` 形式先确保命名段存在。 */
    private fun buildShellScript(changes: Map<String, Map<String, Map<String, Any>>>): String {
        val sb = StringBuilder()
        for ((uciConfig, sections) in changes) {
            for ((sectionSpec, values) in sections) {
                val createName = sectionSpec.takeIf { it.contains('!') }?.substringAfter('!')
                val section = sectionSpec.substringBefore('!')
                val sec = shq(section)
                if (createName != null) {
                    sb.append("uci -q get ").append(uciConfig).append('.').append(sec)
                        .append(" >/dev/null 2>&1 || { S=\$(uci add ").append(uciConfig).append(" timeserver) && uci rename ")
                        .append(uciConfig).append(".\$S=").append(sec).append("; }; ")
                }
                for ((key, value) in values) {
                    val keyQ = shq(key)
                    when {
                        value is String && value.isEmpty() ->
                            sb.append("uci -q delete ").append(uciConfig).append('.').append(sec).append('.').append(keyQ).append("; ")
                        value is String && value.contains('\n') -> {
                            sb.append("uci -q delete ").append(uciConfig).append('.').append(sec).append('.').append(keyQ).append("; ")
                            for (line in value.split('\n').filter { it.isNotBlank() }) {
                                sb.append("uci add_list ").append(uciConfig).append('.').append(sec).append('.').append(keyQ)
                                    .append('=').append(shq(line)).append("; ")
                            }
                        }
                        value is List<*> -> {
                            sb.append("uci -q delete ").append(uciConfig).append('.').append(sec).append('.').append(keyQ).append("; ")
                            for (item in value) {
                                sb.append("uci add_list ").append(uciConfig).append('.').append(sec).append('.').append(keyQ)
                                    .append('=').append(shq(item.toString())).append("; ")
                            }
                        }
                        value is Boolean -> sb.append("uci set ").append(uciConfig).append('.').append(sec).append('.').append(keyQ)
                            .append("='").append(if (value) "1" else "0").append("'; ")
                        else -> sb.append("uci set ").append(uciConfig).append('.').append(sec).append('.').append(keyQ)
                            .append('=').append(shq(value.toString())).append("; ")
                    }
                }
            }
        }
        return sb.toString()
    }

    /** 单引号 shell 转义（' → '\''）。 */
    private fun shq(v: String): String = "'" + v.replace("'", "'\\''") + "'"

    /** 无 SSH 后备：ubus 逐段 uci set/delete（staged）。空字符串值表示删除该选项。 */
    private suspend fun ubusWrite(
        config: RouterConfig,
        changes: Map<String, Map<String, Map<String, Any>>>,
        onPhase: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        for ((uciConfig, sections) in changes) {
            for ((sectionSpec, values) in sections) {
                val createName = sectionSpec.takeIf { it.contains('!') }?.substringAfter('!')
                val section = sectionSpec.substringBefore('!')
                val deletions = values.filterKeys { it.isEmpty() }.keys
                val writes = values.filterNot { it.value is String && (it.value as String).isEmpty() }
                if (createName != null) {
                    runCatching {
                        call(
                            config, "uci", "add",
                            buildJsonObject {
                                put("config", JsonPrimitive(uciConfig))
                                put("type", JsonPrimitive("timeserver"))
                                put("name", JsonPrimitive(createName))
                                put("values", buildJsonObject {
                                    writes.forEach { (k, v) ->
                                        when {
                                            v is List<*> && v.isNotEmpty() -> put(
                                                k, JsonArray(v.map { JsonPrimitive(it.toString()) })
                                            )
                                            v is Boolean -> put(k, JsonPrimitive(if (v) "1" else "0"))
                                            v is String -> if (v.isNotEmpty()) put(k, JsonPrimitive(v))
                                            else -> put(k, JsonPrimitive(v.toString()))
                                        }
                                    }
                                })
                            },
                            fast = true
                        )
                    }
                }
                if (writes.isNotEmpty()) {
                    call(
                        config, "uci", "set",
                        buildJsonObject {
                            put("config", JsonPrimitive(uciConfig))
                            put("section", JsonPrimitive(section))
                            put("values", buildJsonObject {
                                writes.forEach { (k, v) ->
                                    when {
                                        v is List<*> && v.isNotEmpty() -> put(
                                            k, JsonArray(v.map { JsonPrimitive(it.toString()) })
                                        )
                                        v is List<*> -> {}
                                        v is Boolean -> put(k, JsonPrimitive(if (v) "1" else "0"))
                                        v is String && v.contains('\n') -> put(
                                            k, JsonArray(v.split('\n').filter { it.isNotBlank() }
                                                .map { JsonPrimitive(it) })
                                        )
                                        else -> put(k, JsonPrimitive(v.toString()))
                                    }
                                }
                            })
                        },
                        fast = true
                    )
                }
                for (opt in deletions) {
                    call(
                        config, "uci", "delete",
                        buildJsonObject {
                            put("config", JsonPrimitive(uciConfig))
                            put("section", JsonPrimitive(section))
                            put("option", JsonPrimitive(opt))
                        },
                        fast = true
                    )
                }
            }
        }
    }

    /** 无 SSH 后备：ubus uci apply + confirm（90 秒确认窗口）。 */
    private suspend fun applyViaUbus(
        config: RouterConfig,
        configs: List<String>,
        onPhase: (String) -> Unit
    ) {
        onPhase("正在应用并重载服务…")
        try {
            call(
                config, "uci", "apply",
                buildJsonObject {
                    put("timeout", JsonPrimitive(90))
                    put("rollback", JsonPrimitive(true))
                }
            )
        } catch (e: RouterException) {
            if (e.ubusCode != 5) throw e
        }
        delay(1000)
        val deadline = System.currentTimeMillis() + 90_000
        onPhase("等待确认应用（最长 90 秒）…")
        while (true) {
            try {
                call(config, "uci", "confirm", buildJsonObject { }, fast = true)
                return
            } catch (e: RouterException) {
                if (System.currentTimeMillis() >= deadline) {
                    throw RouterException("未能确认应用，配置已被路由器自动还原。", hint = "请等网络恢复后重试。")
                }
                delay(250)
            }
        }
    }
}
