package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mmckb.openwrtstatus.data.model.DashboardData
import com.mmckb.openwrtstatus.data.model.StatusUiState
import com.mmckb.openwrtstatus.ui.RouterViewModel
import com.mmckb.openwrtstatus.ui.components.AppCard
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

    // Auto-refresh on an interval configured by the user.
    LaunchedEffect(config.refreshIntervalSec) {
        while (true) {
            delay((config.refreshIntervalSec * 1000L).coerceAtLeast(2000L))
            viewModel.refresh()
        }
    }

    when (val state = uiState) {
        is StatusUiState.Initial, is StatusUiState.Loading -> LoadingView()
        is StatusUiState.Error -> ErrorView(message = state.message, onRetry = { viewModel.refresh() })
        is StatusUiState.Success -> {
            val data = state.data
            LazyColumn(
                modifier = modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                item { StatusHeader(online = data.online, lastUpdated = data.lastUpdated) }
                item { SystemOverviewCard(data) }
                item { SystemLoadCard(data) }
                item { TrafficCard(data) }
                item { DevicesCard(data) }
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "获取状态失败",
            style = MaterialTheme.typography.titleMedium,
            color = colors.error
        )
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun StatusHeader(online: Boolean, lastUpdated: Long) {
    val colors = LocalAppColors.current
    val color = if (online) colors.success else colors.error
    val text = if (online) "在线" else "离线"
    val time = remember(lastUpdated) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastUpdated))
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text, fontWeight = FontWeight.Bold, color = color)
                Text("更新于 $time", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SystemOverviewCard(data: DashboardData) {
    val colors = LocalAppColors.current
    AppCard {
        Text("系统概览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurface)
        Spacer(Modifier.height(8.dp))
        InfoRow("主机名", data.hostname)
        data.model?.let { InfoRow("型号", it) }
        data.firmware?.let { InfoRow("固件", it) }
        InfoRow("运行时间", formatUptime(data.uptimeSeconds))
    }
}

@Composable
private fun SystemLoadCard(data: DashboardData) {
    val colors = LocalAppColors.current
    AppCard {
        Text("系统负载", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurface)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf("1分钟", "5分钟", "15分钟").forEachIndexed { i, label ->
                val v = data.loadAverage.getOrNull(i) ?: 0.0
                Column {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(
                        "%.2f".format(Locale.US, v),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "内存使用 %.0f%%  (%s / %s)".format(
                Locale.US, data.memoryUsedPercent,
                formatBytes((data.memoryTotalBytes * data.memoryUsedPercent / 100).toLong()),
                formatBytes(data.memoryTotalBytes)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        LinearProgressIndicator(
            progress = { (data.memoryUsedPercent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
        if (data.hasSwap) {
            Text("交换分区 %.0f%%".format(Locale.US, data.swapUsedPercent), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            LinearProgressIndicator(
                progress = { (data.swapUsedPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun TrafficCard(data: DashboardData) {
    val colors = LocalAppColors.current
    AppCard {
        Text("网络流量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurface)
        Spacer(Modifier.height(8.dp))
        if (data.interfaces.isEmpty()) {
            Text("无接口数据", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        } else {
            data.interfaces.forEach { t ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        t.name,
                        modifier = Modifier.width(80.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurface
                    )
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "↓ ${formatRate(t.rxRate)}    累计 ${formatBytes(t.rxBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.primary
                        )
                        Text(
                            "↑ ${formatRate(t.txRate)}    累计 ${formatBytes(t.txBytes)}",
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
private fun DevicesCard(data: DashboardData) {
    val colors = LocalAppColors.current
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已连接设备", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.onSurface)
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(24.dp).background(colors.accent.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("${data.deviceCount}", style = MaterialTheme.typography.labelSmall, color = colors.accent)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (data.devices.isEmpty()) {
            Text("未发现设备", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        } else {
            data.devices.forEach { d ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(d.name ?: d.ip, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = colors.onSurface)
                    Text(
                        "${d.ip}  ·  ${d.mac}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    d.iface?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
                }
                HorizontalDivider(color = colors.outline)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = LocalAppColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
    }
}
