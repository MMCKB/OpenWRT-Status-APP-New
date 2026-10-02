package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.remote.RealtimeClient
import com.mmckb.openwrtstatus.ui.components.AppAlertType
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppIconButton
import com.mmckb.openwrtstatus.ui.components.ConnectionMonitor
import com.mmckb.openwrtstatus.ui.components.SmoothOptionSwitcher
import com.mmckb.openwrtstatus.ui.components.StackedAlertHost
import com.mmckb.openwrtstatus.ui.components.rememberAlertStackState
import com.mmckb.openwrtstatus.ui.formatBytes
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 轮询间隔与单次拉取超时（与 LuCI realtime 的 2 秒节奏一致）。 */
private const val RT_POLL_INTERVAL_MS = 2_000L
private const val RT_FETCH_TIMEOUT_MS = 12_000L
/** 图表保留的采样点数（负载/流量/连接用路由器返回的约 60 个历史点）。 */
private const val RT_MAX_POINTS = 150

private fun rtErrText(e: Exception): String = when {
    e is TimeoutCancellationException -> "路由器连接中断或长时间无响应，请检查网络后重试。"
    e is CancellationException -> "连接已断开，操作已终止。"
    else -> e.message ?: "请稍后重试。"
}

/** 一条折线序列。 */
private data class RtSeries(
    val label: String,
    val color: Color,
    val values: List<Float>,
    val unit: RtUnit = RtUnit.Plain,
    val decimals: Int = 0
)

private enum class RtUnit { Plain, Percent, Rate, Dbm }

/** 页签。 */
private val RT_TABS = listOf("load" to "负载", "traffic" to "流量", "conntrack" to "连接", "wireless" to "无线")

/**
 * 实时监控页（LuCI admin/status/realtime 复刻）：负载（1/5/15 分钟平均）、
 * 流量（选定接口收/发）、连接（UDP/TCP/其它）与无线（信号/噪声）四页签，
 * 每 2 秒轮询 ubus `luci getRealtimeStats`，Canvas 折线图展示近 60 秒采样。
 * 走 ubus，无需 SSH。
 */
@Composable
fun RealtimeScreen(
    config: RouterConfig,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val client = remember { RealtimeClient() }
    val alertStack = rememberAlertStackState()
    val density = LocalDensity.current
    var alertTopPadding by remember { mutableStateOf(0.dp) }

    var tab by remember { mutableStateOf("load") }
    var loading by remember { mutableStateOf(true) }
    var offline by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun setAlert(type: AppAlertType, title: String, description: String? = null) {
        alertStack.push(type, title, description)
    }

    // 断连监控：离线时停止轮询（恢复后自动重启）
    LaunchedEffect(Unit) {
        ConnectionMonitor.status.collect {
            offline = it == ConnectionMonitor.Status.Offline
        }
    }

    // 流量页接口选择 / 无线页设备选择
    var interfaces by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedIface by remember { mutableStateOf("") }
    var radios by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedRadio by remember { mutableStateOf("") }

    // 各页签当前数据（每次轮询整体替换）
    var loadSeries by remember { mutableStateOf<List<RtSeries>>(emptyList()) }
    var trafficSeries by remember { mutableStateOf<List<RtSeries>>(emptyList()) }
    var connSeries by remember { mutableStateOf<List<RtSeries>>(emptyList()) }
    var wirelessSeries by remember { mutableStateOf<List<RtSeries>>(emptyList()) }
    // 无线信号本机累积历史（iwinfo info 只有当前值）
    val wirelessHistory = remember { mutableStateListOf<Pair<Float, Float>>() }

    // 轮询循环：页签或选择变化时重启
    LaunchedEffect(tab, selectedIface, selectedRadio, offline) {
        if (offline) return@LaunchedEffect
        var hadError = false
        while (isActive) {
            try {
                when (tab) {
                    "load" -> {
                        val rows = withTimeout(RT_FETCH_TIMEOUT_MS) {
                            client.realtimeStats(config, "load")
                        }
                        // 行形如 [t, l1, l2, l3]；旧固件把负载放大 100 倍，检测后归一
                        val cols = listOf(1, 2, 3).map { c -> rows.mapNotNull { it.getOrNull(c) } }
                        val raw = cols.filter { it.isNotEmpty() }.flatten().maxOrNull() ?: 0.0
                        val scale = if (raw > 100.0) 0.01 else 1.0
                        val names = listOf("1 分钟", "5 分钟", "15 分钟")
                        loadSeries = cols.mapIndexed { i, v ->
                            RtSeries(
                                label = names.getOrElse(i) { "序列${i + 1}" },
                                color = seriesColor(i),
                                values = v.map { (it * scale).toFloat() },
                                decimals = 2
                            )
                        }
                    }
                    "traffic" -> {
                        if (selectedIface.isBlank()) {
                            errorText = null
                        } else {
                            val rows = withTimeout(RT_FETCH_TIMEOUT_MS) {
                                client.realtimeStats(
                                    config, "traffic",
                                    buildJsonObject { put("interface", selectedIface) }
                                )
                            }
                            val rx = rows.mapNotNull { it.getOrNull(1) }
                            val tx = rows.mapNotNull { it.getOrNull(2) }
                            trafficSeries = listOf(
                                RtSeries("接收", seriesColor(0), rx.map { it.toFloat() }, RtUnit.Rate),
                                RtSeries("发送", seriesColor(1), tx.map { it.toFloat() }, RtUnit.Rate)
                            )
                        }
                    }
                    "conntrack" -> {
                        val rows = withTimeout(RT_FETCH_TIMEOUT_MS) {
                            client.realtimeStats(config, "conntrack")
                        }
                        val names = listOf("UDP", "TCP", "其它")
                        connSeries = listOf(1, 2, 3).mapIndexed { i, c ->
                            RtSeries(
                                label = names[i],
                                color = seriesColor(i),
                                values = rows.mapNotNull { it.getOrNull(c)?.toFloat() }
                            )
                        }
                    }
                    "wireless" -> {
                        if (selectedRadio.isBlank()) {
                            errorText = null
                        } else {
                            val pair = withTimeout(RT_FETCH_TIMEOUT_MS) {
                                client.wirelessInfo(config, selectedRadio)
                            }
                            if (pair != null) {
                                wirelessHistory.add(pair.first.toFloat() to pair.second.toFloat())
                                while (wirelessHistory.size > RT_MAX_POINTS) wirelessHistory.removeAt(0)
                            }
                            wirelessSeries = listOf(
                                RtSeries("信号", seriesColor(0), wirelessHistory.map { it.first }, RtUnit.Dbm),
                                RtSeries("噪声", seriesColor(1), wirelessHistory.map { it.second }, RtUnit.Dbm)
                            )
                        }
                    }
                }
                errorText = null
                hadError = false
                loading = false
            } catch (e: Exception) {
                if (e is CancellationException && !isActive) throw e
                loading = false
                if (!hadError) {
                    hadError = true
                    errorText = rtErrText(e)
                    setAlert(AppAlertType.Error, "获取实时数据失败", rtErrText(e))
                }
            }
            delay(RT_POLL_INTERVAL_MS)
        }
    }

    // 首次进入：拉取接口/无线设备列表
    LaunchedEffect(Unit) {
        try {
            val ifs = withTimeout(RT_FETCH_TIMEOUT_MS) { client.interfaces(config) }
            interfaces = ifs
            if (selectedIface.isBlank()) selectedIface = ifs.firstOrNull() ?: "eth0"
            val rs = runCatching { withTimeout(RT_FETCH_TIMEOUT_MS) { client.radios(config) } }.getOrDefault(emptyList())
            radios = rs
            if (selectedRadio.isBlank()) selectedRadio = rs.firstOrNull() ?: "wlan0"
        } catch (e: Exception) {
            setAlert(AppAlertType.Error, "读取失败", rtErrText(e))
            loading = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 0.dp)
        ) {
            // 标题区（提示栈锚定其底部）
            Column(
                modifier = Modifier.onGloballyPositioned { coords ->
                    alertTopPadding = with(density) {
                        (coords.positionInParent().y + coords.size.height).toDp() + 8.dp
                    }
                }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppBackButton(onBack = onBack)
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    "实时监控",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Text(
                    "负载 · 流量 · 连接 · 无线信号",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            SmoothOptionSwitcher(
                options = RT_TABS,
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
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 设备/接口选择行
                    when (tab) {
                        "traffic" -> SelectorCard(
                            label = "接口",
                            options = interfaces.ifEmpty { listOf(selectedIface) },
                            selected = selectedIface,
                            onSelect = { selectedIface = it }
                        )
                        "wireless" -> SelectorCard(
                            label = "无线",
                            options = radios.ifEmpty { listOf(selectedRadio) },
                            selected = selectedRadio,
                            onSelect = { selectedRadio = it }
                        )
                    }

                    val series = when (tab) {
                        "load" -> loadSeries
                        "traffic" -> trafficSeries
                        "conntrack" -> connSeries
                        else -> wirelessSeries
                    }
                    if (series.any { it.values.isNotEmpty() }) {
                        RealtimeChartCard(series)
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
                                .padding(horizontal = 14.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                errorText ?: "正在采集数据…",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        // 悬浮提示栈：锚定标题区底部；测量完成前不渲染
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
private fun seriesColor(index: Int): Color = when (index % 4) {
    0 -> Color(0xFF0088FF)
    1 -> Color(0xFF2E9E5B)
    2 -> Color(0xFFE08A00)
    else -> Color(0xFFB04FC4)
}

/** 接口/无线设备选择卡片。 */
@Composable
private fun SelectorCard(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        options.forEach { opt ->
            val isSel = opt == selected
            Text(
                opt,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSel) colors.onPrimary else colors.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isSel) colors.primary else colors.surface)
                    .border(1.dp, if (isSel) colors.primary else colors.outline, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

/** 图表卡片：Canvas 折线 + 图例（当前值）。 */
@Composable
private fun RealtimeChartCard(series: List<RtSeries>) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
        ) {
            val w = size.width
            val h = size.height
            val padLeft = 8f
            val padTop = 8f
            val padBottom = 8f
            val all = series.flatMap { it.values }
            if (all.isEmpty()) return@Canvas
            var maxV = (all.maxOrNull() ?: 1f)
            var minV = (all.minOrNull() ?: 0f)
            if (series.any { it.unit == RtUnit.Dbm }) {
                maxV = maxOf(maxV, -40f)
                minV = minOf(minV, -100f)
            }
            if (maxV - minV < 1e-6f) maxV = minV + 1f
            val range = maxV - minV

            // 横向网格 4 条
            val gridColor = colors.outline.copy(alpha = 0.6f)
            for (i in 0..4) {
                val y = padTop + (h - padTop - padBottom) * i / 4f
                drawLine(gridColor, Offset(padLeft, y), Offset(w - padLeft, y), strokeWidth = 1f)
            }

            fun yOf(v: Float): Float =
                padTop + (h - padTop - padBottom) * (1f - (v - minV) / range)

            val count = series.maxOf { it.values.size }
            if (count >= 2) {
                for (s in series) {
                    val n = s.values.size
                    if (n < 2) continue
                    val stepX = (w - padLeft * 2) / (count - 1)
                    val path = androidx.compose.ui.graphics.Path()
                    for (i in 0 until n) {
                        val x = padLeft + stepX * i
                        val y = yOf(s.values[i].coerceIn(minV, maxV))
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, s.color, style = Stroke(width = 4f, cap = StrokeCap.Round))
                }
            }
        }

        // 图例 + 当前值
        series.forEach { s ->
            val last = s.values.lastOrNull()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(s.color)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    s.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.width(56.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        last == null -> "—"
                        s.unit == RtUnit.Rate -> formatBytes(last.toLong()) + "/s"
                        s.unit == RtUnit.Dbm -> "%.0f dBm".format(last)
                        s.unit == RtUnit.Percent -> "%.0f%%".format(last)
                        s.decimals > 0 -> "%.${s.decimals}f".format(last)
                        else -> "%.0f".format(last)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurface
                )
            }
        }
    }
}
