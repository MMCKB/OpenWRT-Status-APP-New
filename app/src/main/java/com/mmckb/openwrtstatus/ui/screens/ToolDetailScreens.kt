package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.ui.components.AppCard
import com.mmckb.openwrtstatus.ui.components.CardSectionTitle
import com.mmckb.openwrtstatus.ui.components.rememberTopBarPadding
import com.mmckb.openwrtstatus.ui.formatBytes
import com.mmckb.openwrtstatus.ui.formatUptime
import com.mmckb.openwrtstatus.ui.theme.AppShapes
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors

/**
 * 工具页：工具入口（文件管理 / 软件包 / 无线设置）。
 * [grid] = true 时两列磁贴排版（设置页可切换），false 为默认的列表卡片。
 */
@Composable
fun ToolScreen(
    grid: Boolean,
    onOpenFileManager: () -> Unit,
    onOpenPackageManager: () -> Unit,
    onOpenWireless: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
            .padding(top = rememberTopBarPadding(), bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (grid) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ToolTile(
                    Icons.Filled.Folder, "文件管理", "浏览 · 上传 · 传输",
                    Modifier.weight(1f).fillMaxHeight(), onOpenFileManager
                )
                ToolTile(
                    Icons.Filled.Inventory2, "软件包", "安装 · 升级",
                    Modifier.weight(1f).fillMaxHeight(), onOpenPackageManager
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ToolTile(
                    Icons.Filled.Wifi, "无线设置", "SSID · 信道",
                    Modifier.weight(1f).fillMaxHeight(), onOpenWireless
                )
                ToolPlaceholderTile(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            ToolEntryCard(
                Icons.Filled.Folder, "文件管理",
                "浏览路由器文件，支持查看、上传、下载、重命名与删除", onOpenFileManager
            )
            ToolEntryCard(
                Icons.Filled.Inventory2, "软件包",
                "已安装/可用软件包，支持安装、删除、升级与更新列表", onOpenPackageManager
            )
            ToolEntryCard(
                Icons.Filled.Wifi, "无线设置",
                "SSID、密码、信道与开关，应用后重载无线", onOpenWireless
            )
        }
    }
}

/** 列表卡片排版（默认）：与历史版本完全一致的整卡入口。 */
@Composable
private fun ToolEntryCard(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    AppCard(modifier = Modifier.clip(AppShapes.card).clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant
            )
        }
    }
}

/** 两列磁贴：大图标 + 名称 + 短语；[onClick] 为空时不可点。 */
@Composable
private fun ToolTile(
    icon: ImageVector,
    title: String,
    tagline: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val colors = LocalAppColors.current
    AppCard(
        modifier = modifier.clickable(enabled = onClick != null) { onClick?.invoke() },
        contentPadding = 16.dp
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface
        )
        Text(
            tagline,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
}

/** 磁贴网格的预留位：虚线框 + 不可点击。 */
@Composable
private fun ToolPlaceholderTile(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Column(
        modifier = modifier
            .drawBehind {
                drawRoundRect(
                    color = colors.outline,
                    cornerRadius = CornerRadius(24.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))
                    )
                )
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "预留",
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurfaceVariant
        )
        Text(
            "后续工具",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
}

/** 详情页：路由器系统详情、网络接口明细、无线明细与 DHCP 租约。 */
@Composable
fun DetailScreen(
    viewModel: com.mmckb.openwrtstatus.ui.RouterViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    when (val s = state) {
        is com.mmckb.openwrtstatus.data.model.StatusUiState.Success ->
            DetailContent(s.data, viewModel, modifier)
        is com.mmckb.openwrtstatus.data.model.StatusUiState.Error -> Box(
            modifier = modifier
                .fillMaxSize()
                .navigationBarsPadding()
            .padding(top = rememberTopBarPadding(), bottom = 96.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                s.message,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalAppColors.current.onSurfaceVariant
            )
        }
        else -> Box(
            modifier = modifier
                .fillMaxSize()
                .navigationBarsPadding()
            .padding(top = rememberTopBarPadding(), bottom = 96.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
        }
    }
}

@Composable
private fun DetailContent(
    data: com.mmckb.openwrtstatus.data.model.DashboardData,
    viewModel: com.mmckb.openwrtstatus.ui.RouterViewModel,
    modifier: Modifier = Modifier
) {
    val leases by viewModel.leases.collectAsState()
    val leaseError by viewModel.leaseError.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(
            top = rememberTopBarPadding(),
            start = 16.dp,
            end = 16.dp,
            bottom = 96.dp
        )
    ) {
        item { SystemCard(data) }
        if (data.interfaceDetails.isNotEmpty()) item { InterfaceCard(data.interfaceDetails) }
        if (data.wireless.isNotEmpty()) item { WirelessCard(data.wireless) }
        item { LeaseCard(leases, leaseError) }
    }
}

@Composable
private fun SystemCard(data: com.mmckb.openwrtstatus.data.model.DashboardData) {
    val colors = LocalAppColors.current
    AppCard {
        CardSectionTitle("系统信息")
        Spacer(Modifier.height(10.dp))
        DetailRow("主机名", data.hostname)
        DetailRow("设备型号", data.model)
        DetailRow("处理器", data.cpuInfo)
        DetailRow("内核版本", data.kernel)
        DetailRow("目标平台", data.target)
        DetailRow("固件版本", data.firmware)
        DetailRow(
            "本地时间",
            data.localtime?.let {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(it * 1000))
            }
        )
        DetailRow("运行时长", formatUptime(data.uptimeSeconds))
        if (data.loadAverage.isNotEmpty()) {
            DetailRow(
                "平均负载",
                data.loadAverage.take(3).joinToString(" / ") { String.format(java.util.Locale.US, "%.2f", it) }
            )
        }
        if (data.warnings.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                data.warnings.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InterfaceCard(interfaces: List<com.mmckb.openwrtstatus.data.model.InterfaceInfo>) {
    val colors = LocalAppColors.current
    AppCard {
        CardSectionTitle("网络接口")
        interfaces.forEachIndexed { index, iface ->
            if (index > 0) {
                HorizontalDivider(color = colors.outline, modifier = Modifier.padding(vertical = 10.dp))
            } else {
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    iface.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "(${iface.device})",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (iface.up) "已连接" else "未连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (iface.up) colors.success else colors.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            DetailRow("IPv4", iface.ipv4.joinToString("、").ifBlank { "—" })
            DetailRow("IPv6", iface.ipv6.joinToString("、").ifBlank { null })
            DetailRow("已运行", formatUptime(iface.uptimeSeconds))
            DetailRow(
                "累计流量",
                "收 ${formatBytes(iface.rxBytes)} · " +
                    "发 ${formatBytes(iface.txBytes)}"
            )
        }
    }
}

@Composable
private fun WirelessCard(wireless: List<com.mmckb.openwrtstatus.data.model.WirelessInfo>) {
    val colors = LocalAppColors.current
    AppCard {
        CardSectionTitle("无线")
        wireless.forEachIndexed { index, w ->
            if (index > 0) {
                HorizontalDivider(color = colors.outline, modifier = Modifier.padding(vertical = 10.dp))
            } else {
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    w.ssid,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (w.up) "开启" else "关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (w.up) colors.success else colors.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            DetailRow("接口", w.name)
            DetailRow("信道", w.channel)
            w.clients?.let { DetailRow("已连接设备", "$it") }
        }
    }
}

@Composable
private fun LeaseCard(
    leases: List<com.mmckb.openwrtstatus.data.model.LeaseInfo>,
    leaseError: String?
) {
    val colors = LocalAppColors.current
    AppCard {
        CardSectionTitle("DHCP 租约")
        Spacer(Modifier.height(6.dp))
        if (leases.isEmpty()) {
            Text(
                leaseError ?: "暂无租约",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        } else {
            leases.forEach { lease ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            lease.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${lease.ip} · ${lease.mac}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    Text(
                        formatExpiry(lease.expiresAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun formatExpiry(expiresAt: Long): String {
    val remaining = expiresAt - System.currentTimeMillis() / 1000
    return if (remaining <= 0) {
        "已过期"
    } else {
        val hours = remaining / 3600
        val minutes = (remaining % 3600) / 60
        if (hours > 0) "剩 ${hours}小时$minutes 分" else "剩 $minutes 分钟"
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    val colors = LocalAppColors.current
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(92.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
