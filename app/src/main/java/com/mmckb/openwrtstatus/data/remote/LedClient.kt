package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 路由器上的一颗物理 LED（/sys/class/leds，来自 ubus luci getLEDs）。 */
data class LedDevice(
    val name: String,
    val triggers: List<String>
)

/** 一个 LED 动作（/etc/config/system 的 led 段，LuCI「LED 配置」页同源）。 */
data class LedAction(
    val section: String,
    /** 空串表示新增动作，提交时由 [LedClient.applyLeds] 生成段名。 */
    val name: String,
    val sysfs: String,
    val trigger: String,
    /** 仅 trigger = none 时有意义（默认状态亮/灭）。 */
    val defaultState: String?,
    /** 仅 trigger = heartbeat 时有意义。 */
    val inverted: Boolean,
    /** 心跳频率（毫秒），基础选项。 */
    val interval: String,
    /** 仅 trigger = timer 时有意义。 */
    val delayon: String,
    val delayoff: String,
    /** 仅 trigger = netdev 时有意义。 */
    val dev: String,
    val mode: List<String>
)

/**
 * LED 配置数据层（LuCI admin/system/leds 的完整复刻）：
 *  - 可用 LED 与触发器列表来自 ubus `luci getLEDs`；
 *  - 配置读写 /etc/config/system 的 led 段（name/sysfs/trigger/interval +
 *    各触发器专属选项 default/inverted/delayon/delayoff/dev/mode）；
 *  - 提交走 SSH（commit system + init.d/system reload，同步执行不伤连接），
 *    无 SSH 时 ubus 暂存 + uci apply+confirm。
 *  触发器切换时，不再适用的专属选项会被删除（LuCI removeIfNoneActive 同款）。
 */
class LedClient(private val rpc: UbusRpcClient = UbusRpcClient()) {

    companion object {
        /** 触发器（与 LuCI led-trigger 插件一一对应）。 */
        val TRIGGERS = listOf(
            "none" to "常灭（Always off）",
            "default-on" to "常亮（Always on）",
            "timer" to "自定义闪烁（Timer）",
            "heartbeat" to "心跳（Heartbeat）",
            "netdev" to "网络设备活动（Netdev）"
        )

        val TRIGGER_DESCRIPTIONS = mapOf(
            "none" to "LED 保持默认关闭状态。",
            "default-on" to "LED 保持默认开启状态。",
            "timer" to "LED 按设定的亮/灭频率闪烁。",
            "heartbeat" to "LED 模拟心跳闪烁，频率与 1 分钟平均负载成正比。",
            "netdev" to "LED 随所配置设备的连接状态与收发活动闪烁。"
        )

        /** netdev 触发模式（LuCI netdev.js 的 MultiValue）。 */
        val NETDEV_MODES = listOf(
            "link" to "链接激活",
            "link_10" to "10M 链接",
            "link_100" to "100M 链接",
            "link_1000" to "1G 链接",
            "link_2500" to "2.5G 链接",
            "link_5000" to "5G 链接",
            "link_10000" to "10G 链接",
            "half_duplex" to "半双工",
            "full_duplex" to "全双工",
            "tx" to "发送活动",
            "rx" to "接收活动"
        )
    }

    private suspend fun call(
        config: RouterConfig,
        target: String,
        method: String,
        params: JsonObject,
        fast: Boolean = true
    ): JsonElement = withContext(Dispatchers.IO) {
        val endpoint = rpc.buildEndpoint(config.ip, config.port, config.useHttps)
        val token = rpc.login(endpoint, config.username, config.password, config.allowInsecureTls, fast)
        rpc.call(endpoint, token, target, method, params, config.allowInsecureTls, fast)
    }

    private suspend fun callWithCode(
        config: RouterConfig,
        target: String,
        method: String,
        params: JsonObject,
        fast: Boolean = true
    ): Pair<Int, JsonElement?> = try {
        0 to call(config, target, method, params, fast)
    } catch (e: RouterException) {
        if (e.ubusCode != null) e.ubusCode to null else throw e
    }

    private fun str(section: JsonObject, key: String): String? =
        (section[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun shq(v: String): String = "'" + v.replace("'", "'\\''") + "'"

    /** 可用 LED 及其支持的触发器（luci getLEDs）。 */
    suspend fun loadLeds(config: RouterConfig): List<LedDevice> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = call(config, "luci", "getLEDs", buildJsonObject { })
            payload.jsonObject.entries.mapNotNull { (name, el) ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val triggers = (obj["triggers"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?: emptyList()
                LedDevice(name, triggers)
            }.sortedBy { it.name }
        }.getOrDefault(emptyList())
    }

    /** 读取全部 LED 动作（/etc/config/system 的 led 段）。 */
    suspend fun loadLedActions(config: RouterConfig): List<LedAction> = withContext(Dispatchers.IO) {
        val payload = call(
            config, "uci", "get",
            buildJsonObject { put("config", JsonPrimitive("system")) }
        )
        val values = payload.jsonObject["values"]?.jsonObject
            ?: return@withContext emptyList()
        values.mapNotNull { (_, el) ->
            val sec = el as? JsonObject ?: return@mapNotNull null
            if (str(sec, ".type") != "led") return@mapNotNull null
            val section = str(sec, ".name") ?: return@mapNotNull null
            LedAction(
                section = section,
                name = str(sec, "name") ?: "",
                sysfs = str(sec, "sysfs") ?: "",
                trigger = str(sec, "trigger") ?: "none",
                defaultState = str(sec, "default"),
                inverted = str(sec, "inverted") == "1",
                interval = str(sec, "interval") ?: "",
                delayon = str(sec, "delayon") ?: "",
                delayoff = str(sec, "delayoff") ?: "",
                dev = str(sec, "dev") ?: "",
                mode = when (val m = sec["mode"]) {
                    is JsonArray -> m.mapNotNull { (it as? JsonPrimitive)?.content }
                    is JsonPrimitive -> listOf(m.content)
                    else -> emptyList()
                }
            )
        }
    }

    /** 网络设备列表（netdev 触发器的「设备」选择，来自 network.device status）。 */
    suspend fun listNetdevDevices(config: RouterConfig): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = call(
                config, "network.device", "status",
                buildJsonObject { }, fast = true
            )
            payload.jsonObject.keys.sorted()
        }.getOrDefault(emptyList())
    }

    /**
     * 应用全部 LED 动作（全量写）与删除列表。
     * SSH 可用时一条脚本完成 add/rename/set/delete + commit + system reload；
     * 无 SSH 时 ubus 暂存 + uci apply {rollback} + confirm。
     */
    suspend fun applyLeds(
        config: RouterConfig,
        actions: List<LedAction>,
        deletedSections: List<String>,
        ssh: SshConfig? = null,
        onPhase: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val existing = actions.map { it.section }.filter { it.isNotBlank() }.toSet()
        val named = actions.mapIndexed { idx, act ->
            if (act.section.isBlank()) {
                act.copy(section = "app" + java.lang.Long.toString(System.currentTimeMillis(), 36) + "n" + idx)
            } else act
        }
        val newSections = named.map { it.section }.filter { it !in existing }.toSet()
        if (ssh != null) {
            onPhase("正在写入 LED 配置…")
            val script = buildString {
                deletedSections.forEach { sec ->
                    append("uci -q delete system.").append(shq(sec)).append("; ")
                }
                named.forEach { act ->
                    if (act.section in newSections) {
                        append("S=$(uci add system led) && uci rename system.$S=")
                            .append(shq(act.section)).append(" && ")
                    }
                    append(ledSetScript(act))
                }
                append("uci commit system; ")
                append("/etc/init.d/system reload >/dev/null 2>&1; ")
                append("echo __LED_APPLY_OK__")
            }
            val ok = runCatching {
                SshExec.run(ssh, script, 30_000).contains("__LED_APPLY_OK__")
            }.getOrDefault(false)
            if (ok) return@withContext
            onPhase("SSH 提交失败，改用 uci apply 提交…")
            ubusLeds(config, named, deletedSections, newSections)
            applyViaUbus(config, onPhase)
        } else {
            onPhase("正在写入配置…")
            ubusLeds(config, named, deletedSections, newSections)
            applyViaUbus(config, onPhase)
        }
    }

    /** 单个 LED 动作的 uci set/delete 脚本（非当前触发器的专属选项会被删除）。 */
    private fun ledSetScript(act: LedAction): String {
        val sec = shq(act.section)
        val sb = StringBuilder()
        fun set(opt: String, value: String) {
            sb.append("uci set system.").append(sec).append('.').append(shq(opt))
                .append("='").append(value.replace("'", "'\\''")).append("'; ")
        }
        fun del(opt: String) {
            sb.append("uci -q delete system.").append(sec).append('.').append(shq(opt)).append("; ")
        }
        if (act.name.isNotBlank()) set("name", act.name.trim()) else del("name")
        if (act.sysfs.isNotBlank()) set("sysfs", act.sysfs.trim()) else del("sysfs")
        set("trigger", act.trigger)
        if (act.trigger == "none") set("default", if (act.defaultState == "1") "1" else "0") else del("default")
        if (act.trigger == "heartbeat" && act.inverted) set("inverted", "1") else del("inverted")
        if (act.interval.isNotBlank()) set("interval", act.interval.trim()) else del("interval")
        if (act.trigger == "timer") {
            if (act.delayon.isNotBlank()) set("delayon", act.delayon.trim()) else del("delayon")
            if (act.delayoff.isNotBlank()) set("delayoff", act.delayoff.trim()) else del("delayoff")
        } else {
            del("delayon")
            del("delayoff")
        }
        if (act.trigger == "netdev") {
            if (act.dev.isNotBlank()) set("dev", act.dev.trim()) else del("dev")
            if (act.mode.isNotEmpty()) set("mode", act.mode.joinToString(" ")) else del("mode")
        } else {
            del("dev")
            del("mode")
        }
        return sb.toString()
    }

    /** 无 SSH 后备：ubus 逐段 uci add/delete/set（staged）。 */
    private suspend fun ubusLeds(
        config: RouterConfig,
        named: List<LedAction>,
        deletedSections: List<String>,
        newSections: Set<String>
    ) {
        deletedSections.forEach { sec ->
            runCatching {
                call(config, "uci", "delete", buildJsonObject {
                    put("config", JsonPrimitive("system"))
                    put("section", JsonPrimitive(sec))
                })
            }
        }
        named.forEach { act ->
            if (act.section in newSections) {
                call(config, "uci", "add", buildJsonObject {
                    put("config", JsonPrimitive("system"))
                    put("type", JsonPrimitive("led"))
                    put("name", JsonPrimitive(act.section))
                })
            }
            call(config, "uci", "set", buildJsonObject {
                put("config", JsonPrimitive("system"))
                put("section", JsonPrimitive(act.section))
                put("values", buildJsonObject {
                    if (act.name.isNotBlank()) put("name", JsonPrimitive(act.name.trim()))
                    if (act.sysfs.isNotBlank()) put("sysfs", JsonPrimitive(act.sysfs.trim()))
                    put("trigger", JsonPrimitive(act.trigger))
                    if (act.trigger == "none") {
                        put("default", JsonPrimitive(if (act.defaultState == "1") "1" else "0"))
                    }
                    if (act.trigger == "heartbeat" && act.inverted) {
                        put("inverted", JsonPrimitive("1"))
                    }
                    if (act.interval.isNotBlank()) put("interval", JsonPrimitive(act.interval.trim()))
                    if (act.trigger == "timer") {
                        if (act.delayon.isNotBlank()) put("delayon", JsonPrimitive(act.delayon.trim()))
                        if (act.delayoff.isNotBlank()) put("delayoff", JsonPrimitive(act.delayoff.trim()))
                    }
                    if (act.trigger == "netdev") {
                        if (act.dev.isNotBlank()) put("dev", JsonPrimitive(act.dev.trim()))
                        if (act.mode.isNotEmpty()) {
                            put("mode", JsonArray(act.mode.map { JsonPrimitive(it) }))
                        }
                    }
                })
            })
            // 非当前触发器的专属选项与空值选项删除
            val deletes = mutableListOf<String>()
            if (act.name.isBlank()) deletes.add("name")
            if (act.sysfs.isBlank()) deletes.add("sysfs")
            if (act.trigger != "none") deletes.add("default")
            if (!(act.trigger == "heartbeat" && act.inverted)) deletes.add("inverted")
            if (act.interval.isBlank()) deletes.add("interval")
            if (act.trigger != "timer") {
                deletes.add("delayon")
                deletes.add("delayoff")
            }
            if (act.trigger != "netdev") {
                deletes.add("dev")
                deletes.add("mode")
            } else {
                if (act.dev.isBlank()) deletes.add("dev")
                if (act.mode.isEmpty()) deletes.add("mode")
            }
            deletes.forEach { opt ->
                runCatching {
                    call(config, "uci", "delete", buildJsonObject {
                        put("config", JsonPrimitive("system"))
                        put("section", JsonPrimitive(act.section))
                        put("option", JsonPrimitive(opt))
                    })
                }
            }
        }
    }

    /** 无 SSH 后备：ubus uci apply + confirm（90 秒确认窗口）。 */
    private suspend fun applyViaUbus(config: RouterConfig, onPhase: (String) -> Unit) {
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
