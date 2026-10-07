package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 一个接口的 UCI 配置（network 段 + 关联的 dhcp 段）。 */
data class IfaceUci(
    val section: String,
    val name: String,
    val proto: String?,
    val device: String?,
    val ipaddr: String?,
    val netmask: String?,
    val gateway: String?,
    val dns: List<String>,
    val pppoeUser: String?,
    val pppoePass: String?,
    val dhcpSection: String?,
    val dhcpEnabled: Boolean,
    val dhcpStart: String?,
    val dhcpLimit: String?,
    val dhcpLeasetime: String?
)

/**
 * 接口配置编辑数据层（LuCI admin/network/interfaces 编辑弹窗的复刻）：
 *  - 读取：ubus `uci get network` + `uci get dhcp`（关联 .interface==ifname 的 dhcp 段）；
 *  - 保存/删除/新建走 SSH：uci set/delete + commit network,dhcp + network reload + dnsmasq restart
 *    （一条脚本内完成，先回显标记再重启服务）。
 *  全部编辑操作需要设备开启 SSH。
 */
class NetworkConfigClient {

    suspend fun loadIfaceUcis(config: RouterConfig): List<IfaceUci> = withContext(Dispatchers.IO) {
        val endpoint = rpc.buildEndpoint(config.ip, config.port, config.useHttps)
        val token = rpc.login(endpoint, config.username, config.password, config.allowInsecureTls, true)
        val net = runCatching {
            rpc.call(endpoint, token, "uci", "get", json { put("config", JsonPrimitive("network")) }, config.allowInsecureTls, true)
        }.getOrNull()
        val dhcp = runCatching {
            rpc.call(endpoint, token, "uci", "get", json { put("config", JsonPrimitive("dhcp")) }, config.allowInsecureTls, true)
        }.getOrNull()
        parseIfaceUcis(
            net?.let { (it as? JsonObject)?.get("values")?.toString() } ?: "",
            dhcp?.let { (it as? JsonObject)?.get("values")?.toString() } ?: ""
        )
    }

    suspend fun saveIface(
        config: RouterConfig,
        ssh: SshConfig,
        section: String,
        isNew: Boolean,
        values: Map<String, String>,
        dns: List<String>,
        dhcp: Map<String, String>
    ) = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        if (isNew) {
            sb.append("S=$(uci -q add network interface) && uci rename network.$S=").append(shq(section)).append(" && ")
        }
        for ((k, v) in values) {
            val key = shq(k)
            if (v.isEmpty()) sb.append("uci -q delete network.").append(shq(section)).append('.').append(key).append("; ")
            else sb.append("uci set network.").append(shq(section)).append('.').append(key)
                .append("='").append(v.replace("'", "'\\''")).append("'; ")
        }
        for (line in dns) {
            sb.append("uci add_list network.").append(shq(section)).append(".dns=").append(shq(line)).append("; ")
        }
        // dhcp 段：找 .interface==section 的既有段；有值且不存在时新建；空值=删除字段
        sb.append("D=''; ")
            .append("for s in $(uci -q show dhcp | sed -n 's/^\\(.*\\)=dhcp$/\\1/p'); do ")
            .append("[ \"$(uci -q get dhcp.$s.interface)\" = ").append(shq(section)).append(" ] && D=$s; done; ")
        if (dhcp.isNotEmpty()) {
            sb.append("[ -z \"$D\" ] && { D=$(uci -q add dhcp dhcp); uci set dhcp.$D.interface=").append(shq(section)).append("; }; ")
            for ((k, v) in dhcp) {
                val key = shq(k)
                if (v.isEmpty()) sb.append("uci -q delete dhcp.$D.").append(key).append("; ")
                else sb.append("uci set dhcp.$D.").append(key).append("='").append(v.replace("'", "'\\''")).append("'; ")
            }
            sb.append("uci -q delete dhcp.$D.ignore; ")
        } else {
            sb.append("[ -n \"$D\" ] && uci -q set dhcp.$D.ignore='1'; ")
        }
        sb.append("uci commit network; uci commit dhcp; ")
        sb.append("/etc/init.d/network reload >/dev/null 2>&1; ")
        sb.append("/etc/init.d/dnsmasq restart >/dev/null 2>&1; ")
        sb.append("echo __NIF_SAVE_OK__")
        val ok = SshExec.run(ssh, sb.toString(), 30_000).contains("__NIF_SAVE_OK__")
        if (!ok) throw RouterException(
            "保存失败：接口配置未能写入。",
            "请检查 SSH 连接后重试。"
        )
    }

    suspend fun deleteIface(config: RouterConfig, ssh: SshConfig, section: String, ifname: String) =
        withContext(Dispatchers.IO) {
            val script = StringBuilder()
                .append("uci -q delete network.").append(shq(section)).append("; ")
                .append("for s in $(uci -q show dhcp | sed -n 's/^\\(.*\\)=dhcp$/\\1/p'); do ")
                .append("[ \"$(uci -q get dhcp.$s.interface)\" = ").append(shq(ifname)).append(" ] && uci -q delete dhcp.$s; done; ")
                .append("uci commit network; uci commit dhcp; ")
                .append("/etc/init.d/network reload >/dev/null 2>&1; ")
                .append("echo __NIF_DEL_OK__")
            val ok = SshExec.run(ssh, script, 30_000).contains("__NIF_DEL_OK__")
            if (!ok) throw RouterException("删除失败：接口配置未能写入。", "请检查 SSH 连接后重试。")
        }

    /** 解析 network + dhcp 的 uci values JSON 为逐接口配置（internal 供测试）。 */
    internal fun parseIfaceUcis(netValuesRaw: String, dhcpValuesRaw: String): List<IfaceUci> {
        val net = runCatching { Json.parseToJsonElement(netValuesRaw.trim()).jsonObject }.getOrNull()
        val dhs = runCatching { Json.parseToJsonElement(dhcpValuesRaw.trim()).jsonObject }.getOrNull()
        val out = mutableListOf<IfaceUci>()
        for ((_, el) in net?.entries ?: emptyList()) {
            val sec = el as? JsonObject ?: continue
            if (str(sec, ".type") != "interface") continue
            val section = str(sec, ".name") ?: continue
            val dhcpEntry = dhs?.entries?.firstOrNull { (_, de) ->
                val d = de as? JsonObject ?: return@firstOrNull false
                str(d, "interface") == section
            }
            val dsec = dhcpEntry?.value as? JsonObject
            out.add(
                IfaceUci(
                    section = section,
                    name = str(sec, ".name") ?: section,
                    proto = str(sec, "proto"),
                    device = str(sec, "device"),
                    ipaddr = str(sec, "ipaddr"),
                    netmask = str(sec, "netmask"),
                    gateway = str(sec, "gateway"),
                    dns = (sec["dns"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }
                        ?: str(sec, "dns")?.split(Regex("[, ]+"))?.filter { it.isNotEmpty() }
                        ?: emptyList(),
                    pppoeUser = str(sec, "username"),
                    pppoePass = str(sec, "password"),
                    dhcpSection = dhcpEntry?.key,
                    dhcpEnabled = dsec?.let { str(it, "ignore") != "1" } ?: false,
                    dhcpStart = dsec?.let { str(it, "start") },
                    dhcpLimit = dsec?.let { str(it, "limit") },
                    dhcpLeasetime = dsec?.let { str(it, "leasetime") }
                )
            )
        }
        return out
    }

    private fun str(section: JsonObject, key: String): String? =
        (section[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun shq(v: String): String = "'" + v.replace("'", "'\\''") + "'"
}
