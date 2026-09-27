package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import com.mmckb.openwrtstatus.data.ssh.SshFiles
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 一个 Dropbear SSH 服务实例（LuCI「SSH 访问」页的 uci dropbear 段）。 */
data class DropbearInstance(
    val section: String,
    /** 空串表示新增实例，提交时由 [AdminClient.applyDropbear] 生成段名。 */
    val enabled: Boolean,
    val directBind: Boolean,
    val directInterface: String,
    val interface_: String,
    val port: String,
    val passwordAuth: Boolean,
    val rootPasswordAuth: Boolean,
    val gatewayPorts: Boolean
)

/** LuCI「SSH 密钥」页展示用的公钥解析结果（与 LuCI SSHPubkeyDecoder 同源）。 */
data class SshPublicKey(
    val source: String,
    val kind: String,
    val bits: Int?,
    val curve: String?,
    val comment: String,
    val options: List<String>,
    val displayKey: String
)

/** 一个软件包仓库公钥（/etc/apk/keys 或 /etc/opkg/keys 下的文件）。 */
data class RepoPublicKey(
    val filename: String,
    val content: String,
    val protected: Boolean
)

/**
 * 管理权数据层（LuCI admin/system/admin 的完整复刻），五个部分：
 *  - 路由器密码：ubus `luci setPassword`（与 LuCI password.js 同一调用）；
 *  - SSH 访问：uci dropbear 段读写（enable/_direct/DirectInterface/Interface/Port/
 *    PasswordAuth/RootPasswordAuth/GatewayPorts），提交走 SSH commit+重启，无 SSH
 *    时 ubus 暂存 + uci apply+confirm；
 *  - SSH 密钥：/etc/dropbear/authorized_keys 整文件读写（ubus file write 优先，
 *    ACL 拒绝时走 SSH 上传——部分固件的 rpcd 登录 ACL 不放行 file write）；
 *  - HTTP(S) 访问：uci uhttpd.main.redirect_https；
 *  - 仓库公钥：/etc/apk/keys（opkg 固件为 /etc/opkg/keys）列出/添加/删除，
 *    受保护键（openwrt-* / openwrt-snapshots / d310c6f2833e97f7）不可删除。
 */
class AdminClient(private val rpc: UbusRpcClient = UbusRpcClient()) {

    companion object {
        /** LuCI repokeys.js 的 safeList + openwrt 版本前缀保护规则。 */
        fun isProtectedRepoKey(filename: String): Boolean {
            if (filename == "d310c6f2833e97f7" || filename == "openwrt-snapshots.pem") return true
            val lowered = filename.lowercase()
            if (lowered.replaceFirst(Regex("^openwrt-[0-9]+\\.[0-9]+"), "") != filename) return true
            if (lowered.replaceFirst(Regex("^openwrt-snapshots"), "") != filename) return true
            return false
        }

        /** LuCI repokeys.js 的 isPemFormat。 */
        fun isValidPem(content: String): Boolean =
            Regex("-BEGIN ([A-Z ]+)?PUBLIC KEY-").containsMatchIn(content)
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

    /** 返回 (ubus 状态码, result[1])；status 非 0 时不抛异常，供逐级回退判断。 */
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

    // ===== 路由器密码（LuCI password.js：luci.setPassword） =====

    /**
     * 修改管理员密码。[username] 固定 root（LuCI 同款）。返回 true 表示系统接受；
     * false 表示 passwd 拒绝（如密码过弱/过短）。
     */
    suspend fun changePassword(config: RouterConfig, username: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            val payload = call(
                config, "luci", "setPassword",
                buildJsonObject {
                    put("username", JsonPrimitive(username))
                    put("password", JsonPrimitive(password))
                }
            )
            (payload.jsonObject["result"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        }

    // ===== SSH 访问（uci dropbear） =====

    suspend fun loadDropbear(config: RouterConfig): List<DropbearInstance> = withContext(Dispatchers.IO) {
        val payload = call(
            config, "uci", "get",
            buildJsonObject { put("config", JsonPrimitive("dropbear")) }
        )
        val values = payload.jsonObject["values"]?.jsonObject
            ?: return@withContext emptyList()
        values.mapNotNull { (_, el) ->
            val sec = el as? JsonObject ?: return@mapNotNull null
            if (str(sec, ".type") != "dropbear") return@mapNotNull null
            val name = str(sec, ".name") ?: return@mapNotNull null
            DropbearInstance(
                section = name,
                enabled = str(sec, "enable") != "0",
                directBind = str(sec, "_direct") == "1",
                directInterface = str(sec, "DirectInterface") ?: "",
                interface_ = str(sec, "Interface") ?: "",
                port = str(sec, "Port") ?: "",
                passwordAuth = str(sec, "PasswordAuth") != "off",
                rootPasswordAuth = str(sec, "RootPasswordAuth") != "off",
                gatewayPorts = str(sec, "GatewayPorts") == "on"
            )
        }
    }

    /** 路由器上的逻辑接口（/etc/config/network），供「绑定接口」选择；空值 = 全部接口。 */
    suspend fun listNetworks(config: RouterConfig): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = call(
                config, "uci", "get",
                buildJsonObject { put("config", JsonPrimitive("network")) }
            )
            val values = payload.jsonObject["values"]?.jsonObject
                ?: return@runCatching emptyList()
            values.mapNotNull { (name, el) ->
                val sec = el as? JsonObject ?: return@mapNotNull null
                if (str(sec, ".type") != "interface") return@mapNotNull null
                (str(sec, ".name") ?: name).takeUnless { it == "loopback" }
            }.sorted()
        }.getOrDefault(emptyList())
    }

    /**
     * 应用全部 Dropbear 实例（全量写）与删除列表。
     * SSH 可用时一条脚本完成 add/rename/set/delete + commit + 延时重启 dropbear
     * （restart 会断开经由 dropbear 的本会话，故放入后台延时执行并先回显标记）；
     * 无 SSH 时 ubus 暂存 + uci apply {rollback} + confirm。
     */
    suspend fun applyDropbear(
        config: RouterConfig,
        instances: List<DropbearInstance>,
        deletedSections: List<String>,
        ssh: SshConfig? = null,
        onPhase: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val existing = instances.map { it.section }.filter { it.isNotBlank() }.toSet()
        val named = instances.mapIndexed { idx, inst ->
            if (inst.section.isBlank()) {
                inst.copy(section = "app" + java.lang.Long.toString(System.currentTimeMillis(), 36) + "n" + idx)
            } else inst
        }
        val newSections = named.map { it.section }.filter { it !in existing }.toSet()
        if (ssh != null) {
            onPhase("正在写入并重启 SSH 服务…")
            val script = buildString {
                deletedSections.forEach { sec ->
                    append("uci -q delete dropbear.").append(shq(sec)).append("; ")
                }
                named.forEach { inst ->
                    if (inst.section in newSections) {
                        append("S=$(uci add dropbear dropbear) && uci rename dropbear.$S=")
                            .append(shq(inst.section)).append(" && ")
                    }
                    append(dropbearSetScript(inst))
                }
                append("uci commit dropbear; ")
                append("(sleep 2 && /etc/init.d/dropbear restart) >/dev/null 2>&1 & ")
                append("echo __ADMIN_APPLY_OK__")
            }
            val ok = runCatching {
                SshExec.run(ssh, script, 30_000).contains("__ADMIN_APPLY_OK__")
            }.getOrDefault(false)
            if (ok) return@withContext
            onPhase("SSH 提交失败，改用 uci apply 提交…")
            ubusDropbear(config, named, deletedSections, newSections)
            applyViaUbus(config, onPhase)
        } else {
            onPhase("正在写入配置…")
            ubusDropbear(config, named, deletedSections, newSections)
            applyViaUbus(config, onPhase)
        }
    }

    /** 单实例的 uci set/delete 脚本（语义对齐 LuCI dropbear.js 的 Flag/Value 保存行为）。 */
    private fun dropbearSetScript(inst: DropbearInstance): String {
        val sec = shq(inst.section)
        val sb = StringBuilder()
        fun set(opt: String, value: String) {
            sb.append("uci set dropbear.").append(sec).append('.').append(shq(opt))
                .append("='").append(value.replace("'", "'\\''")).append("'; ")
        }
        fun del(opt: String) {
            sb.append("uci -q delete dropbear.").append(sec).append('.').append(shq(opt)).append("; ")
        }
        set("enable", if (inst.enabled) "1" else "0")
        set("_direct", if (inst.directBind) "1" else "0")
        if (inst.directBind) {
            if (inst.directInterface.isNotBlank()) set("DirectInterface", inst.directInterface.trim()) else del("DirectInterface")
        } else {
            if (inst.interface_.isNotBlank()) set("Interface", inst.interface_.trim()) else del("Interface")
        }
        if (inst.port.isNotBlank()) set("Port", inst.port.trim()) else del("Port")
        set("PasswordAuth", if (inst.passwordAuth) "on" else "off")
        set("RootPasswordAuth", if (inst.rootPasswordAuth) "on" else "off")
        set("GatewayPorts", if (inst.gatewayPorts) "on" else "off")
        return sb.toString()
    }

    /** 无 SSH 后备：ubus 逐段 uci add/delete/set（staged）。 */
    private suspend fun ubusDropbear(
        config: RouterConfig,
        named: List<DropbearInstance>,
        deletedSections: List<String>,
        newSections: Set<String>
    ) {
        deletedSections.forEach { sec ->
            runCatching {
                call(config, "uci", "delete", buildJsonObject {
                    put("config", JsonPrimitive("dropbear"))
                    put("section", JsonPrimitive(sec))
                })
            }
        }
        named.forEach { inst ->
            if (inst.section in newSections) {
                call(config, "uci", "add", buildJsonObject {
                    put("config", JsonPrimitive("dropbear"))
                    put("type", JsonPrimitive("dropbear"))
                    put("name", JsonPrimitive(inst.section))
                })
            }
            call(config, "uci", "set", buildJsonObject {
                put("config", JsonPrimitive("dropbear"))
                put("section", JsonPrimitive(inst.section))
                put("values", buildJsonObject {
                    put("enable", JsonPrimitive(if (inst.enabled) "1" else "0"))
                    put("_direct", JsonPrimitive(if (inst.directBind) "1" else "0"))
                    if (inst.directBind && inst.directInterface.isNotBlank()) {
                        put("DirectInterface", JsonPrimitive(inst.directInterface.trim()))
                    }
                    if (!inst.directBind && inst.interface_.isNotBlank()) {
                        put("Interface", JsonPrimitive(inst.interface_.trim()))
                    }
                    if (inst.port.isNotBlank()) put("Port", JsonPrimitive(inst.port.trim()))
                    put("PasswordAuth", JsonPrimitive(if (inst.passwordAuth) "on" else "off"))
                    put("RootPasswordAuth", JsonPrimitive(if (inst.rootPasswordAuth) "on" else "off"))
                    put("GatewayPorts", JsonPrimitive(if (inst.gatewayPorts) "on" else "off"))
                })
            })
            // 空值选项删除（Port 未填 / 接口未选），与 SSH 路径语义一致
            listOfNotNull(
                "Port".takeIf { inst.port.isBlank() },
                "DirectInterface".takeIf { inst.directBind && inst.directInterface.isBlank() },
                "Interface".takeIf { !inst.directBind && inst.interface_.isBlank() }
            ).forEach { opt ->
                runCatching {
                    call(config, "uci", "delete", buildJsonObject {
                        put("config", JsonPrimitive("dropbear"))
                        put("section", JsonPrimitive(inst.section))
                        put("option", JsonPrimitive(opt))
                    })
                }
            }
        }
    }

    /** 无 SSH 后备：ubus uci apply + confirm（90 秒确认窗口，LuCI apply 协议）。 */
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

    // ===== SSH 密钥（/etc/dropbear/authorized_keys） =====

    /** 读取 authorized_keys（每行一个公钥）；文件不存在视为空列表。 */
    suspend fun loadAuthorizedKeys(config: RouterConfig, ssh: SshConfig?): List<String> =
        withContext(Dispatchers.IO) {
            val (code, payload) = callWithCode(
                config, "file", "read",
                buildJsonObject { put("path", JsonPrimitive("/etc/dropbear/authorized_keys")) }
            )
            when {
                code == 0 -> {
                    val data = payload?.jsonObject?.get("data")
                    ((data as? JsonPrimitive)?.content ?: "")
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toList()
                }
                code == 4 -> emptyList()
                ssh != null -> runCatching {
                    SshExec.run(ssh, "cat /etc/dropbear/authorized_keys 2>/dev/null")
                }.getOrDefault("")
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                else -> throw RouterException(
                    "无法读取 SSH 公钥列表（权限不足）。",
                    "请在设备设置中开启 SSH 后重试。"
                )
            }
        }

    /**
     * 整文件覆盖写入 authorized_keys（LuCI 同款：keys.join("\n") + "\n"，权限 0600）。
     * 优先 ubus file write（mode 0600）；ACL 拒绝（部分固件，本机实测返回 ubus 6）
     * 时回退 SSH：SshFiles.upload（stdin 管道，避免 shell 引号转义问题）+ chmod。
     */
    suspend fun saveAuthorizedKeys(config: RouterConfig, keys: List<String>, ssh: SshConfig?) =
        withContext(Dispatchers.IO) {
            val content = if (keys.isEmpty()) "" else keys.joinToString("\n") + "\n"
            val (code, _) = callWithCode(
                config, "file", "write",
                buildJsonObject {
                    put("path", JsonPrimitive("/etc/dropbear/authorized_keys"))
                    put("data", JsonPrimitive(content))
                    put("mode", JsonPrimitive(384))
                }
            )
            if (code == 0) return@withContext
            if (ssh == null) {
                throw RouterException(
                    "无法保存 SSH 公钥（本固件限制了网页文件写入）。",
                    "请在设备设置中开启 SSH 后重试。"
                )
            }
            SshFiles.upload(ssh, "/etc/dropbear/authorized_keys", content.toByteArray(Charsets.UTF_8))
            SshExec.run(ssh, "chmod 600 /etc/dropbear/authorized_keys; echo __CHMOD_OK__", 15_000)
        }

    // ===== HTTP(S) 访问（uci uhttpd.main.redirect_https） =====

    suspend fun loadHttpRedirect(config: RouterConfig): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val payload = call(
                config, "uci", "get",
                buildJsonObject { put("config", JsonPrimitive("uhttpd")) }
            )
            val main = payload.jsonObject["values"]?.jsonObject?.get("main")?.jsonObject
            main?.let { str(it, "redirect_https") == "1" } ?: false
        }.getOrDefault(false)
    }

    /**
     * 应用「重定向到 HTTPS」。SSH 路径 commit 后延时重启 uhttpd（重启会短暂中断
     * /ubus，故放入后台执行并先回显标记）；无 SSH 时 ubus 暂存 + apply+confirm
     * （确认窗口内自动重试，可穿过 uhttpd 的短暂重启）。
     */
    suspend fun applyHttpRedirect(
        config: RouterConfig,
        redirect: Boolean,
        ssh: SshConfig? = null,
        onPhase: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (ssh != null) {
            onPhase("正在写入并重启 uHTTPd…")
            val value = if (redirect) "1" else "0"
            val script = "uci set uhttpd.main.redirect_https='" + value + "'; " +
                "uci commit uhttpd; " +
                "(sleep 1 && /etc/init.d/uhttpd restart) >/dev/null 2>&1 & " +
                "echo __ADMIN_APPLY_OK__"
            val ok = runCatching {
                SshExec.run(ssh, script, 30_000).contains("__ADMIN_APPLY_OK__")
            }.getOrDefault(false)
            if (ok) return@withContext
            onPhase("SSH 提交失败，改用 uci apply 提交…")
        } else {
            onPhase("正在写入配置…")
            call(
                config, "uci", "set",
                buildJsonObject {
                    put("config", JsonPrimitive("uhttpd"))
                    put("section", JsonPrimitive("main"))
                    put("values", buildJsonObject {
                        put("redirect_https", JsonPrimitive(if (redirect) "1" else "0"))
                    })
                }
            )
        }
        applyViaUbus(config, onPhase)
    }

    // ===== 软件包仓库公钥 =====

    /**
     * 列出仓库公钥。返回 (密钥目录, 公钥列表)；目录按 LuCI repokeys.js 的
     * determineKeyEnv 规则探测：/etc/apk/keys（.pem）存在则用之，否则 /etc/opkg/keys。
     * ubus file list/read 不可用且无 SSH 时返回 null。
     */
    suspend fun loadRepoKeys(config: RouterConfig, ssh: SshConfig?): Pair<String, List<RepoPublicKey>>? =
        withContext(Dispatchers.IO) {
            val (apkStat, _) = callWithCode(
                config, "file", "stat",
                buildJsonObject { put("path", JsonPrimitive("/etc/apk/keys")) }
            )
            val dir = if (apkStat == 0) "/etc/apk/keys" else "/etc/opkg/keys"
            val (listCode, listPayload) = callWithCode(
                config, "file", "list",
                buildJsonObject { put("path", JsonPrimitive(dir)) }
            )
            if (listCode != 0) {
                if (ssh == null) return@withContext null
                val names = runCatching {
                    SshExec.run(ssh, "ls -1 ${shq(dir)} 2>/dev/null")
                }.getOrDefault("")
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                val keys = names.map { name ->
                    val content = runCatching {
                        SshExec.run(ssh, "cat ${shq("$dir/$name")}")
                    }.getOrDefault("")
                    RepoPublicKey(name, content, isProtectedRepoKey(name))
                }
                return@withContext dir to keys
            }
            val entries = listPayload?.jsonObject?.get("entries") as? JsonArray
                ?: JsonArray(emptyList())
            val keys = entries.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val name = (obj["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val type = (obj["type"] as? JsonPrimitive)?.content ?: "file"
                if (type != "file") return@mapNotNull null
                val (_, readPayload) = callWithCode(
                    config, "file", "read",
                    buildJsonObject { put("path", JsonPrimitive("$dir/$name")) }
                )
                val content = readPayload
                    ?.let { (it.jsonObject["data"] as? JsonPrimitive)?.content } ?: ""
                RepoPublicKey(name, content, isProtectedRepoKey(name))
            }
            dir to keys
        }

    /**
     * 添加仓库公钥。[baseName] 为建议文件名（去掉扩展名，URL 添加时取自 URL 文件名），
     * 缺省用 key_<时间戳>；apk 环境自动补 .pem（LuCI saveKeyFile 同款）。
     * 返回实际写入的文件名。
     */
    suspend fun addRepoKey(
        config: RouterConfig,
        keyDir: String,
        content: String,
        baseName: String?,
        ssh: SshConfig?
    ): String = withContext(Dispatchers.IO) {
        val isApk = keyDir == "/etc/apk/keys"
        val filename = (baseName?.takeIf { it.isNotBlank() } ?: ("key_" + System.currentTimeMillis())) +
            (if (isApk) ".pem" else "")
        val path = "$keyDir/$filename"
        val (code, _) = callWithCode(
            config, "file", "write",
            buildJsonObject {
                put("path", JsonPrimitive(path))
                put("data", JsonPrimitive(content))
                put("mode", JsonPrimitive(384))
            }
        )
        if (code == 0) return@withContext filename
        if (ssh == null) {
            throw RouterException(
                "无法写入仓库公钥（本固件限制了网页文件写入）。",
                "请在设备设置中开启 SSH 后重试。"
            )
        }
        SshFiles.upload(ssh, path, content.toByteArray(Charsets.UTF_8))
        SshExec.run(ssh, "chmod 600 ${shq(path)}; echo __CHMOD_OK__", 15_000)
        filename
    }

    /** 删除仓库公钥文件。ubus file remove 被拒时走 SSH rm -f。 */
    suspend fun deleteRepoKey(config: RouterConfig, keyDir: String, filename: String, ssh: SshConfig?) =
        withContext(Dispatchers.IO) {
            val path = "$keyDir/$filename"
            val (code, _) = callWithCode(
                config, "file", "remove",
                buildJsonObject { put("path", JsonPrimitive(path)) }
            )
            if (code == 0) return@withContext
            if (ssh == null) {
                throw RouterException(
                    "无法删除仓库公钥（本固件限制了网页文件删除）。",
                    "请在设备设置中开启 SSH 后重试。"
                )
            }
            SshExec.run(ssh, "rm -f ${shq(path)}; echo __RM_OK__", 15_000)
        }

    /**
     * 从 URL 拉取公钥内容（LuCI addKey 的 URL 分支：HTTP 200、≤8192 字节、≥32 字符）。
     * 返回 (内容, 建议文件名——URL 路径最后一节去扩展名)。
     */
    suspend fun fetchKeyFromUrl(url: String): Pair<String, String?> = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RouterException("拉取公钥失败：HTTP ${response.code}。", "请检查 URL 是否可访问。")
            }
            val text = response.body?.string()?.trim().orEmpty()
            if (text.isEmpty() || text.length > 8192) {
                throw RouterException("密钥文件内容无效。", "内容为空或超过 8192 字节上限。")
            }
            if (text.length < 32) {
                throw RouterException("密钥文件内容无效。", "内容过短，不像是密钥文件。")
            }
            val name = url.substringBefore('?').substringBefore('#')
                .substringAfterLast('/')
                .ifBlank { null }
                ?.replace(Regex("\\.[^.]+$"), "")
            text to name
        }
    }
}

/**
 * OpenSSH 公钥行解析（LuCI SSHPubkeyDecoder 的 Kotlin 移植）：
 * 可选 options 前缀 + 类型 + base64 主体 + 注释；类型字段须与主体内嵌类型一致。
 * 支持 RSA / DSA / Ed25519 / ECDSA(NIST P-256/384/521) / FIDO(SK) 密钥。
 */
object SshPublicKeyDecoder {

    private val KEY_LINE = Regex(
        "^((?:(?:^|,)[^ =,]+(?:=(?:[^ \",]+|\"(?:[^\"\\\\]|\\\\.)*\"))?)+ +)?" +
            "(ssh-dss|ssh-rsa|ssh-ed25519|ecdsa-sha2-nistp[0-9]+|" +
            "sk-ecdsa-sha2-nistp256@openssh\\.com|sk-ssh-ed25519@openssh\\.com) +([^ ]+)( +.*)?$"
    )
    private val OPTIONS = Regex("(?:^|,)([^ =,]+)(?:=(?:([^ \",]+)|\"((?:[^\"\\\\]|\\\\.)*)\"))?")

    fun decode(line: String): SshPublicKey? {
        val trimmed = line.trim()
        val m = KEY_LINE.find(trimmed) ?: return null
        val (optionsPart, type, b64, commentPart) = m.destructured
        val key = try {
            java.util.Base64.getDecoder().decode(b64).toString(Charsets.ISO_8859_1)
        } catch (_: Exception) {
            return null
        }

        fun lengthAt(off: Int): Int {
            if (off < 0 || off + 4 > key.length) return -1
            var l = 0
            for (i in 0 until 4) {
                l = (l shl 8) or (key[off + i].code and 0xFF)
            }
            return if (l < 0 || off + 4 + l > key.length) -1 else l
        }

        var off = 0
        val len = lengthAt(off)
        if (len <= 0) return null
        val innerType = key.substring(off + 4, off + 4 + len)
        if (innerType != type) return null
        off += 4 + len

        val kind = when (type) {
            "ssh-rsa" -> "RSA"
            "ssh-dss" -> "DSA"
            "ssh-ed25519" -> "EdDSA"
            "sk-ecdsa-sha2-nistp256@openssh.com" -> "ECDSA-SK"
            "sk-ssh-ed25519@openssh.com" -> "EdDSA-SK"
            else -> if (type.startsWith("ecdsa-sha2-")) "ECDSA" else return null
        }

        var len1 = if (off < key.length) lengthAt(off) else 0
        var len2 = 0
        if (len1 > 0) {
            if (kind == "ECDSA") {
                // 曲线名是第一个子字段；必须与类型后缀一致（LuCI type.substr(11) 校验）
                val curveName = key.substring(off + 4, off + 4 + len1)
                if (type.substringAfter("ecdsa-sha2-") != curveName) return null
            }
            off += 4 + len1
            len2 = if (off < key.length) lengthAt(off) else 0
        }
        if (len2 < 0) return null

        // LuCI：奇数字长先减一（mpint 前导零位）再换算位数
        if ((len1 and 1) == 1) len1--
        var len2adj = len2
        if ((len2adj and 1) == 1) len2adj--

        val bits: Int? = when (kind) {
            "RSA" -> len2adj * 8
            "DSA" -> len1 * 8
            else -> null
        }

        val displayCurve: String? = when (kind) {
            "EdDSA", "EdDSA-SK" -> "Curve25519"
            "ECDSA-SK" -> "NIST P-256"
            "ECDSA" -> type.substringAfter("ecdsa-sha2-").replace(Regex("^nistp(\\d+)$"), "NIST P-$1")
            else -> null
        }

        val options = if (optionsPart.isBlank()) emptyList() else {
            val map = linkedMapOf<String, String>()
            OPTIONS.findAll(optionsPart.trim()).forEach { om ->
                val (k, p, q) = om.destructured
                map[k] = q.ifEmpty { p }
            }
            map.keys.sorted()
        }

        val comment = commentPart.trim()
        val displayKey = if (b64.length > 68) {
            b64.substring(0, 33) + "…" + b64.substring(b64.length - 34)
        } else b64

        SshPublicKey(
            source = trimmed,
            kind = kind,
            bits = bits,
            curve = displayCurve,
            comment = comment,
            options = options,
            displayKey = displayKey
        )
    }
}
