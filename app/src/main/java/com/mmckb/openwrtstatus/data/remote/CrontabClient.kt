package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import com.mmckb.openwrtstatus.data.ssh.SshFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 计划任务数据层（LuCI admin/system/crontab 的完整复刻）：
 *  - 读取：/etc/crontabs/root（与 LuCI fs.read 同源）；
 *  - 保存：trim、CRLF→LF、补尾换行后 upload，chmod 0644 + /etc/init.d/cron reload
 *    （与 LuCI fs.write + cron reload 语义一致）。
 *  全部操作需要设备开启 SSH。
 */
class CrontabClient {

    suspend fun load(config: RouterConfig, ssh: SshConfig?): String = withContext(Dispatchers.IO) {
        val s = ssh ?: throw RouterException(
            "计划任务需要 SSH 访问。",
            "请在设备编辑页开启 SSH 后重试。"
        )
        SshExec.run(s, "cat /etc/crontabs/root 2>/dev/null", 15_000)
    }

    /** 保存并重载 cron：trim、CRLF→LF、补尾换行（LuCI 同款），失败抛异常。 */
    suspend fun save(config: RouterConfig, ssh: SshConfig?, content: String) =
        withContext(Dispatchers.IO) {
            val s = ssh ?: throw RouterException(
                "计划任务需要 SSH 访问。",
                "请在设备编辑页开启 SSH 后重试。"
            )
            val normalized = content.replace("\r\n", "\n").trim() + "\n"
            SshFiles.upload(s, "/etc/crontabs/root", normalized.toByteArray(Charsets.UTF_8))
            val ok = SshExec.run(
                s,
                "chmod 0644 /etc/crontabs/root && /etc/init.d/cron reload >/dev/null 2>&1 && echo __CRON_OK__ || echo __CRON_FAIL__",
                15_000
            ).contains("__CRON_OK__")
            if (!ok) throw RouterException(
                "保存失败：cron 重载未成功。",
                "请确认设备上的 cron 服务可用后重试。"
            )
        }
}
