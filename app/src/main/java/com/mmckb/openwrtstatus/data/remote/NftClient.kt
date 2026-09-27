package com.mmckb.openwrtstatus.data.remote

import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.ssh.SshExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** nftables 表（family + 名称，如 inet/fw4）。 */
data class NftTableSpec(val family: String, val name: String)

/** nftables 链（所属表 + 类型/hook/优先级/策略）。 */
data class NftChainSpec(
    val family: String,
    val table: String,
    val name: String,
    val type: String?,
    val hook: String?,
    val prio: String?,
    val policy: String?
)

/** nftables 规则（表达式以原始 JSON 保留，由 NftRenderer 渲染为可读文本）。 */
data class NftRuleSpec(
    val family: String,
    val table: String,
    val chain: String,
    val exprs: JsonArray?,
    val comment: String?
)

/** nftables 规则集解析结果。 */
data class NftRuleset(
    val tables: List<NftTableSpec>,
    val chains: List<NftChainSpec>,
    val rules: List<NftRuleSpec>
)

/** iptables 一条规则（iptables -nvxL 行）。 */
data class IptRule(
    val num: Int,
    val pkts: Long,
    val bytes: Long,
    val target: String,
    val proto: String,
    val inDev: String,
    val outDev: String,
    val src: String,
    val dst: String,
    val options: String,
    val comment: String
)

/** iptables 链（策略或被引用次数）。 */
data class IptChain(
    val name: String,
    val policy: String?,
    val packets: Long?,
    val bytes: Long?,
    val references: Int?,
    val rules: List<IptRule>
)

/** 一个 iptables 表（如 filter/nat/mangle/raw）。 */
data class IptTableDump(val name: String, val chains: List<IptChain>)

/** 一个地址族的全部 iptables 表。 */
data class IptFamilyDump(val tables: List<IptTableDump>)

/** 防火墙页完整数据。 */
data class FirewallData(
    val ruleset: NftRuleset,
    val ipt4: List<IptTableDump>,
    val ipt6: List<IptTableDump>,
    val legacy4: String,
    val legacy6: String,
    val hasLegacy: Boolean
)

/**
 * 防火墙状态数据层（LuCI admin/status/nftables 与 nftables/iptables 子页的完整复刻）：
 *  - nftables：SSH `nft --terse --json list ruleset`（与 LuCI 同款命令），表达式原始
 *    JSON 由 [NftRenderer] 渲染为「规则匹配 / 规则操作」徽标文本；
 *  - 旧版规则检测：`iptables-save` / `ip6tables-save` 输出含 `-A ` 行即视为存在
 *    旧版 iptables 规则（LuCI checkLegacyRules 同款），黄色警告提示并提供
 *    iptables 规则概况子页；
 *  - iptables 概况：`iptables/ip6tables -w -nvxL -t filter|nat|mangle|raw`。
 */
class NftClient(private val rpc: UbusRpcClient = UbusRpcClient()) {

    suspend fun load(config: RouterConfig, ssh: SshConfig?): FirewallData = withContext(Dispatchers.IO) {
        if (ssh == null) {
            throw RouterException(
                "防火墙状态需要 SSH 访问。",
                "请在设备编辑页开启 SSH 后重试。"
            )
        }
        val script = listOf(
            "echo __NFT__", "nft --terse --json list ruleset 2>/dev/null",
            "echo __I4SAVE__", "iptables-save 2>/dev/null",
            "echo __I6SAVE__", "ip6tables-save 2>/dev/null",
            "echo __I4F__", "iptables -w -nvxL -t filter 2>/dev/null",
            "echo __I4N__", "iptables -w -nvxL -t nat 2>/dev/null",
            "echo __I4M__", "iptables -w -nvxL -t mangle 2>/dev/null",
            "echo __I4R__", "iptables -w -nvxL -t raw 2>/dev/null",
            "echo __I6F__", "ip6tables -w -nvxL -t filter 2>/dev/null",
            "echo __I6N__", "ip6tables -w -nvxL -t nat 2>/dev/null",
            "echo __I6M__", "ip6tables -w -nvxL -t mangle 2>/dev/null",
            "echo __I6R__", "ip6tables -w -nvxL -t raw 2>/dev/null"
        ).joinToString("; ")
        val out = SshExec.run(ssh, script, 60_000)
        val parts = out.split(Regex("__(?:NFT|I4SAVE|I6SAVE|I4F|I4N|I4M|I4R|I6F|I6N|I6M|I6R)__"))
        fun sec(i: Int): String = parts.getOrNull(i + 1)?.trim().orEmpty()

        val ruleset = parseNft(sec(0))
        val ipt4 = listOf(
            IptTableDump("Filter", parseIptablesDump(sec(3))),
            IptTableDump("NAT", parseIptablesDump(sec(4))),
            IptTableDump("Mangle", parseIptablesDump(sec(5))),
            IptTableDump("Raw", parseIptablesDump(sec(6)))
        ).filter { it.chains.isNotEmpty() }
        val ipt6 = listOf(
            IptTableDump("Filter", parseIptablesDump(sec(7))),
            IptTableDump("NAT", parseIptablesDump(sec(8))),
            IptTableDump("Mangle", parseIptablesDump(sec(9))),
            IptTableDump("Raw", parseIptablesDump(sec(10)))
        ).filter { it.chains.isNotEmpty() }
        val legacy4 = sec(1)
        val legacy6 = sec(2)
        FirewallData(
            ruleset = ruleset,
            ipt4 = ipt4,
            ipt6 = ipt6,
            legacy4 = legacy4,
            legacy6 = legacy6,
            hasLegacy = Regex("(?m)^-A ").containsMatchIn(legacy4) ||
                Regex("(?m)^-A ").containsMatchIn(legacy6)
        )
    }

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private fun parseNft(text: String): NftRuleset {
        if (text.isBlank()) return NftRuleset(emptyList(), emptyList(), emptyList())
        return runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val entries = root["nftables"] as? JsonArray ?: JsonArray(emptyList())
            val tables = mutableListOf<NftTableSpec>()
            val chains = mutableListOf<NftChainSpec>()
            val rules = mutableListOf<NftRuleSpec>()
            entries.forEach { el ->
                val obj = el as? JsonObject ?: return@forEach
                obj["table"]?.jsonObject?.let { t ->
                    tables.add(
                        NftTableSpec(
                            family = str(t, "family") ?: "",
                            name = str(t, "name") ?: ""
                        )
                    )
                }
                obj["chain"]?.jsonObject?.let { c ->
                    chains.add(
                        NftChainSpec(
                            family = str(c, "family") ?: "",
                            table = str(c, "table") ?: "",
                            name = str(c, "name") ?: "",
                            type = str(c, "type"),
                            hook = str(c, "hook"),
                            prio = str(c, "prio"),
                            policy = str(c, "policy")
                        )
                    )
                }
                obj["rule"]?.jsonObject?.let { r ->
                    rules.add(
                        NftRuleSpec(
                            family = str(r, "family") ?: "",
                            table = str(r, "table") ?: "",
                            chain = str(r, "chain") ?: "",
                            exprs = r["expr"] as? JsonArray,
                            comment = str(r, "comment")
                        )
                    )
                }
            }
            NftRuleset(tables, chains, rules)
        }.getOrDefault(NftRuleset(emptyList(), emptyList(), emptyList()))
    }

    /** 解析 iptables -nvxL 文本输出（LuCI parseIptablesDump 同款正则语义）。 */
    private fun parseIptablesDump(text: String): List<IptChain> {
        val chains = mutableListOf<IptChain>()
        var rules = mutableListOf<IptRule>()
        var chainName: String? = null
        var policy: String? = null
        var packets: Long? = null
        var bytes: Long? = null
        var references: Int? = null

        fun flush() {
            val name = chainName ?: return
            chains.add(IptChain(name, policy, packets, bytes, references, rules.toList()))
            chainName = null; policy = null; packets = null; bytes = null; references = null
            rules = mutableListOf()
        }

        val ruleRe = Regex(
            "^(\\d+) +(\\d+) +(\\d+) +(.*?) +(\\S+) +(\\S*) +(\\S+) +(\\S+) +(!?[a-f0-9:.]+(?:/[a-f0-9:.]+)?) +(!?[a-f0-9:.]+(?:/[a-f0-9:.]+)?) +(.+)$"
        )
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            val chainPolicy = Regex("^Chain (.+) \\(policy (\\w+) (\\d+) packets, (\\d+) bytes\\)$").find(line)
            val chainRefs = Regex("^Chain (.+) \\((\\d+) references\\)$").find(line)
            when {
                chainPolicy != null -> {
                    flush()
                    chainName = chainPolicy.groupValues[1]
                    policy = chainPolicy.groupValues[2]
                    packets = chainPolicy.groupValues[3].toLongOrNull()
                    bytes = chainPolicy.groupValues[4].toLongOrNull()
                }
                chainRefs != null -> {
                    flush()
                    chainName = chainRefs.groupValues[1]
                    references = chainRefs.groupValues[2].toIntOrNull()
                }
                line.startsWith("num ") || line.isBlank() -> return@forEach
                else -> {
                    val m = ruleRe.find(line) ?: return@forEach
                    var options = m.groupValues[11].trim()
                    var comment = "-"
                    options = options.replace(Regex("(?:^| )/\\* (.+) \\*/")) { mr ->
                        comment = mr.groupValues[1].replace(Regex("^!fw3(: |$)"), "").trim().ifEmpty { "-" }
                        ""
                    }.trim().ifEmpty { "-" }
                    rules.add(
                        IptRule(
                            num = m.groupValues[1].toIntOrNull() ?: 0,
                            pkts = m.groupValues[2].toLongOrNull() ?: 0,
                            bytes = m.groupValues[3].toLongOrNull() ?: 0,
                            target = m.groupValues[4],
                            proto = m.groupValues[5],
                            inDev = m.groupValues[7],
                            outDev = m.groupValues[8],
                            src = m.groupValues[9],
                            dst = m.groupValues[10],
                            options = options,
                            comment = comment
                        )
                    )
                }
            }
        }
        flush()
        return chains
    }
}
