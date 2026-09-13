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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmckb.openwrtstatus.data.model.DashboardData
import com.mmckb.openwrtstatus.data.model.StatusUiState
import com.mmckb.openwrtstatus.ui.RouterViewModel
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.CardSectionTitle
import com.mmckb.openwrtstatus.ui.components.MetricTile
import com.mmckb.openwrtstatus.ui.components.rememberTopBarPadding
import com.mmckb.openwrtstatus.ui.formatBytes
import com.mmckb.openwrtstatus.ui.formatRate
import com.mmckb.openwrtstatus.ui.formatUptime
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    viewModel: RouterViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val leases by viewModel.leases.collectAsStateWithLifecycle()

    LaunchedEffect(config.refreshIntervalSec) {
        while (true) {
            delay((config.refreshIntervalSec * 1000L).coerceAtLeast(2000L))
            viewModel.refresh()
        }
    }

    when (val state = uiState) {
        is StatusUiState.Initial, is StatusUiState.Loading -> LoadingView()
        is StatusUiState.Error -> ErrorView(
            message = state.message,
            hint = state.hint,
            onRetry = { viewModel.refresh() }
        )
        is StatusUiState.Success -> DashboardContent(
            data = state.data.copy(leases = state.data.leases.ifEmpty { leases }),
            modifier = modifier
        )
    }
}

@Composable
private fun LoadingView() {
    val colors = LocalAppColors.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = colors.accent)
            Spacer(Modifier.height(12.dp))
            Text(
                "正在连接路由器…",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

/**
 * Surfaces the failure reason plus an actionable hint, so a connection problem is
 * diagnosable from the UI instead of just "failed".
 */
@Composable
private fun ErrorView(message: String, hint: String?, onRetry: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AppCard {
            CardSectionTitle("连接失败")
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.error
            )
            if (!hint.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重试") }
        }
    }
}

@Composable
private fun DashboardContent(data: DashboardData, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = rememberTopBarPadding(), bottom = 96.dp)
    ) {
        item { StatusCard(data) }
        item { ResourceCard(data) }
        item { TrafficCard(data) }
        item { ClientsCard(data) }
        if (data.warnings.isNotEmpty()) {
            item { WarningsCard(data.warnings) }
        }
    }
}

@Composable
private fun StatusCard(data: DashboardData) {
    val colors = LocalAppColors.current
    val statusColor = if (data.online) colors.success else colors.error
    val time = remember(data.lastUpdated) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(data.lastUpdated))
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(statusColor, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    data.hostname,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Text(
                    if (data.online) "在线 · 更新于 $time" else "离线 · $time",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("运行时间", formatUptime(data.uptimeSeconds), Modifier.weight(1f))
            MetricTile("在线租约", "${data.leases.size}", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        InfoRow("型号", data.model ?: "—")
        InfoRow("固件", data.firmware ?: "—")
    }
}

@Composable
private fun ResourceCard(data: DashboardData) {
    val colors = LocalAppColors.current
    AppCard {
        CardSectionTitle("系统资源")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("1 分钟", "%.2f".format(Locale.US, data.loadAverage.getOrNull(0) ?: 0.0), Modifier.weight(1f))
            MetricTile("5 分钟", "%.2f".format(Locale.US, data.loadAverage.getOrNull(1) ?: 0.0), Modifier.weight(1f))
            MetricTile("15 分钟", "%.2f".format(Locale.US, data.loadAverage.getOrNull(2) ?: 0.0), Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "内存  %.0f%%  ·  可用 %s / %s".format(
                Locale.US,
                data.memoryUsedPercent,
                formatBytes(data.memoryAvailableBytes),
                formatBytes(data.memoryTotalBytes)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        LinearProgressIndicator(
            progress = { (data.memoryUsedPercent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        )
        if (data.hasSwap) {
            Text(
                "交换分区  %.0f%%".format(Locale.US, data.swapUsedPercent),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = { (data.swapUsedPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun TrafficCard(data: DashboardData) {
    val colors = LocalAppColors.current
    AppCard {
        CardSectionTitle("网络流量")
        Spacer(Modifier.height(10.dp))
        if (data.interfaces.isEmpty()) {
            Text("暂无接口数据", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        } else {
            data.interfaces.forEach { t ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        t.name,
                        modifier = Modifier.width(84.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurface
                    )
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "↓ ${formatRate(t.rxRate)}  累计 ${formatBytes(t.rxBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.primary
                        )
                        Text(
                            "↑ ${formatRate(t.txRate)}  累计 ${formatBytes(t.txBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.accent
                        )
                    }
                }
                HorizontalDivider(color = colors.outline)
            }
        }
    }
}

@Composable
private fun ClientsCard(data: DashboardData) {
    val colors = LocalAppColors.current
    AppCard {
        CardSectionTitle("接入设备")
        Spacer(Modifier.height(10.dp))
        if (data.wireless.isEmpty()) {
            Text("未获取到无线信息", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        } else {
            data.wireless.forEach { w ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(10.dp).background(if (w.up) colors.success else colors.onSurfaceVariant, CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            w.ssid,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurface
                        )
                        Text(
                            "${w.name} · 信道 ${w.channel}${w.clients?.let { " · ${it} 个客户端" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(color = colors.outline)
            }
        }
        if (data.leases.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "DHCP 租约 ${data.leases.size} 台，最新：${data.leases.firstOrNull()?.name ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WarningsCard(warnings: List<String>) {
    val colors = LocalAppColors.current
    AppCard {
        CardSectionTitle("提示")
        Spacer(Modifier.height(8.dp))
        warnings.forEach {
            Text("- $it", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = LocalAppColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
    }
}
