package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.remote.FirewallData
import com.mmckb.openwrtstatus.data.remote.IptChain
import com.mmckb.openwrtstatus.data.remote.NftChainSpec
import com.mmckb.openwrtstatus.data.remote.NftRenderer
import com.mmckb.openwrtstatus.data.remote.NftTableSpec
import com.mmckb.openwrtstatus.data.remote.RouterException
import com.mmckb.openwrtstatus.data.remote.RoutesClient
import com.mmckb.openwrtstatus.ui.components.AppAlertType
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.ConnectionMonitor
import com.mmckb.openwrtstatus.ui.components.SmoothOptionSwitcher
import com.mmckb.openwrtstatus.ui.components.StackedAlertHost
import com.mmckb.openwrtstatus.ui.components.rememberAlertStackState
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.mmckb.openwrtstatus.data.remote.NftRuleSpec

/** 防火墙页数据加载的总时长上限（nft 规则集可能较大）。 */
private const val FW_LOAD_TIMEOUT_MS = 60_000L

/** 统一的失败文案：超时（连接中断/无响应）给出可操作的提示，其余透出原始信息。 */
private fun fwErrText(e: Exception): String = when {
    e is TimeoutCancellationException -> "路由器连接中断或长时间无响应，请检查网络后重试。"
    e is CancellationException -> "连接已断开，操作已终止。"
    else -> e.message ?: "请稍后重试。"
}

private fun fwFamilyTableTitle(family: String, name: String): String = when (family) {
    "ip" -> "IPv4 流量表 \"$name\""
    "ip6" -> "IPv6 流量表 \"$name\""
    "inet" -> "IPv4/IPv6 流量表 \"$name\""
    "arp" -> "ARP 流量表 \"$name\""
    "bridge" -> "桥接流量表 \"$name\""
    "netdev" -> "网络设备流量表 \"$name\""
    else -> "流量表 \"$name\"（$family）"
}

private val FW_HOOK_LABELS = mapOf(
    "ingress" to "网卡接收后直接捕获数据包",
    "prerouting" to "捕获路由决定之前的传入数据包",
    "input" to "捕获发往本机的数据包",
    "forward" to "捕获转发到其他主机的数据包",
    "output" to "捕获本机发出的数据包",
    "postrouting" to "捕获路由决定之后发出的数据包"
)

private val FW_POLICY_LABELS = mapOf(
    "drop" to "丢弃未匹配的数据包",
    "accept" to "继续处理未匹配的数据包"
)

private val FW_TYPE_LABELS = mapOf(
    "filter" to "流量过滤链",
    "route" to "路由动作链",
    "nat" to "NAT 动作链"
)

/**
 * 防火墙页（LuCI admin/status/nftables 与 nftables/iptables 子页的完整复刻，只读）：
 * nftables 规则集按 表 → 链 → 规则 展示（规则分「规则匹配 / 规则操作」两栏徽标）；
 * 检测到旧版 iptables 规则时顶部黄色警告并提供「iptables 规则概况」子页
 * （Filter/NAT/Mangle/Raw 四表，iptables + ip6tables）。
 */
@Composable
fun NftablesScreen(
    config: RouterConfig,
    sshEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val client = remember { RoutesClient() }
    val nftClient = remember { com.mmckb.openwrtstatus.data.remote.NftClient() }
    val sshOrNull = remember(config) {
        if (sshEnabled) {
            SshConfig(
                host = config.sshHost.ifBlank { config.ip },
                port = config.sshPort,
                username = config.sshUsername,
                password = config.sshPassword
            )
        } else null
    }

    var loading by remember { mutableStateOf(true) }
    var data by remember { mutableStateOf<FirewallData?>(null) }
    val alertStack = rememberAlertStackState()
    val density = LocalDensity.current
    var alertTopPadding by remember { mutableStateOf(0.dp) }
    var iptFamily by remember { mutableStateOf("iptables") }
    var showIptables by remember { mutableStateOf(false) }

    fun setAlert(type: AppAlertType, title: String, description: String? = null) {
        alertStack.push(type, title, description)
    }

    /** 读取前的快速检查：已断开则直接红色提示，不再发起操作。 */
    fun ensureConnected(): Boolean {
        if (ConnectionMonitor.status.value == ConnectionMonitor.Status.Offline) {
            setAlert(AppAlertType.Error, "与路由器已断开连接", "请检查手机与路由器的网络后重试。")
            return false
        }
        return true
    }

    // 跟踪读取任务；路由器断开连接时立即取消，界面即刻恢复
    var opJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) {
        ConnectionMonitor.status.collect { status ->
            if (status == ConnectionMonitor.Status.Offline) opJob?.cancel()
        }
    }

    fun load() {
        if (opJob?.isActive == true) return
        if (!ensureConnected()) {
            loading = false
            return
        }
        opJob = scope.launch {
            loading = true
            try {
                data = withTimeout(FW_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { nftClient.load(config, sshOrNull) }
                }
            } catch (e: RouterException) {
                setAlert(AppAlertType.Error, "防火墙状态读取失败", fwErrText(e))
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "防火墙状态读取失败", fwErrText(e))
            } finally {
                loading = false
            }
        }
    }

    var loadedOnce by remember { mutableStateOf(false) }
    if (!loadedOnce) {
        loadedOnce = true
        load()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showIptables) {
                    IconButton(onClick = { showIptables = false }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回 nftables 规则集",
                            tint = colors.onSurface
                        )
                    }
                } else {
                    AppBackButton(onBack = onBack)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { load() }, enabled = !loading) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        tint = colors.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Text(
                if (showIptables) "iptables 规则概况" else "防火墙",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Text(
                if (showIptables) "旧版 iptables 的 Filter/NAT/Mangle/Raw 表"
                else "当前生效的防火墙规则集",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.onGloballyPositioned { coords ->
                    // 提示栈锚定在标题区正下方
                    alertTopPadding = with(density) {
                        (coords.positionInParent().y + coords.size.height).toDp() + 8.dp
                    }
                }
            )
            Spacer(Modifier.height(10.dp))

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
            } else {
                val d = data
                if (d == null) {
                    Text(
                        "暂无数据，点击右上角刷新重试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                } else if (showIptables) {
                    IptablesOverview(data = d, family = iptFamily, onFamilyChange = { iptFamily = it })
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (d.hasLegacy) {
                            item {
                                LegacyNoticeCard(onOpen = { showIptables = true })
                            }
                        }
                        if (d.ruleset.tables.isEmpty()) {
                            item {
                                Text(
                                    "未加载 nftables 规则集。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                        d.ruleset.tables.forEach { t ->
                            item {
                                val chains = d.ruleset.chains.filter {
                                    it.family == t.family && it.table == t.name
                                }
                                val rules = d.ruleset.rules.filter {
                                    it.family == t.family && it.table == t.name
                                }
                                NftTableCard(t, chains, rules)
                            }
                        }
                    }
                }
            }
        }

        // 悬浮提示栈：浮在内容上方（不推挤布局），位于标题区正下方
        StackedAlertHost(
            state = alertStack,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = alertTopPadding)
        )
    }
}

/** 顶部「检测到旧版规则」黄色警告卡，含跳转 iptables 概况的按钮。 */
@Composable
private fun LegacyNoticeCard(onOpen: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFFCF1CC))
            .border(1.dp, Color(0xFFE0C46E), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "检测到旧版规则",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF8A6A10)
        )
        Text(
            "系统上存在旧版 iptables 规则。不鼓励混合使用 iptables 和 nftables 规则，" +
                "这可能会导致流量过滤不完整。",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8A6A10)
        )
        Button(onClick = onOpen) { Text("打开 iptables 规则概况…") }
    }
}

/** nftables 一张表的卡片：按链分节，规则分「匹配 / 操作」两栏。 */
@Composable
private fun NftTableCard(
    table: NftTableSpec,
    chains: List<NftChainSpec>,
    rules: List<NftRuleSpec>
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            fwFamilyTableTitle(table.family, table.name),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface
        )
        if (chains.isEmpty()) {
            Text("该表没有链。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        chains.forEach { chain ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    (FW_TYPE_LABELS[chain.type] ?: "规则容器链") + " \"${chain.name}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                if (!chain.hook.isNullOrBlank()) {
                    Text(
                        "Hook: ${chain.hook}（${FW_HOOK_LABELS[chain.hook] ?: chain.hook}），" +
                            "优先级 ${chain.prio ?: "0"} · " +
                            "策略: ${chain.policy ?: "accept"}（${FW_POLICY_LABELS[chain.policy ?: "accept"]}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                val chainRules = rules.filter {
                    it.family == chain.family && it.table == chain.table && it.chain == chain.name
                }
                if (chainRules.isEmpty()) {
                    Text(
                        "该链没有规则。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                chainRules.forEach { rule ->
                    val (matches, actions) = NftRenderer.renderRule(rule)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(0.6f)) {
                            if (matches.isEmpty()) {
                                NftBadge("任意数据包")
                            } else {
                                FlowBadgeRow(matches)
                            }
                        }
                        Column(modifier = Modifier.weight(0.4f)) {
                            if (actions.isNotEmpty()) FlowBadgeRow(actions)
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .height(1.dp)
                            .background(colors.outline)
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowBadgeRow(items: List<String>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { NftBadge(it) }
    }
}

@Composable
private fun NftBadge(text: String) {
    val colors = LocalAppColors.current
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** iptables 规则概况子页：iptables / ip6tables 页签 + Filter/NAT/Mangle/Raw 四表。 */
@Composable
private fun IptablesOverview(
    data: FirewallData,
    family: String,
    onFamilyChange: (String) -> Unit
) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        SmoothOptionSwitcher(
            options = listOf("iptables" to "iptables", "ip6tables" to "ip6tables"),
            selected = family,
            onSelect = onFamilyChange,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        val dump = if (family == "iptables") data.ipt4 else data.ipt6
        if (dump.isEmpty()) {
            Text(
                if (family == "iptables") "未安装 iptables 或没有可显示的表。"
                else "未安装 ip6tables 或没有可显示的表。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                dump.forEach { table ->
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "${table.name} 表",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            table.chains.forEach { chain ->
                                IptChainSection(chain)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IptChainSection(chain: IptChain) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "链 \"${chain.name}\"",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface
        )
        val meta = when {
            chain.policy != null ->
                "策略 ${chain.policy} · ${chain.packets ?: 0} 包 · ${NftRenderer.humanBytes(chain.bytes ?: 0)}"
            chain.references != null -> "${chain.references} 次引用"
            else -> ""
        }
        if (meta.isNotBlank()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (chain.rules.isEmpty()) {
            Text("该链没有规则。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        chain.rules.forEach { r ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${NftRenderer.humanBytes(r.pkts)} · ${NftRenderer.humanBytes(r.bytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.width(140.dp)
                    )
                    Text(
                        r.target.ifEmpty { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (r.target.isEmpty()) colors.onSurfaceVariant else colors.primary
                    )
                }
                Text(
                    "协议 ${r.proto} · 入 ${r.inDev} · 出 ${r.outDev} · " +
                        "源 ${r.src} → 目标 ${r.dst}" +
                        (if (r.options != "-") " · ${r.options}" else "") +
                        (if (r.comment != "-") " · # ${r.comment}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}
