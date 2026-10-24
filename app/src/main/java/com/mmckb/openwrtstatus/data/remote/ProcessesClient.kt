package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** 路由器上的一个运行中进程（ubus luci getProcessList，LuCI「进程」页同源）。 */
data class ProcessInfo(
    val pid: Int,
    val user: String,
    val command: String,
    /** 形如 "0%"（原样保留，含百分号）。 */
    val cpuPercent: String,
    /** 形如 "323%"（busybox ps 的 %VSZ，可超 100%，原样保留）。 */
    val memPercent: String
)

/** 进程信号（与 LuCI 进程页三个按钮一一对应）。 */
enum class ProcessSignal(val signum: Int, val label: String) {
    HUP(1, "挂起"),
    TERM(15, "关闭"),
    KILL(9, "强制关闭")
}

/**
 * 进程页数据层（LuCI admin/status/processes 的复刻）：
 *  - 列表来自 ubus `luci getProcessList`（LuCI 视图同款接口），
 *    实测返回 {"result":[{PID,PPID,USER,STAT,VSZ,%MEM,%CPU,COMMAND},…]}，
 *    值均为字符串（%MEM/%CPU 带 % 号，%MEM 为 busybox 的 %VSZ 口径、可超 100%）；
 *    展示列与 LuCI 一致（PID / 所有者 / 命令 / CPU% / 内存%），并按 PID 升序排列；
 *  - 发送信号走 SSH `kill -<信号> <PID>`：本固件 luci 对象没有 setProcessSignal
 *    （LuCI 视图自身也是 fs.exec('/bin/kill')），退出码校验回显，stderr 并入判定。
 */
class ProcessesClient(private val rpc: UbusRpcClient = UbusRpcClient()) {

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

    private fun str(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** 读取进程列表（ubus luci getProcessList），按 PID 升序（LuCI 同款排序）。 */
    suspend fun load(config: RouterConfig): List<ProcessInfo> = withContext(Dispatchers.IO) {
        val payload = call(config, "luci", "getProcessList", buildJsonObject { })
        val entries = (payload.jsonObject["result"] as? JsonArray)
            ?: return@withContext emptyList()
        entries.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val pid = str(o, "PID")?.toIntOrNull() ?: return@mapNotNull null
            ProcessInfo(
                pid = pid,
                user = str(o, "USER") ?: "—",
                command = str(o, "COMMAND").orEmpty(),
                cpuPercent = str(o, "%CPU") ?: "0%",
                memPercent = str(o, "%MEM") ?: "0%"
            )
        }.sortedBy { it.pid }
    }

    /**
     * 向进程发送信号（SSH kill）。stderr 并入 stdout，失败时带回真实原因
     * （如 "No such process"）；进程不存在或权限不足均按失败处理。
     */
    suspend fun sendSignal(ssh: SshConfig?, pid: Int, signal: ProcessSignal) {
        val s = requireSsh(ssh)
        val out = SshExec.run(
            s,
            "kill -${signal.signum} $pid 2>&1 && echo __KILL_OK__ || echo __KILL_FAIL__",
            10_000
        )
        if (!out.contains("__KILL_OK__")) {
            val reason = out.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && it != "__KILL_FAIL__" }
            throw RouterException(
                "信号发送失败（PID $pid）。",
                reason ?: "进程可能已退出，请刷新列表后重试。"
            )
        }
    }

    private fun requireSsh(ssh: SshConfig?): SshConfig =
        ssh ?: throw RouterException(
            "需要 SSH 访问才能发送信号。",
            "请在设备编辑页开启 SSH 并保存后重试。"
        )
}
