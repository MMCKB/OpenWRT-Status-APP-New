package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 系统日志一行（logread：时间 + facility.severity + 标签 + 消息）。 */
data class SyslogEntry(
    val time: String,
    val facility: String,
    val severity: String,
    val tag: String,
    val msg: String
)

/** 内核日志一行（dmesg -r 解析：<级别> 前缀转为级别号，正文保留 [时间]）。 */
data class KernelLine(
    val severity: Int?,
    val text: String
)

/** 日志页数据。 */
data class LogsData(
    val syslog: List<SyslogEntry>,
    val kernel: List<KernelLine>
)

/**
 * 日志数据层（LuCI admin/status/logs = status/syslog + status/dmesg 的复刻）：
 *  - 系统日志：`logread`（与 LuCI LogreadBox 的 ubus log read 同源；该固件 logd 的
 *    ubus 接口返回为空，故走 SSH 文本并解析 facility/severity/标签）；
 *  - 内核日志：`dmesg -r`（与 LuCI 同款），解析 <级别> 前缀供筛选，
 *    -r 不可用时回退普通 dmesg（级别为空、筛选自然失效）。
 *  一次 SSH 连接用标记行切分全部输出；需要设备开启 SSH。
 */
class LogsClient {

    suspend fun load(config: RouterConfig, ssh: SshConfig?): LogsData = withContext(Dispatchers.IO) {
        if (ssh == null) {
            throw RouterException(
                "日志需要 SSH 访问。",
                "请在设备编辑页开启 SSH 后重试。"
            )
        }
        val script = listOf(
            "echo __SYS__", "logread 2>/dev/null | tail -n 1000",
            "echo __KRN__", "(dmesg -r 2>/dev/null || dmesg 2>/dev/null) | tail -n 1000"
        ).joinToString("; ")
        val out = SshExec.run(ssh, script, 30_000)
        val parts = out.split(Regex("__(?:SYS|KRN)__"))
        LogsData(
            syslog = parseSyslog(parts.getOrNull(1).orEmpty()),
            kernel = parseKernel(parts.getOrNull(2).orEmpty())
        )
    }

    /** logread 行：`Sun Sep 27 22:22:11 2026 authpriv.notice dropbear[26877]: msg`。 */
    private fun parseSyslog(text: String): List<SyslogEntry> {
        val re = Regex("^(.{24}) +(\\w+)\\.(\\w+) +(\\S+): (.*)$")
        return text.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val m = re.find(line)
            if (m != null) {
                SyslogEntry(
                    time = m.groupValues[1].trim(),
                    facility = m.groupValues[2],
                    severity = m.groupValues[3],
                    tag = m.groupValues[4],
                    msg = m.groupValues[5]
                )
            } else {
                SyslogEntry(time = "", facility = "", severity = "", tag = "", msg = line)
            }
        }.toList()
    }

    /** dmesg -r 行：`<3>[ 1234.5] msg`；`<c>` 为续行（继承上一条级别）。 */
    private fun parseKernel(text: String): List<KernelLine> {
        val re = Regex("^<(\\w+)>")
        val out = mutableListOf<KernelLine>()
        var lastSeverity: Int? = null
        text.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val m = re.find(line)
            if (m == null) return@forEach
            val tag = m.groupValues[1]
            val clean = line.replace(re, "")
            if (tag == "c") {
                out.add(KernelLine(lastSeverity, clean))
            } else {
                val sev = tag.toIntOrNull()
                lastSeverity = sev
                out.add(KernelLine(sev, clean))
            }
        }
        return out
    }
}
