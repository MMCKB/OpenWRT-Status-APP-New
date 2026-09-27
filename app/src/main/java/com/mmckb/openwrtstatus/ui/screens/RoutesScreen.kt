package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.remote.NeighEntry
import com.mmckb.openwrtstatus.data.remote.RouteEntry
import com.mmckb.openwrtstatus.data.remote.RouterException
import com.mmckb.openwrtstatus.data.remote.RoutingData
import com.mmckb.openwrtstatus.data.remote.RuleEntry
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

/** 路由页数据加载的总时长上限。 */
private const val ROUTES_LOAD_TIMEOUT_MS = 45_000L

/** 统一的失败文案：超时（连接中断/无响应）给出可操作的提示，其余透出原始信息。 */
private fun routesErrText(e: Exception): String = when {
    e is TimeoutCancellationException -> "路由器连接中断或长时间无响应，请检查网络后重试。"
    e is CancellationException -> "连接已断开，操作已终止。"
    else -> e.message ?: "请稍后重试。"
}

/**
 * 路由页（LuCI admin/status/routesj 的完整复刻，只读）：
 * IPv4 / IPv6 两个页签，各含邻居表、活动路由与路由规则三张卡片，
 * 数据来自 SSH `ip -j` 命令，逻辑接口名经 network.interface dump 映射。
 */
@Composable
fun RoutesScreen(
    config: RouterConfig,
    sshEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val client = remember { RoutesClient() }
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
    var data by remember { mutableStateOf<RoutingData?>(null) }
    val alertStack = rememberAlertStackState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var alertTopPadding by remember { mutableStateOf(0.dp) }
    var tab by remember { mutableStateOf("ipv4") }

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

    // 浏览超过一屏后，标题右侧出现「回到顶部」按钮
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val showBackToTop = listState.firstVisibleItemIndex > 0 ||
        listState.firstVisibleItemScrollOffset > 300

    fun load() {
        if (opJob?.isActive == true) return
        if (!ensureConnected()) {
            loading = false
            return
        }
        opJob = scope.launch {
            loading = true
            try {
                data = withTimeout(ROUTES_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.load(config, sshOrNull) }
                }
            } catch (e: RouterException) {
                setAlert(AppAlertType.Error, "路由表读取失败", routesErrText(e))
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "路由表读取失败", routesErrText(e))
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
            // 标题区（提示栈锚定其底部；浏览后右侧出现回到顶部按钮）
            Column(
                modifier = Modifier.onGloballyPositioned { coords ->
                    alertTopPadding = with(density) { coords.size.height.toDp() + 8.dp }
                }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppBackButton(onBack = onBack)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "路由表",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showBackToTop,
                        enter = androidx.compose.animation.fadeIn(tween(200)) +
                            androidx.compose.animation.scaleIn(
                                initialScale = 0.8f, animationSpec = tween(200)
                            ),
                        exit = androidx.compose.animation.fadeOut(tween(200)) +
                            androidx.compose.animation.scaleOut(
                                targetScale = 0.8f, animationSpec = tween(200)
                            )
                    ) {
                        IconButton(
                            onClick = { scope.launch { listState.animateScrollToItem(0) } },
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(colors.surfaceVariant)
                                .size(34.dp)
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowUp,
                                contentDescription = "回到顶部",
                                tint = colors.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                Text(
                    "当前生效的邻居、路由与策略规则",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            SmoothOptionSwitcher(
                options = listOf("ipv4" to "IPv4 路由", "ipv6" to "IPv6 路由"),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.fillMaxWidth()
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
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (tab == "ipv4") {
                            item { RoutesGroupCard("IPv4 邻居", d.v4Neigh.size) {
                                if (d.v4Neigh.isEmpty()) RoutesEmpty()
                                d.v4Neigh.forEach { NeighRow(it) }
                            } }
                            item { RoutesGroupCard("活跃的 IPv4 路由", d.v4Routes.size) {
                                if (d.v4Routes.isEmpty()) RoutesEmpty()
                                d.v4Routes.forEach { RouteRow(it) }
                            } }
                            item { RoutesGroupCard("活跃的 IPv4 规则", d.v4Rules.size) {
                                if (d.v4Rules.isEmpty()) RoutesEmpty()
                                d.v4Rules.forEach { RuleRow(it) }
                            } }
                        } else {
                            item { RoutesGroupCard("IPv6 邻居", d.v6Neigh.size) {
                                if (d.v6Neigh.isEmpty()) RoutesEmpty()
                                d.v6Neigh.forEach { NeighRow(it) }
                            } }
                            item { RoutesGroupCard("活跃的 IPv6 路由", d.v6Routes.size) {
                                if (d.v6Routes.isEmpty()) RoutesEmpty()
                                d.v6Routes.forEach { RouteRow(it) }
                            } }
                            item { RoutesGroupCard("活跃的 IPv6 规则", d.v6Rules.size) {
                                if (d.v6Rules.isEmpty()) RoutesEmpty()
                                d.v6Rules.forEach { RuleRow(it) }
                            } }
                        }
                    }
                }
            }
        }

        // 悬浮提示栈：浮在内容上方（不推挤布局），位于标题区正下方；
        // 首帧布局测量完成前不显示，避免提示盖住标题
        if (alertTopPadding > 0.dp) {
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
}

@Composable
private fun RoutesGroupCard(title: String, count: Int, content: @Composable () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "$title（$count）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface
        )
        content()
    }
}

@Composable
private fun RoutesEmpty() {
    Text(
        "无条目",
        style = MaterialTheme.typography.bodySmall,
        color = LocalAppColors.current.onSurfaceVariant
    )
}

@Composable
private fun RouteRow(entry: RouteEntry) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.dest,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                entry.iface,
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Text(
            "网关 ${entry.gateway} · 源 ${entry.source} · 跃点 ${entry.metric} · " +
                "表 ${entry.table} · 协议 ${entry.protocol}" +
                if (entry.iface.startsWith("(")) " · ${entry.device}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
}

@Composable
private fun NeighRow(entry: NeighEntry) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.ip,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                entry.iface,
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Text(
            buildString {
                append(entry.mac)
                if (entry.info.isNotBlank()) append(" · ${entry.info}")
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant
        )
    }
}

@Composable
private fun RuleRow(entry: RuleEntry) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "规则 #${entry.priority}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface
        )
        Text(
            "动作 ${entry.action} → 表 ${entry.table}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        Text(
            buildString {
                append("入 ${entry.iif} · 出 ${entry.oif} · ")
                append("源 ${entry.src}${if (entry.sport != "—") ":${entry.sport}" else ""} · ")
                append("目标 ${entry.dst}${if (entry.dport != "—") ":${entry.dport}" else ""} · ")
                append("协议 ${entry.ipproto}")
                if (entry.info.isNotBlank()) append(" · ${entry.info}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
}
