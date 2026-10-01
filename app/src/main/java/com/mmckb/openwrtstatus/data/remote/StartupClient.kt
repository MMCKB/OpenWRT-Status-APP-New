package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import com.mmckb.openwrtstatus.data.ssh.SshFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一个 init 启动脚本（/etc/init.d 下的可执行脚本）。 */
data class InitScript(
    val name: String,
    val enabled: Boolean,
    val priority: Int?
)

/** 启动页数据（init 脚本列表 + /etc/rc.local 内容）。 */
data class StartupData(
    val scripts: List<InitScript>,
    val rcLocal: String
)

/**
 * 启动项数据层（LuCI admin/system/startup 的完整复刻）：
 *  - 脚本列表来自 /etc/init.d（可执行项），启用状态与启动优先级来自
 *    /etc/rc.d/ 的 S/K 符号链接（启用 = 存在同名链接，优先级 = 链接中的数字）；
 *  - 动作（启动/停止/重启/重载/启用/禁用）为 /etc/init.d/<名> <动作>；
 *  - 本地启动脚本即 /etc/rc.local（保存时去 CRLF、补尾部换行，LuCI 同款）。
 *  全部操作需要设备开启 SSH。
 */
class StartupClient {

    suspend fun load(config: RouterConfig, ssh: SshConfig?): StartupData = withContext(Dispatchers.IO) {
        val s = ssh ?: throw RouterException(
            "启动项需要 SSH 访问。",
            "请在设备编辑页开启 SSH 后重试。"
        )
        val script = listOf(
            "echo __RC__", "ls /etc/rc.d 2>/dev/null",
            "echo __INIT__",
            "for f in /etc/init.d/*; do [ -x \"\$f\" ] || continue; " +
                "st=\$(grep -m1 -E '^START=[-0-9]+' \"\$f\" 2>/dev/null | cut -d= -f2); " +
                "echo \"\$(basename \"\$f\")|\$st\"; done",
            "echo __LOCAL__", "cat /etc/rc.local 2>/dev/null"
        ).joinToString("; ")
        val out = SshExec.run(s, script, 30_000)
        val parts = out.split(Regex("__(?:RC|INIT|LOCAL)__"))

        val rcLinks = parts.getOrNull(1)?.trim().orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val initLines = parts.getOrNull(2)?.trim().orEmpty()
        val rcLocal = parts.getOrNull(3)?.trim().orEmpty()

        val scripts = initLines.lineSequence()
            .map { it.trim() }
            .filter { it.contains('|') }
            .mapNotNull { line ->
                val idx = line.lastIndexOf('|')
                if (idx <= 0) return@mapNotNull null
                val name = line.substring(0, idx)
                val startVar = line.substring(idx + 1)
                val sLink = rcLinks.firstOrNull { it.startsWith("S") && it.endsWith(name) }
                val enabled = sLink != null
                val priority = sLink?.let { link ->
                    Regex("^S(\\d+)").find(link)?.groupValues?.get(1)
                }?.toIntOrNull() ?: startVar.toIntOrNull()
                InitScript(name = name, enabled = enabled, priority = priority)
            }
            .sortedWith(compareBy({ it.priority ?: 999 }, { it.name }))
            .toList()
        StartupData(scripts = scripts, rcLocal = rcLocal)
    }

    /** 执行 /etc/init.d/<name> <action>（start/stop/restart/reload/enable/disable）。 */
    suspend fun runAction(config: RouterConfig, ssh: SshConfig?, name: String, action: String) =
        withContext(Dispatchers.IO) {
            val s = ssh ?: throw RouterException(
                "执行动作需要 SSH 访问。",
                "请在设备编辑页开启 SSH 后重试。"
            )
            SshExec.run(s, "/etc/init.d/${shq(name)} $action 2>&1", 30_000)
            Unit
        }

    /** 保存 /etc/rc.local（trim、CRLF→LF、补尾部换行，LuCI 同款）。 */
    suspend fun saveRcLocal(config: RouterConfig, ssh: SshConfig?, content: String) =
        withContext(Dispatchers.IO) {
            val s = ssh ?: throw RouterException(
                "保存本地启动脚本需要 SSH 访问。",
                "请在设备编辑页开启 SSH 后重试。"
            )
            val normalized = content.replace("\r\n", "\n").trim() + "\n"
            SshFiles.upload(s, "/etc/rc.local", normalized.toByteArray(Charsets.UTF_8))
        }

    private fun shq(v: String): String = "'" + v.replace("'", "'\\''") + "'"
}
