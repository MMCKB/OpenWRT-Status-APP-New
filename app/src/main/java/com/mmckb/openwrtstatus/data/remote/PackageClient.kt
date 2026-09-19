package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 一个软件包（已安装或可用）。 */
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

/** 挂载点容量（用于存储占用进度条）。 */
data class MountInfo(
    val mount: String,
    val size: Long,
    val free: Long
)

/**
 * 软件包管理数据层：通过 SSH 调用 LuCI 的
 * `/usr/libexec/package-manager-call` 助手（apk/opkg 双后端，自动适配）。
 * update/install/remove/upgrade 的结果是 JSON 包装 {code, pkmcmd, stdout, stderr}。
 */
class PackageClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun quote(s: String): String = "'${s.replace("'", "'\\''")}'"

    private suspend fun runHelper(
        ssh: SshConfig,
        timeoutMs: Int,
        vararg args: String
    ): String = withContext(Dispatchers.IO) {
        val cmd = args.joinToString(" ") { quote(it) }
        SshExec.run(ssh, "/usr/libexec/package-manager-call $cmd", timeoutMs)
    }

    /** 已安装软件包列表。 */
    suspend fun listInstalled(ssh: SshConfig): List<PkgInfo> =
        parseApkJson(runHelper(ssh, 180_000, "list-installed"), installedByDefault = true)

    /** 可用软件包列表（需要先更新过列表）。 */
    suspend fun listAvailable(ssh: SshConfig): List<PkgInfo> =
        parseApkJson(runHelper(ssh, 180_000, "list-available"), installedByDefault = false)

    /** update / install / remove / upgrade；pkgs 为包名或本地路径。 */
    suspend fun op(
        ssh: SshConfig,
        action: String,
        pkgs: List<String>
    ): PkgOpResult = withContext(Dispatchers.IO) {
        val out = runHelper(ssh, 600_000, action, *pkgs.toTypedArray())
        val obj = runCatching { json.parseToJsonElement(out).jsonObject }.getOrNull()
        PkgOpResult(
            code = (obj?.get("code") as? JsonPrimitive)?.content?.toIntOrNull() ?: -1,
            pkmcmd = (obj?.get("pkmcmd") as? JsonPrimitive)?.content,
            stdout = (obj?.get("stdout") as? JsonPrimitive)?.content,
            stderr = (obj?.get("stderr") as? JsonPrimitive)?.content
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

    /** 解析 apk query --format json 的数组输出。 */
    private fun parseApkJson(s: String, installedByDefault: Boolean): List<PkgInfo> {
        val trimmed = s.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        val arr = runCatching { json.parseToJsonElement(trimmed) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val status = obj["status"] as? JsonArray
            PkgInfo(
                name = name,
                version = (obj["version"] as? JsonPrimitive)?.content,
                size = (obj["file-size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                description = (obj["description"] as? JsonPrimitive)?.content,
                installed = installedByDefault ||
                    (status?.any { (it as? JsonPrimitive)?.content == "installed" } ?: false)
            )
        }.sortedBy { it.name.lowercase() }
    }

    companion object {
        /** 版本比较：按数字段比较（用于可升级判断），语义同 LuCI 的 compareVersion。 */
        fun compareVersion(a: String, b: String): Int {
            val pa = a.split(Regex("[.\\-+]")).filter { it.isNotEmpty() }
            val pb = b.split(Regex("[.\\-+]")).filter { it.isNotEmpty() }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val va = pa.getOrNull(i)
                val vb = pb.getOrNull(i)
                if (va == vb) continue
                if (va == null) return -1
                if (vb == null) return 1
                val na = va.toLongOrNull()
                val nb = vb.toLongOrNull()
                if (na != null && nb != null) {
                    val c = na.compareTo(nb)
                    if (c != 0) return c
                } else {
                    val c = va.compareTo(vb)
                    if (c != 0) return c
                }
            }
            return 0
        }
    }
}
