package com.mmckb.openwrtstatus.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * nft 表达式渲染器（LuCI nftables.js 的 expr/op/action 翻译子集）：
 * 把 `nft --json` 规则的 expr 数组渲染为「规则匹配 / 规则操作」两组可读文本。
 */
object NftRenderer {

    /** 规则匹配键 → 中文名（expr_translations 子集，覆盖 fw4 常见键）。 */
    private val EXPR_KEYS = mapOf(
        "meta.iifname" to "入接口",
        "meta.oifname" to "出接口",
        "meta.iif" to "入接口 ID",
        "meta.oif" to "出接口 ID",
        "meta.l4proto" to "IP 协议",
        "meta.nfproto" to "地址族",
        "meta.mark" to "包标记",
        "meta.secmark" to "安全标记",
        "meta.hour" to "当前时间",
        "meta.day" to "当前星期",
        "ct.state" to "连接状态",
        "ct.status" to "连接状态位",
        "ct.helper" to "连接跟踪助手",
        "ip.saddr" to "源 IP",
        "ip.daddr" to "目标 IP",
        "ip.sport" to "源端口",
        "ip.dport" to "目标端口",
        "ip.protocol" to "IP 协议",
        "ip.dscp" to "DSCP",
        "ip6.saddr" to "源 IPv6",
        "ip6.daddr" to "目标 IPv6",
        "ip6.sport" to "IPv6 源端口",
        "ip6.dport" to "IPv6 目标端口",
        "ip6.dscp" to "DSCP",
        "ip6.nexthdr" to "IPv6 下一报头",
        "ether.saddr" to "源 MAC",
        "ether.daddr" to "目标 MAC",
        "icmp.type" to "ICMP 类型",
        "icmp.code" to "ICMP 代码",
        "icmpv6.type" to "ICMPv6 类型",
        "icmpv6.code" to "ICMPv6 代码",
        "tcp.sport" to "TCP 源端口",
        "tcp.dport" to "TCP 目标端口",
        "tcp.flags" to "TCP 标志",
        "udp.sport" to "UDP 源端口",
        "udp.dport" to "UDP 目标端口",
        "th.sport" to "传输层源端口",
        "th.dport" to "传输层目标端口",
        "rt.mtu" to "路由 MTU",
        "nfproto.ipv4" to "IPv4",
        "nfproto.ipv6" to "IPv6",
        "l4proto.tcp" to "TCP",
        "l4proto.udp" to "UDP",
        "l4proto.icmp" to "ICMP",
        "l4proto.icmpv6" to "ICMPv6",
        "ip.protocol.tcp" to "TCP",
        "ip.protocol.udp" to "UDP",
        "ip.protocol.icmp" to "ICMP",
        "ip.protocol.ipv6-icmp" to "ICMPv6"
    )

    private val OPS = mapOf(
        "==" to "是",
        "!=" to "非",
        ">" to ">",
        "<" to "<",
        ">=" to "≥",
        "<=" to "≤",
        "in" to "属于",
        "in_set" to "属于",
        "not_in_set" to "不属于"
    )

    private val ACTIONS = mapOf(
        "accept" to "接受数据包",
        "drop" to "丢弃数据包",
        "notrack" to "不跟踪连接",
        "reject" to "拒绝数据包",
        "continue" to "继续下一条规则",
        "return" to "返回调用链",
        "masquerade" to "改写为出口设备地址",
        "redirect" to "重定向到本机",
        "log" to "记录日志"
    )

    /** 1024 进制人性化字节（LuCI %.1024mB 同款：236.2 MB）。 */
    fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.1f GB".format(mb / 1024.0)
    }

    /** 渲染一条规则：返回 (匹配徽标列表, 动作徽标列表)。 */
    fun renderRule(rule: NftRuleSpec): Pair<List<String>, List<String>> {
        val matches = mutableListOf<String>()
        val actions = mutableListOf<String>()
        if (!rule.comment.isNullOrBlank()) {
            matches.add("# " + rule.comment.replace(Regex("^!fw4: "), ""))
        }
        val exprs = rule.exprs ?: return matches to actions
        exprs.forEach { se ->
            var e: JsonElement = se
            // `flow add @flowtable` 字符串 → flow 表达式（LuCI 同款还原）
            if (se is JsonPrimitive && Regex("^flow add (@\\S+)$").containsMatchIn(se.content)) {
                val flowtable = Regex("^flow add (@\\S+)$").find(se.content)!!.groupValues[1]
                e = buildJsonObject {
                    put("flow", buildJsonObject {
                        put("op", JsonPrimitive("add"))
                        put("flowtable", JsonPrimitive(flowtable))
                    })
                }
            }
            val obj = e as? JsonObject
            if (obj != null && "counter" in obj) {
                val c = obj["counter"] as? JsonObject
                val bytes = (c?.get("bytes") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
                matches.add(humanBytes(bytes))
                return@forEach
            }
            if (obj != null && "comment" in obj) {
                val c = (obj["comment"] as? JsonPrimitive)?.content ?: ""
                matches.add("# " + c.replace(Regex("^!fw4: "), ""))
                return@forEach
            }
            val text = renderExpr(e)
            if (text.isBlank()) return@forEach
            if (obj != null && isAction(obj)) actions.add(text) else matches.add(text)
        }
        return matches to actions
    }

    private fun isAction(obj: JsonObject): Boolean = obj.keys.any {
        it in setOf(
            "accept", "notrack", "reject", "drop", "jump", "goto", "continue",
            "snat", "dnat", "redirect", "mangle", "masquerade", "return", "flow",
            "ct helper", "log"
        )
    }

    /** 单个表达式 → 可读文本。 */
    private fun renderExpr(expr: JsonElement): String {
        if (expr is JsonPrimitive) return expr.content
        val obj = expr as? JsonObject ?: return ""
        val kind = obj.keys.firstOrNull() ?: return ""
        val spec = obj[kind] ?: return ""

        when (kind) {
            "match" -> {
                val m = spec as? JsonObject ?: return ""
                val op = (m["op"] as? JsonPrimitive)?.content ?: "=="
                var opText = OPS[op] ?: op
                val left = m["left"]
                val right = m["right"]
                val rightIsSet = (right as? JsonObject)?.containsKey("set") == true
                if (op == "==" && rightIsSet) opText = "属于"
                if (op == "!=" && rightIsSet) opText = "不属于"
                return "${exprToString(left, exprToKey(left))} $opText ${exprToString(right, exprToKey(left))}"
            }
            "accept" -> return ACTIONS["accept"]!!
            "drop" -> return ACTIONS["drop"]!!
            "notrack" -> return ACTIONS["notrack"]!!
            "return" -> return ACTIONS["return"]!!
            "continue" -> return ACTIONS["continue"]!!
            "masquerade" -> return ACTIONS["masquerade"]!!
            "log" -> {
                val l = spec as? JsonObject
                val prefix = l?.get("prefix")?.let { (it as? JsonPrimitive)?.content }
                return prefix?.let { "记录日志「$it」…" } ?: ACTIONS["log"]!!
            }
            "reject" -> {
                val r = spec as? JsonObject
                val type = r?.get("type")?.let { (it as? JsonPrimitive)?.content } ?: "default"
                return when {
                    type == "tcp reset" -> "拒绝数据包（TCP RST）"
                    type.startsWith("icmp") -> "拒绝数据包（ICMP $type）"
                    else -> "拒绝数据包"
                }
            }
            "jump" -> {
                val t = (spec as? JsonObject)?.get("target")?.let { (it as? JsonPrimitive)?.content } ?: ""
                return "在 $t 继续"
            }
            "goto" -> {
                val t = (spec as? JsonObject)?.get("target")?.let { (it as? JsonPrimitive)?.content } ?: ""
                return "跳转链 $t"
            }
            "snat" -> return renderNat(kind, spec, "改写源为")
            "dnat" -> return renderNat(kind, spec, "改写目标为")
            "redirect" -> {
                val port = (spec as? JsonObject)?.get("port")?.let { (it as? JsonPrimitive)?.content }
                return port?.let { "重定向到本机端口 $it" } ?: ACTIONS["redirect"]!!
            }
            "limit" -> {
                val l = spec as? JsonObject ?: return "限速"
                val rate = (l["rate"] as? JsonPrimitive)?.content ?: "?"
                val unit = (l["rate_unit"] as? JsonPrimitive)?.content ?: "packets"
                val per = (l["per"] as? JsonPrimitive)?.content ?: ""
                return "限速 $rate $unit / $per"
            }
            "mangle" -> {
                val m = spec as? JsonObject ?: return "mangle"
                val key = exprToString(m["key"])
                val value = exprToString(m["value"])
                return "设置 $key 为 $value"
            }
            "ct helper" -> {
                val h = (spec as? JsonPrimitive)?.content ?: ""
                return "使用连接跟踪助手 $h"
            }
            "flow" -> {
                val f = (spec as? JsonObject)?.get("flowtable")?.let { (it as? JsonPrimitive)?.content } ?: ""
                return "使用流表 ${f.removePrefix("@")}"
            }
            "xt" -> {
                val name = ((spec as? JsonObject)?.get("name") as? JsonPrimitive)?.content
                return name?.let { "{ $it }" } ?: "{ xt }"
            }
            "quota" -> return "配额 " + spec.toString()
            else -> return "{ $kind }"
        }
    }

    private fun renderNat(kind: String, spec: JsonElement, verb: String): String {
        val o = spec as? JsonObject
        val parts = mutableListOf<String>()
        o?.get("addr")?.let { parts.add(exprToString(it)) }
        o?.get("port")?.let { parts.add("端口 " + exprToString(it)) }
        val family = o?.get("family")?.let { (it as? JsonPrimitive)?.content } ?: ""
        val prefix = if (kind == "snat") "改写源为" else "改写目标为"
        return "$prefix ${parts.joinToString(", ")}${if (family.isNotBlank()) "（$family）" else ""}"
    }

    private fun exprToKey(expr: JsonElement?): String {
        val obj = expr as? JsonObject ?: return ""
        val kind = obj.keys.firstOrNull() ?: return ""
        val spec = obj[kind]
        return when (kind) {
            "meta", "ct", "rt" -> {
                val key = (spec as? JsonObject)?.get("key")?.let { (it as? JsonPrimitive)?.content } ?: ""
                "$kind.$key"
            }
            "payload" -> {
                val p = spec as? JsonObject
                val proto = p?.get("protocol")?.let { (it as? JsonPrimitive)?.content } ?: ""
                val field = p?.get("field")?.let { (it as? JsonPrimitive)?.content } ?: ""
                "$proto.$field".ifBlank { "" }
            }
            else -> ""
        }
    }

    /** 表达式 → 文本（值/集合/前缀/范围/拼接/载荷递归）。 */
    private fun exprToString(expr: JsonElement?, hint: String? = null): String {
        if (expr == null) return "—"
        if (expr is JsonPrimitive) {
            val value = expr.content
            return hint?.let { EXPR_KEYS["$it.$value"] } ?: value
        }
        if (expr is JsonArray) return expr.joinToString(" ") { exprToString(it) }
        val obj = expr as? JsonObject ?: return expr.toString()
        val kind = obj.keys.firstOrNull() ?: return ""
        val spec = obj[kind] ?: return ""
        when (kind) {
            "prefix" -> {
                val p = spec as? JsonObject
                val addr = p?.get("addr")?.let { (it as? JsonPrimitive)?.content } ?: "?"
                val len = p?.get("len")?.let { (it as? JsonPrimitive)?.content } ?: "?"
                return "$addr/$len"
            }
            "set", "list" -> {
                val items = (spec as? JsonArray)?.map { exprToString(it) } ?: emptyList()
                return "{ ${items.joinToString(", ")} }"
            }
            "range" -> {
                val arr = spec as? JsonArray ?: return spec.toString()
                val a = (arr.getOrNull(0) as? JsonPrimitive)?.content ?: "?"
                val b = (arr.getOrNull(1) as? JsonPrimitive)?.content ?: "?"
                return "$a-$b"
            }
            "concat" -> {
                val arr = spec as? JsonArray ?: return spec.toString()
                return arr.joinToString("+") { exprToString(it) }
            }
            "payload" -> {
                val p = spec as? JsonObject
                val proto = p?.get("protocol")?.let { (it as? JsonPrimitive)?.content } ?: ""
                val field = p?.get("field")?.let { (it as? JsonPrimitive)?.content } ?: ""
                if (proto.isNotBlank() && field.isNotBlank()) {
                    return EXPR_KEYS["$proto.$field"] ?: "$proto.$field"
                }
                val base = p?.get("base")?.let { (it as? JsonPrimitive)?.content } ?: ""
                return "@$base"
            }
            "meta", "ct", "rt" -> {
                val key = (spec as? JsonObject)?.get("key")?.let { (it as? JsonPrimitive)?.content } ?: ""
                return EXPR_KEYS["$kind.$key"] ?: "$kind $key"
            }
            "&", "|", "^" -> {
                val arr = spec as? JsonArray ?: return spec.toString()
                val a = arr.getOrNull(0)?.let { exprToString(it) } ?: "?"
                val b = arr.getOrNull(1)?.let { exprToString(it) } ?: "?"
                return "$a $kind $b"
            }
            "fib" -> return "FIB 查询"
            "numgen" -> return "随机数生成"
            "hash" -> return "哈希计算"
            "socket" -> return "socket 匹配"
            else -> {
                val key = exprToKey(obj)
                if (key.isNotBlank()) return EXPR_KEYS[key] ?: key
                return "$kind: ${spec.toString().take(80)}"
            }
        }
    }
}
