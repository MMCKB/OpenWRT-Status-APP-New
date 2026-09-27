package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 日志页数据（系统日志 logread + 内核日志 dmesg）。 */
data class LogsData(
    val syslog: String,
    val kernel: String
)

/**
 * 日志数据层（LuCI admin/status/logs = status/syslog + status/dmesg 的复刻）：
 *  - 系统日志：`logread`（与 LuCI LogreadBox 的 ubus log read 同源，尾部 1000 行）；
 *  - 内核日志：`dmesg`（LuCI 用 dmesg -r 解析级别后展示正文，显示效果即纯文本 dmesg）。
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
            "echo __KRN__", "dmesg 2>/dev/null | tail -n 1000"
        ).joinToString("; ")
        val out = SshExec.run(ssh, script, 30_000)
        val parts = out.split(Regex("__(?:SYS|KRN)__"))
        LogsData(
            syslog = parts.getOrNull(1)?.trim().orEmpty(),
            kernel = parts.getOrNull(2)?.trim().orEmpty()
        )
    }
}
