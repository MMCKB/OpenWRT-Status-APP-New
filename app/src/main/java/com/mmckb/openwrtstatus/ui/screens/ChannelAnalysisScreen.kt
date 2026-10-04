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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.remote.ChannelAnalysisClient
import com.mmckb.openwrtstatus.data.remote.RouterException
import com.mmckb.openwrtstatus.ui.components.AppAlertType
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppIconButton
import com.mmckb.openwrtstatus.ui.components.ConnectionMonitor
import com.mmckb.openwrtstatus.ui.components.SmoothOptionSwitcher
import com.mmckb.openwrtstatus.ui.components.StackedAlertHost
import com.mmckb.openwrtstatus.ui.components.rememberAlertStackState
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 扫描（含触发）较慢：无数据不活动超时给足 90 秒。 */
private const val CA_SCAN_TIMEOUT_MS = 90_000L

private fun caErrText(e: Exception): String = when {
    e is TimeoutCancellationException -> "路由器连接中断或扫描超时，请重试。"
    e is CancellationException -> "连接已断开，操作已终止。"
    e is RouterException -> e.message ?: "请稍后重试。"
    else -> e.message ?: "请稍后重试。"
}

/** BSSID → 稳定的高饱和颜色（同 LuCI 由 bssid 派生）。 */
private fun stationColor(bssid: String): Color {
    var h = 1125899906842597L
    for (c in bssid) h = 31 * h + c.code
    val hue = (((h % 360) + 360) % 360).toFloat()
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.72f, 0.92f)))
}

/** 信号 dBm → 0..1 相对强度（-100 → 0.05，-30 → 1）。 */
private fun signalStrength(signal: Int): Float =
    (((signal + 100f) / 70f).coerceIn(0.05f, 1f))

/**
 * 信道分析页（LuCI admin/status/channel_analysis 复刻）：
 * 每个 wlan 接口×频段一个页签；上方信道占用图（每网络一条钟形，x=频率、
 * 高度=信号强度、BSSID 派生颜色、本机接口描边置顶），下方网络表格
 * （信号 / SSID / 信道 / 宽度 / 模式 / BSSID），按信道→SSID 排序。需要 SSH。
 */
@Composable
fun ChannelAnalysisScreen(
    config: RouterConfig,
    sshEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val client = remember { ChannelAnalysisClient() }
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
    var busy by remember { mutableStateOf(false) }
    val alertStack = rememberAlertStackState()
    val density = LocalDensity.current
    var alertTopPadding by remember { mutableStateOf(0.dp) }

    var data by remember { mutableStateOf<ChannelAnalysisClient.ChannelAnalysisData?>(null) }
    var tab by remember { mutableStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }

    var opJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) {
        ConnectionMonitor.status.collect {
            if (it == ConnectionMonitor.Status.Offline) opJob?.cancel()
        }
    }

    fun setAlert(type: AppAlertType, title: String, description: String? = null) {
        alertStack.push(type, title, description)
    }

    fun ensureConnected(): Boolean {
        if (ConnectionMonitor.status.value == ConnectionMonitor.Status.Offline) {
            setAlert(AppAlertType.Error, "与路由器已断开连接", "请检查手机与路由器的网络后重试。")
            return false
        }
        return true
    }

    fun load() {
        if (opJob?.isActive == true) return
        if (!ensureConnected()) {
            loading = false
            return
        }
        opJob = scope.launch {
            busy = true
            try {
                val result = withTimeout(CA_SCAN_TIMEOUT_MS) {
                    withContext(kotlinx.coroutines.Dispatchers.IO) { client.load(sshOrNull) }
                }
                data = result
                errorText = null
                if (result.bands.isEmpty()) {
                    errorText = "未发现无线接口（wlan*），请确认无线已启用。"
                }
                if (tab >= result.bands.size) tab = 0
            } catch (e: Exception) {
                errorText = caErrText(e)
                setAlert(AppAlertType.Error, "信道分析失败", caErrText(e))
            } finally {
                busy = false
                loading = false
            }
        }
    }

    var loadedOnce by remember { mutableStateOf(false) }
    if (!loadedOnce) {
        loadedOnce = true
        load()
    }

    val bands = data?.bands.orEmpty()
    val tabOptions = bands.mapIndexed { i, b ->
        val title = if (b.band == 2) "${b.ifname} · 2.4G" else "${b.ifname} · ${b.band}G"
        i.toString() to title
    }
    val current = bands.getOrNull(tab)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 0.dp)
        ) {
            // 标题区（提示栈锚定返回键行底部，与其他页面一致）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.onGloballyPositioned { coords ->
                    // 返回键行底边 + 24dp 间隙 = 提示栈顶部锚点
                    alertTopPadding = with(density) {
                        (coords.positionInParent().y + coords.size.height).toDp() + 24.dp
                    }
                }
            ) {
                AppBackButton(onBack = onBack)
                Spacer(Modifier.width(10.dp))
                Text(
                    "信道分析",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                AppIconButton(onClick = { load() }, enabled = !busy) {
                    Text(
                        "⟳",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface
                    )
                }
            }
            Text(
                "邻近无线网络的信道占用与信号分布",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            if (bands.isNotEmpty()) {
                SmoothOptionSwitcher(
                    options = tabOptions,
                    selected = tab.toString(),
                    onSelect = { tab = it.toInt() },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
            }

            Box(modifier = Modifier.weight(1f)) {
            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
            } else if (current == null) {
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
                        errorText ?: "暂无数据，点击右上角刷新重试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ChannelGraphCard(current)

                    StationTableCard(current)
                }
                Spacer(Modifier.navigationBarsPadding().height(6.dp))
            }
            }
        }

        // 悬浮提示栈：浮在内容上方（不推挤布局），位于返回键行正下方；
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

/** 信道占用图：x=频率（MHz 线性），钟形高度=信号强度，标签=SSID。 */
@Composable
private fun ChannelGraphCard(band: ChannelAnalysisClient.ChannelBandData) {
    val colors = LocalAppColors.current
    val labelPaint = remember(colors) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 9f * 2.4f
            color = colors.onSurfaceVariant.copy(alpha = 0.9f).toArgb()
        }
    }
    val localPaint = remember(colors) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 9f * 2.4f
            color = colors.onSurface.toArgb()
            isFakeBoldText = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
        ) {
            val w = size.width
            val h = size.height
            val channels = band.channels
            if (channels.isEmpty()) return@Canvas
            val freqs = channels.mapNotNull { ch -> band.channelMhz[ch]?.toFloat()?.takeIf { m -> m > 0f } }
            val minF = (freqs.minOrNull() ?: 2412f) - 10f
            val maxF = (freqs.maxOrNull() ?: 2482f) + 10f
            val span = (maxF - minF).coerceAtLeast(1f)
            fun xOf(mhz: Float): Float = (mhz - minF) / span * w
            val baseline = h - 18.dp.toPx()

            // 信道刻度线与数字
            for (ch in channels) {
                val mhz = band.channelMhz[ch]?.toFloat() ?: continue
                val x = xOf(mhz)
                drawLine(
                    colors.outline.copy(alpha = 0.5f),
                    Offset(x, 0f), Offset(x, baseline),
                    strokeWidth = 1f
                )
                drawContext.canvas.nativeCanvas.drawText(
                    ch.toString(), x + 3f, h - 5f, labelPaint
                )
            }

            fun drawBell(centerCh: Int, halfWidthMhz: Float, signal: Int, color: Color, emphasized: Boolean) {
                val centerMhz = band.channelMhz[centerCh]?.toFloat()
                    ?: ChannelAnalysisClient.defaultMhz(centerCh, band.band).toFloat()
                if (centerMhz <= 0f) return
                val xc = xOf(centerMhz)
                val half = (halfWidthMhz / 2f) / span * w * 2f
                val peakY = baseline - signalStrength(signal) * (baseline - 14.dp.toPx())
                val path = Path().apply {
                    moveTo(xc - half, baseline)
                    cubicTo(xc - half * 0.55f, baseline, xc - half * 0.3f, peakY, xc, peakY)
                    cubicTo(xc + half * 0.3f, peakY, xc + half * 0.55f, baseline, xc + half, baseline)
                    close()
                }
                drawPath(path, color.copy(alpha = 0.30f))
                drawPath(
                    path, if (emphasized) Color(0xFF1A1C1E) else color,
                    style = Stroke(width = if (emphasized) 3f else 2.5f)
                )
            }

            // 邻近网络
            for (st in band.stations) {
                val color = stationColor(st.bssid)
                val halfSpanMhz = when {
                    st.widthMhz >= 160 -> 74f
                    st.widthMhz >= 80 -> 36f
                    st.widthMhz >= 40 -> 18f
                    else -> 9f
                }
                val centers = st.centerChannels.ifEmpty { listOf(st.channel) }
                centers.forEach { drawBell(it, halfSpanMhz * 2f, st.signal, color, false) }
                val mainCenter = centers.firstOrNull() ?: st.channel
                val mainMhz = band.channelMhz[mainCenter]?.toFloat()
                    ?: ChannelAnalysisClient.defaultMhz(mainCenter, band.band).toFloat()
                if (mainMhz > 0f) {
                    drawContext.canvas.nativeCanvas.drawText(
                        (st.ssid ?: "隐藏网络").take(14),
                        (xOf(mainMhz) - 30f).coerceAtLeast(2f),
                        baseline - signalStrength(st.signal) * (baseline - 14.dp.toPx()) - 8f,
                        labelPaint
                    )
                }
            }

            // 本机接口（描边加重，置顶绘制）
            band.local?.let { local ->
                val halfSpanMhz = when {
                    local.htmode?.startsWith("16") == true -> 74f
                    local.centerChan2 != null -> 36f
                    (local.centerChan1 ?: local.channel) != local.channel -> 18f
                    else -> 9f
                }
                val signal = maxOf(local.signal, -50)
                drawBell(local.centerChan1 ?: local.channel, halfSpanMhz * 2f, signal, Color(0xFF0088FF), true)
                val mainMhz = band.channelMhz[local.channel]?.toFloat() ?: 0f
                if (mainMhz > 0f) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "本机 · ${local.ssid}".take(16),
                        (xOf(mainMhz) - 30f).coerceAtLeast(2f),
                        14f,
                        localPaint
                    )
                }
            }
        }
    }
}

/** 邻近网络表格：信号徽章 / SSID / 信道 / 宽度 / 模式 / BSSID。 */
@Composable
private fun StationTableCard(band: ChannelAnalysisClient.ChannelBandData) {
    val colors = LocalAppColors.current

    fun qualityPercent(st: ChannelAnalysisClient.ChannelStation): Int {
        val q = st.quality
        val m = st.qualityMax
        return if (q > 0 && m > 0) (100f / m * q).toInt().coerceIn(0, 100) else 0
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // 表头
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("信号", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.width(44.dp))
            Spacer(Modifier.width(16.dp))
            Text("SSID", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("信道", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.width(36.dp))
            Text("带宽", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.width(60.dp))
            Text("模式", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.width(52.dp))
        }
        HorizontalDivider(color = colors.outline, modifier = Modifier.padding(vertical = 6.dp))

        if (band.stations.isEmpty()) {
            Text(
                "未扫描到邻近网络，点击右上角刷新重扫。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        }
        band.stations.forEach { st ->
            val pct = qualityPercent(st)
            val sigColor = when {
                st.signal >= -55 -> colors.success
                st.signal >= -70 -> Color(0xFFE08A00)
                else -> colors.error
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$pct%",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = sigColor,
                    modifier = Modifier.width(44.dp)
                )
                Box(modifier = Modifier.width(16.dp)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(stationColor(st.bssid))
                    )
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    st.ssid ?: "（隐藏）",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp)
                )
                Text(
                    st.channel.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurface,
                    modifier = Modifier.width(36.dp)
                )
                Text(
                    st.widthText,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.width(64.dp)
                )
                Text(
                    st.mode ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.width(56.dp)
                )
            }
            Spacer(Modifier.height(7.dp))
        }
    }
}
