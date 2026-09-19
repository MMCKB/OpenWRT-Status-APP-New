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

    // ---- 列表 ----

    /** 已安装软件包（apk info -v，每行"包名-版本"）。 */
    suspend fun listInstalled(ssh: SshConfig): List<PkgInfo> = withContext(Dispatchers.IO) {
        parseInstalledPackages(exec(ssh, "apk info -v", 120_000))
    }

    /** 可升级软件包（apk list -u，输出含旧版本号）。 */
    suspend fun listUpgradable(ssh: SshConfig): List<PkgInfo> = withContext(Dispatchers.IO) {
        parseUpgradablePackages(exec(ssh, "apk list -u", 120_000))
    }

    /** 可用软件包（apk search -v "*"，含描述；[installedNames] 用于标记已安装）。 */
    suspend fun listAvailable(ssh: SshConfig, installedNames: Set<String>): List<PkgInfo> =
        withContext(Dispatchers.IO) {
            parseAvailablePackages(exec(ssh, "apk search -v \"*\" || apk search \"*\"", 180_000), installedNames)
        }

    // ---- 操作 ----

    suspend fun update(ssh: SshConfig): PkgOpResult = runOpCmd(ssh, "apk update")

    suspend fun install(ssh: SshConfig, packageName: String): PkgOpResult =
        runOpCmd(ssh, "apk add ${quotePackageName(packageName)}")

    suspend fun remove(ssh: SshConfig, packageName: String): PkgOpResult =
        runOpCmd(ssh, "apk del ${quotePackageName(packageName)}")

    suspend fun upgradePackage(ssh: SshConfig, packageName: String): PkgOpResult =
        runOpCmd(ssh, "apk upgrade ${quotePackageName(packageName)}")

    suspend fun upgradeAll(ssh: SshConfig): PkgOpResult = runOpCmd(ssh, "apk upgrade")

    private suspend fun runOpCmd(ssh: SshConfig, command: String): PkgOpResult =
        withContext(Dispatchers.IO) {
            val out = exec(ssh, command, 600_000)
            // apk 的失败信息以 ERROR: 开头（stderr 已并入 stdout）。
            val failed = Regex("^ERROR", RegexOption.MULTILINE).containsMatchIn(out)
            PkgOpResult(
                code = if (failed) 1 else 0,
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
            val quotedSource = quoteShell(source)
            if (entries.isEmpty()) {
                "rm -f $quotedSource"
            } else {
                val writeLines = entries
                    .joinToString(" ") { quoteShell((if (it.enabled) "" else "# ") + it.url) }
                "target=$quotedSource; mkdir -p \"\$(dirname \"\$target\")\"; temp=\$(mktemp /tmp/openwrt-status-apk-repositories.XXXXXX) || exit 1; printf '%s\\n' $writeLines > \"\$temp\" || { rm -f \"\$temp\"; exit 1; }; cp \"\$target\" \"\$target.openwrt-status.bak\" 2>/dev/null || true; mv \"\$temp\" \"\$target\""
            }
        }
        exec(
            ssh,
            "if ! command -v apk >/dev/null 2>&1; then echo 'apk 未安装。'; exit 2; fi; umask 077; ${writes.joinToString("; ")} && apk update",
            300_000
        )
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

    // ---- 行文本解析（移植自旧版 OpenWRT-Status-APP） ----

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
                        description = "已安装的系统软件包 (apk)",
                        installed = true
                    )
                } else {
                    PkgInfo(
                        name = trimmed,
                        version = "unknown",
                        description = "已安装的系统软件包 (apk)",
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
