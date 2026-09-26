package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一个软件包（已安装/可用/可升级共用）。 */
data class PkgInfo(
    val name: String,
    val version: String? = null,
    val size: Long = 0L,
    val description: String? = null,
    val installed: Boolean = false
)

/** 一次软件包操作（update/install/remove/upgrade）的结果。 */
data class PkgOpResult(
    val code: Int,
    val pkmcmd: String?,
    val stdout: String?,
    val stderr: String?
) {
    val success: Boolean get() = code == 0
}

/** 一条 APK 软件源条目。 */
data class ApkRepository(
    val line: Int,
    val url: String,
    val enabled: Boolean,
    val source: String? = null
)

/** 挂载点容量（用于存储占用进度条）。 */
data class MountInfo(
    val mount: String,
    val size: Long,
    val free: Long
)

/**
 * 软件包管理数据层：与 OpenWRT-Status-APP（旧版）一致，通过 SSH 直接执行
 * 原生 apk 命令并解析行文本输出（apk info -v / list -u / search -v /
 * add / del / upgrade / update），不经过 LuCI 的 package-manager-call 助手，
 * 避免传输数 MB 的全字段 JSON。
 */
class PackageClient {

    /** OpenWrt 25.12+ APK 软件源文件。 */
    val apkCustomFeedsSource = "/etc/apk/repositories.d/customfeeds.list"
    val apkDistFeedsSource = "/etc/apk/repositories.d/distfeed"
    val apkDistFeedsFallbackSource = "/etc/apk/repositories.d/distfeeds.list"

    private fun quotePackageName(name: String): String {
        val sanitized = name.trim().replace(Regex("[^a-zA-Z0-9+._:@/-]"), "")
        require(sanitized.isNotEmpty()) { "软件包名称无效。" }
        return "\"$sanitized\""
    }

    private fun quoteShell(value: String): String =
        "'${value.replace("'", "'\\''")}'"

    private fun normalizeRepositoryUrl(value: String): String {
        val url = value.trim()
        if (!Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(url) ||
            Regex("['\"`$\\\\;|<>(){}\\[\\]!]").containsMatchIn(url)
        ) {
            throw IllegalArgumentException("仓库地址必须是以 http:// 或 https:// 开头的完整 URL。")
        }
        return url
    }

    private suspend fun exec(ssh: SshConfig, command: String, timeoutMs: Int): String =
        withContext(Dispatchers.IO) { SshExec.run(ssh, "$command 2>&1", timeoutMs) }

    private val EXIT_MARKER = "__OPENWRT_STATUS_EXIT__"

    /**
     * 执行命令并返回 (清洗后的输出, 真实退出码)。命令末尾追加退出码标记行，
     * 由远端 shell 回显；SSH 会话中途断开（如设备离网、连接被杀）时标记行
     * 不会到达，退出码为 null——必须视为失败，不能凭「输出里没有 ERROR」
     * 就判定成功。
     */
    private suspend fun execWithCode(ssh: SshConfig, command: String, timeoutMs: Int): Pair<String, Int?> =
        withContext(Dispatchers.IO) {
            val out = SshExec.run(ssh, "$command 2>&1; echo \"$EXIT_MARKER:\$?\"", timeoutMs)
            val code = Regex("$EXIT_MARKER:(-?\\d+)\\s*$", RegexOption.MULTILINE)
                .find(out)?.groupValues?.get(1)?.toIntOrNull()
            val cleaned = out
                .replace(Regex("^$EXIT_MARKER:-?\\d+\\s*$", RegexOption.MULTILINE), "")
                .trim()
            cleaned to code
        }

    // ---- 后端检测（apk / opkg，自动适配，逻辑同 LuCI helper） ----

    private val backends = mutableMapOf<String, String>()

    /** 检测路由器使用的包管理器：有 /usr/bin/apk 用 apk，否则 opkg。 */
    suspend fun backend(ssh: SshConfig): String = withContext(Dispatchers.IO) {
        backends.getOrPut("${ssh.username}@${ssh.host}:${ssh.port}") {
            runCatching {
                SshExec.run(
                    ssh,
                    "command -v apk >/dev/null 2>&1 && echo apk || echo opkg",
                    20_000
                ).trim().ifBlank { "opkg" }
            }.getOrDefault("opkg")
        }
    }

    // ---- 列表 ----

    /** 已安装软件包。 */
    suspend fun listInstalled(ssh: SshConfig): List<PkgInfo> {
        val bin = backend(ssh)
        return if (bin == "apk") {
            withContext(Dispatchers.IO) { parseInstalledPackages(exec(ssh, "apk info -v", 120_000)) }
        } else {
            withContext(Dispatchers.IO) {
                parseOpkgList(exec(ssh, "cat /usr/lib/opkg/status", 120_000), installedByDefault = true)
            }
        }
    }

    /** 可升级软件包。 */
    suspend fun listUpgradable(ssh: SshConfig): List<PkgInfo> {
        val bin = backend(ssh)
        return if (bin == "apk") {
            withContext(Dispatchers.IO) { parseUpgradablePackages(exec(ssh, "apk list -u", 120_000)) }
        } else {
            withContext(Dispatchers.IO) {
                parseOpkgUpgradable(exec(ssh, "opkg list-upgradable", 120_000))
            }
        }
    }

    /** 可用软件包（[installedNames] 用于标记已安装）。 */
    suspend fun listAvailable(ssh: SshConfig, installedNames: Set<String>): List<PkgInfo> {
        val bin = backend(ssh)
        return if (bin == "apk") {
            withContext(Dispatchers.IO) {
                parseAvailablePackages(exec(ssh, "apk search -v \"*\" || apk search \"*\"", 180_000), installedNames)
            }
        } else {
            // opkg：解包 lists 目录的 gzip 索引（逻辑同 LuCI helper）。
            val command = "lists_dir=\$(sed -rne 's#^lists_dir \\S+ (\\S+)#\\1#p' /etc/opkg.conf /etc/opkg/*.conf 2>/dev/null | tail -n 1); " +
                "find \"\${lists_dir:-/usr/lib/opkg/lists}\" -type f '!' -name '*.sig' | xargs -r gzip -cd"
            withContext(Dispatchers.IO) { parseOpkgList(exec(ssh, command, 180_000), installedByDefault = false) }
        }
    }

    // ---- 操作 ----

    suspend fun update(ssh: SshConfig): PkgOpResult = runOpCmd(ssh, "update")

    /** [allowUntrusted]：跳过签名校验（上传的本地包默认启用）。 */
    suspend fun install(
        ssh: SshConfig,
        packageName: String,
        allowUntrusted: Boolean = false
    ): PkgOpResult {
        val bin = backend(ssh)
        val args = mutableListOf<String>()
        if (allowUntrusted && bin == "apk") args += "--allow-untrusted"
        args += quotePackageName(packageName)
        return runOpCmd(ssh, "install", *args.toTypedArray())
    }

    suspend fun remove(ssh: SshConfig, packageName: String): PkgOpResult =
        runOpCmd(ssh, "remove", quotePackageName(packageName))

    suspend fun upgradePackage(ssh: SshConfig, packageName: String): PkgOpResult =
        runOpCmd(ssh, "upgrade", quotePackageName(packageName))

    suspend fun upgradeAll(ssh: SshConfig): PkgOpResult = runOpCmd(ssh, "upgrade")

    private suspend fun runOpCmd(ssh: SshConfig, action: String, vararg pkgs: String): PkgOpResult =
        withContext(Dispatchers.IO) {
            val bin = backend(ssh)
            // apk 的安装/删除子命令是 add/del（opkg 才叫 install/remove），语义同 LuCI helper。
            val mappedAction = when {
                bin == "apk" && action == "install" -> "add"
                bin == "apk" && action == "remove" -> "del"
                else -> action
            }
            val command = "$bin $mappedAction" + if (pkgs.isEmpty()) "" else " " + pkgs.joinToString(" ")
            val (out, exitCode) = execWithCode(ssh, command, 600_000)
            // 判定失败的三种情况：输出含 ERROR、远端返回非 0、以及「拿不到退出码」
            // （SSH 会话中断，例如更新过程中设备离网）——绝不能当作成功。
            val failed = exitCode == null || exitCode != 0 ||
                Regex("^ERROR|^Collected errors", RegexOption.MULTILINE).containsMatchIn(out)
            PkgOpResult(
                code = when {
                    exitCode == null -> -1
                    failed && exitCode == 0 -> 1
                    else -> exitCode
                },
                pkmcmd = command,
                stdout = out.takeIf { it.isNotBlank() },
                stderr = null
            )
        }

    // ---- 软件源管理 ----

    /** 读取 APK 软件源条目（distfeed + customfeeds）。 */
    suspend fun repositoriesSnapshot(ssh: SshConfig): List<ApkRepository> =
        withContext(Dispatchers.IO) {
            val command = buildString {
                append("if ! command -v apk >/dev/null 2>&1; then echo 'ERROR|apk_missing'; exit 2; fi; ")
                append("found=0; for file in $apkCustomFeedsSource $apkDistFeedsSource $apkDistFeedsFallbackSource; do ")
                append("[ -f \"\$file\" ] || continue; found=1; ")
                append("awk -v source=\"\$file\" '{ raw=\$0; sub(/\\r$/, \"\", raw); if (raw ~ /^[[:space:]]*$/) next; enabled=1; if (raw ~ /^[[:space:]]*#/) { enabled=0; sub(/^[[:space:]]*#[[:space:]]*/, \"\", raw); } if (raw != \"\") printf \"REPO|%s|%d|%d|%s\\n\", source, NR, enabled, raw; }' \"\$file\"; done; ")
                append("[ \"\$found\" -eq 1 ] || { echo 'ERROR|repositories_missing'; exit 2; }")
            }
            parseApkRepositories(exec(ssh, command, 60_000))
        }

    /**
     * 受控保存 APK 软件源列表：仅接受 HTTP(S) 地址，保存前备份并原子替换，
     * 完成后自动 apk update。返回 apk update 的输出。
     */
    suspend fun saveRepositories(
        ssh: SshConfig,
        repositories: List<ApkRepository>
    ): String = withContext(Dispatchers.IO) {
        val normalized = repositories.map {
            val url = normalizeRepositoryUrl(it.url)
            val source = when (it.source) {
                apkDistFeedsFallbackSource -> apkDistFeedsSource
                apkCustomFeedsSource, apkDistFeedsSource -> it.source
                else -> throw IllegalArgumentException("APK 仓库配置文件路径无效。")
            }
            ApkRepository(it.line, url, it.enabled, source)
        }
        val active = normalized
        require(active.isNotEmpty()) { "至少保留一个软件包仓库。" }
        require(active.any { it.enabled }) { "至少启用一个软件包仓库。" }
        require(active.map { it.url }.toSet().size == active.size) { "软件包仓库地址不能重复。" }

        val writes = normalized.map { it.source }.toSet().map { source ->
            val entries = normalized.filter { it.source == source }
            val quotedSource = quoteShell(source!!)
            if (entries.isEmpty()) {
                "rm -f $quotedSource"
            } else {
                val writeLines = entries
                    .joinToString(" ") { quoteShell((if (it.enabled) "" else "# ") + it.url) }
                "target=$quotedSource; mkdir -p \"\$(dirname \"\$target\")\"; temp=\$(mktemp /tmp/openwrt-status-apk-repositories.XXXXXX) || exit 1; printf '%s\\n' $writeLines > \"\$temp\" || { rm -f \"\$temp\"; exit 1; }; cp \"\$target\" \"\$target.openwrt-status.bak\" 2>/dev/null || true; mv \"\$temp\" \"\$target\""
            }
        }
        val (out, exitCode) = execWithCode(
            ssh,
            "if ! command -v apk >/dev/null 2>&1; then echo 'apk 未安装。'; exit 2; fi; umask 077; ${writes.joinToString("; ")} && apk update",
            300_000
        )
        // 会话中断 / 非 0 退出时补一行 ERROR，让调用方的 ERROR 检查直接判定失败。
        val failed = exitCode == null || exitCode != 0
        if (failed && !out.startsWith("ERROR")) {
            (if (out.isBlank()) "" else "$out\n") +
                "ERROR: 命令未正常完成（${exitCode?.let { "退出码 $it" } ?: "连接中断"}）。"
        } else out
    }

    /** 根分区存储容量（apk 安装位置）。 */
    suspend fun mountInfo(ssh: SshConfig): MountInfo? = withContext(Dispatchers.IO) {
        val out = runCatching {
            SshExec.run(ssh, "df -k / /overlay 2>/dev/null", 20_000)
        }.getOrDefault("")
        val lines = out.lines().filter { it.contains("/") && !it.startsWith("Filesystem") }
        val root = lines.firstOrNull { it.trimEnd().endsWith(" /") }
            ?: lines.firstOrNull { it.trimEnd().endsWith(" /overlay") }
            ?: return@withContext null
        val f = root.split(' ').filter { it.isNotEmpty() }
        if (f.size < 4) return@withContext null
        MountInfo(
            mount = "/",
            size = (f[1].toLongOrNull() ?: 0L) * 1024,
            free = (f[3].toLongOrNull() ?: 0L) * 1024
        )
    }

    // ---- 行文本解析（apk 部分移植自旧版 OpenWRT-Status-APP） ----

    /**
     * 解析 opkg 的文本格式（/usr/lib/opkg/status 与 lists 索引同构）：
     * `Package:/Version:/status:/Installed-Size:/Description:` 等键值块，
     * 续行以空格开头；键名大小写不敏感（移植自 LuCI parseList）。
     */
    private fun parseOpkgList(output: String, installedByDefault: Boolean): List<PkgInfo> {
        val packages = mutableListOf<PkgInfo>()
        var name: String? = null
        var version: String? = null
        var description: String? = null
        var size = 0L
        var installed = false

        fun flush() {
            val n = name ?: return
            packages.add(
                PkgInfo(
                    name = n,
                    version = version,
                    size = size,
                    description = description,
                    installed = installedByDefault || installed
                )
            )
        }

        for (raw in output.split(Regex("\r?\n"))) {
            if (raw.startsWith(" ") || raw.startsWith("\t")) {
                // 续行：追加到上一个字段的值。
                if (raw.trim().isNotEmpty()) {
                    description = (description ?: "").let { if (it.isEmpty()) raw.trim() else "$it ${raw.trim()}" }
                }
                continue
            }
            val idx = raw.indexOf(':')
            if (idx <= 0) continue
            val key = raw.substring(0, idx).trim().lowercase()
            val value = raw.substring(idx + 1).trim()
            when (key) {
                "package" -> {
                    flush()
                    name = value.ifEmpty { null }
                    version = null
                    description = null
                    size = 0L
                    installed = false
                }
                "version" -> version = value
                "installed-size" -> size = value.toLongOrNull() ?: 0L
                "status" -> installed = value.split(' ').getOrNull(2) == "installed"
                "description" -> description = value
            }
        }
        flush()
        return packages.sortedBy { it.name.lowercase() }
    }

    /** 解析 `opkg list-upgradable` 输出：`name - version - description`。 */
    private fun parseOpkgUpgradable(output: String): List<PkgInfo> {
        val packages = mutableListOf<PkgInfo>()
        for (raw in output.split(Regex("\r?\n"))) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("ERROR") || trimmed.startsWith("Collected errors")) continue
            val segments = trimmed.split(" - ")
            if (segments.isEmpty()) continue
            val nameVersion = segments[0].trim()
            val description = segments.getOrNull(2)?.trim() ?: "有可用更新"
            val match = Regex("^(.+)-([0-9].*)$").find(nameVersion)
            packages.add(
                PkgInfo(
                    name = match?.groupValues?.get(1) ?: nameVersion,
                    version = match?.groupValues?.get(2) ?: "unknown",
                    description = description,
                    installed = true
                )
            )
        }
        return packages
    }

    private fun parseInstalledPackages(output: String): List<PkgInfo> {
        val packages = mutableListOf<PkgInfo>()
        for (rawLine in output.split(Regex("\r?\n"))) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() ||
                trimmed.startsWith("fetch ") ||
                trimmed.startsWith("OK:") ||
                trimmed.startsWith("packages:")
            ) continue
            val match = Regex("^(.+)-([0-9].*)$").find(trimmed)
            packages.push(
                if (match != null) {
                    PkgInfo(
                        name = match.groupValues[1],
                        version = match.groupValues[2],
                        description = null,
                        installed = true
                    )
                } else {
                    PkgInfo(
                        name = trimmed,
                        version = "unknown",
                        description = null,
                        installed = true
                    )
                }
            )
        }
        return packages
    }

    private fun parseUpgradablePackages(output: String): List<PkgInfo> {
        val packages = mutableListOf<PkgInfo>()
        val regex = Regex("^(.+)-([0-9].*?)(?:\\s+\\[upgradable from:\\s+([^\\]]+)])?$")
        for (rawLine in output.split(Regex("\r?\n"))) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() ||
                trimmed.startsWith("fetch ") ||
                trimmed.startsWith("OK:") ||
                trimmed.startsWith("packages:")
            ) continue
            val match = regex.find(trimmed) ?: continue
            val (name, version, previousVersion) = match.destructured
            packages.add(
                PkgInfo(
                    name = name,
                    version = version,
                    description = if (previousVersion.isNotEmpty()) "可从 $previousVersion 更新" else "有可用更新",
                    installed = true
                )
            )
        }
        return packages
    }

    private fun parseAvailablePackages(output: String, installedNames: Set<String>): List<PkgInfo> {
        val packages = mutableListOf<PkgInfo>()
        for (rawLine in output.split(Regex("\r?\n"))) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() ||
                trimmed.startsWith("fetch ") ||
                trimmed.startsWith("OK:") ||
                trimmed.startsWith("packages:")
            ) continue
            val sepIdx = trimmed.indexOf(" - ")
            val nameVersion = if (sepIdx > 0) trimmed.slice(0 until sepIdx).trim() else trimmed
            val description = if (sepIdx > 0) {
                trimmed.slice(sepIdx + 3 until trimmed.length).trim()
            } else {
                "软件仓库中的可用包 (apk)"
            }
            val match = Regex("^(.+)-([0-9][a-zA-Z0-9._.-]*)$").find(nameVersion)
            val name = match?.groupValues?.get(1) ?: nameVersion
            val version = match?.groupValues?.get(2) ?: "unknown"
            if (name.isEmpty() || packages.any { it.name == name }) continue
            val installed = installedNames.contains(name)
            packages.add(
                PkgInfo(
                    name = name,
                    version = version,
                    description = description,
                    installed = installed
                )
            )
        }
        return packages
    }

    private fun parseApkRepositories(output: String): List<ApkRepository> {
        val repositories = mutableListOf<ApkRepository>()
        for (rawLine in output.split(Regex("\r?\n"))) {
            val line = rawLine.trim()
            if (!line.startsWith("REPO|")) continue
            val values = line.split("|")
            val modernFormat = values.size >= 5
            val source = if (modernFormat) values[1] else null
            val lineValue = if (modernFormat) values[2] else values[1]
            val enabledValue = if (modernFormat) values[3] else values[2]
            val url = (if (modernFormat) values.drop(4) else values.drop(3)).joinToString("|")
            val number = lineValue.toIntOrNull() ?: continue
            if (url.isBlank()) continue
            repositories.add(ApkRepository(line = number, url = url.trim(), enabled = enabledValue == "1", source = source))
        }
        return repositories
    }
}

private fun <T> MutableList<T>.push(item: T) {
    add(item)
}
