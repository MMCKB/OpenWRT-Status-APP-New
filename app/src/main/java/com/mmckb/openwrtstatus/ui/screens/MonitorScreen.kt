package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmckb.openwrtstatus.data.model.DashboardData
import com.mmckb.openwrtstatus.data.model.HistorySample
import com.mmckb.openwrtstatus.data.model.StatusUiState
import com.mmckb.openwrtstatus.ui.RouterViewModel
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.CardSectionTitle
import com.mmckb.openwrtstatus.ui.components.MetricTile
import com.mmckb.openwrtstatus.ui.components.Sparkline
import com.mmckb.openwrtstatus.ui.formatRate
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

/**
 * Rolling traffic and resource monitoring. Samples are appended on every dashboard refresh
 * and kept to the last [HISTORY_LIMIT] points.
 */
@Composable
fun MonitorScreen(
    viewModel: RouterViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val data = (uiState as? StatusUiState.Success)?.data

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        item { TrafficCard(history) }
        item { ResourceCard(history, data) }
        item { SampleNoteCard(config.refreshIntervalSec, history.size) }
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
private fun SampleNoteCard(intervalSec: Int, sampleCount: Int) {
    val colors = LocalAppColors.current
    AppCard {
        CardSectionTitle("采样说明")
        Spacer(Modifier.height(8.dp))
        Text(
            "每 ${intervalSec.coerceAtLeast(2)} 秒采样一次，保留最近 60 个点（当前 $sampleCount 个）。" +
                "速率由相邻两次计数差值计算，重启应用后历史会重新累积。",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "提示：缩短刷新间隔可让曲线更细腻。",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = colors.onSurfaceVariant
        )
    }
}
