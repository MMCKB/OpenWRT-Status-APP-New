package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmckb.openwrtstatus.data.model.DashboardData
import com.mmckb.openwrtstatus.data.model.HistorySample
import com.mmckb.openwrtstatus.data.model.InterfaceInfo
import com.mmckb.openwrtstatus.data.model.LeaseInfo
import com.mmckb.openwrtstatus.data.model.StatusUiState
import com.mmckb.openwrtstatus.data.model.WirelessInfo
import com.mmckb.openwrtstatus.ui.RouterViewModel
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.CardSectionTitle
import com.mmckb.openwrtstatus.ui.components.MetricTile
import com.mmckb.openwrtstatus.ui.components.Sparkline
import com.mmckb.openwrtstatus.ui.formatBytes
import com.mmckb.openwrtstatus.ui.formatRate
import com.mmckb.openwrtstatus.ui.formatUptime
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

/**
 * Monitoring hub: rolling traffic/resource charts plus the device details moved over
 * from the old devices tab (interfaces, wireless radios and DHCP leases). Everything
 * reflects the currently active router.
 */
@Composable
fun MonitorScreen(
    viewModel: RouterViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val leases by viewModel.leases.collectAsStateWithLifecycle()
    val leaseError by viewModel.leaseError.collectAsStateWithLifecycle()
    val data = (uiState as? StatusUiState.Success)?.data

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        item { TrafficCard(history) }
        item { ResourceCard(history, data) }
        item { InterfacesCard(data?.interfaceDetails.orEmpty()) }
        item { WirelessCard(data?.wireless.orEmpty()) }
        item {
            LeasesCard(
                leases = leases,
                error = leaseError,
                sshEnabled = config.sshEnabled,
                onRefresh = { viewModel.refreshLeases() }
            )
        }
    }
}

@Composable
private fun TrafficCard(history: List<HistorySample>) {
    val colors = LocalAppColors.current
    val last = history.lastOrNull()
    AppCard {
        CardSectionTitle("实时流量")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("当前下行", formatRate(last?.rxRate ?: 0.0), Modifier.weight(1f), colors.primary)
            MetricTile("当前上行", formatRate(last?.txRate ?: 0.0), Modifier.weight(1f), colors.accent)
        }
        Spacer(Modifier.height(16.dp))
        ChartBlock("下行速率", history.map { it.rxRate.toFloat() }, colors.primary)
        Spacer(Modifier.height(12.dp))
        ChartBlock("上行速率", history.map { it.txRate.toFloat() }, colors.accent)
    }
}

@Composable
private fun ResourceCard(history: List<HistorySample>, data: DashboardData?) {
    val colors = LocalAppColors.current
    AppCard {
        CardSectionTitle("资源占用")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                "内存",
                "%.0f%%".format(data?.memoryUsedPercent ?: 0f),
                Modifier.weight(1f)
            )
            MetricTile(
                "负载(1m)",
                "%.2f".format(data?.loadAverage?.firstOrNull() ?: 0.0),
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(16.dp))
        ChartBlock("内存占用 %", history.map { it.memoryPercent }, colors.accent, maxValue = 100f)
        Spacer(Modifier.height(12.dp))
        ChartBlock("负载 1 分钟", history.map { it.load1 }, colors.success)
    }
}

@Composable
private fun ChartBlock(
    title: String,
    values: List<Float>,
    color: Color,
    maxValue: Float? = null
) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Sparkline(values = values, color = color, maxValue = maxValue)
    }
}

@Composable
private fun InterfacesCard(interfaces: List<InterfaceInfo>) {
    val colors = LocalAppColors.current
    AppCard {
        CardSectionTitle("网络接口")
        Spacer(Modifier.height(10.dp))
        if (interfaces.isEmpty()) {
            Text("暂无接口数据", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            return@AppCard
        }
        interfaces.forEach { iface ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(if (iface.up) colors.success else colors.onSurfaceVariant, CircleShape)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        iface.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Text(
                        buildString {
                            append(iface.device)
                            if (iface.ipv4.isNotEmpty()) append(" · ${iface.ipv4.joinToString(", ")}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    Text(
                        "运行 ${formatUptime(iface.uptimeSeconds)} · ↓ ${formatBytes(iface.rxBytes)} · ↑ ${formatBytes(iface.txBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = colors.outline)
        }
    }
}

@Composable
private fun WirelessCard(wireless: List<WirelessInfo>) {
    val colors = LocalAppColors.current
    AppCard {
        CardSectionTitle("无线")
        Spacer(Modifier.height(10.dp))
        if (wireless.isEmpty()) {
            Text("未获取到无线信息", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            return@AppCard
        }
        wireless.forEach { w ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(if (w.up) colors.success else colors.onSurfaceVariant, CircleShape)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        w.ssid,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Text(
                        "${w.name} · 信道 ${w.channel}${w.clients?.let { " · ${it} 客户端" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(color = colors.outline)
        }
    }
}

@Composable
private fun LeasesCard(
    leases: List<LeaseInfo>,
    error: String?,
    sshEnabled: Boolean,
    onRefresh: () -> Unit
) {
    val colors = LocalAppColors.current
    AppCard {
        CardSectionTitle("DHCP 租约")
        Spacer(Modifier.height(10.dp))
        when {
            !sshEnabled -> {
                Spacer(Modifier.height(4.dp))
                Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("刷新租约") }
            }
            leases.isEmpty() -> {
                Text(
                    error ?: "未读取到租约。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("刷新租约") }
            }
            else -> {
                leases.forEach { lease ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                lease.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = colors.onSurface
                            )
                            Text(
                                "${lease.ip} · ${lease.mac}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Text(
                            expiryText(lease.expiresAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(color = colors.outline)
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("刷新租约") }
            }
        }
    }
}

private fun expiryText(expiresAt: Long): String {
    val remain = expiresAt - System.currentTimeMillis() / 1000
    return if (remain > 0) "剩余 ${remain / 60} 分" else "已过期"
}
