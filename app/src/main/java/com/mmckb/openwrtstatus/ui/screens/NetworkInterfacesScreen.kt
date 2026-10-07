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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.remote.IfaceDetail
import com.mmckb.openwrtstatus.data.remote.NetworkInterfacesClient
import com.mmckb.openwrtstatus.data.remote.RouterException
import com.mmckb.openwrtstatus.ui.components.AppAlertType
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppIconButton
import com.mmckb.openwrtstatus.ui.components.ConnectionMonitor
import com.mmckb.openwrtstatus.ui.components.StackedAlertHost
import com.mmckb.openwrtstatus.ui.components.rememberAlertStackState
import com.mmckb.openwrtstatus.ui.formatUptime
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val NIF_TIMEOUT_MS = 20_000L

private fun nifErrText(e: Exception): String = when {
    e is TimeoutCancellationException -> "路由器连接中断或长时间无响应，请检查网络后重试。"
    e is CancellationException -> "连接已断开，操作已终止。"
    e is RouterException -> e.message ?: "请稍后重试。"
    else -> e.message ?: "请稍后重试。"
}

/** 网络-接口页（LuCI admin/network/interfaces 状态概览）：每个逻辑接口一张卡片。 */
@Composable
fun NetworkInterfacesScreen(
    config: RouterConfig,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = remember { androidx.compose.runtime.rememberCoroutineScope() }
    val client = remember { NetworkInterfacesClient() }
    val alertStack = rememberAlertStackState()
    val density = LocalDensity.current
    var alertTopPadding by remember { mutableStateOf(0.dp) }

    var loading by remember { mutableStateOf(true) }
    var ifaces by remember { mutableStateOf<List<IfaceDetail>?>(null) }
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
            loading = true
            try {
                ifaces = withTimeout(NIF_TIMEOUT_MS) {
                    withContext(kotlinx.coroutines.Dispatchers.IO) { client.load(config) }
                }
                errorText = null
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    errorText = nifErrText(e)
                    setAlert(AppAlertType.Error, "接口状态读取失败", nifErrText(e))
                }
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

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
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "接口",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    AppIconButton(onClick = { load() }, enabled = !loading) {
                        Text(
                            "⟳",
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.onSurface
                        )
                    }
                }
                Text(
                    "逻辑接口的协议、地址与网关状态",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val list = ifaces.orEmpty()
                    if (list.isEmpty()) {
                        item {
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
                                    errorText ?: "暂无接口数据，点击右上角刷新重试。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                    list.forEach { iface ->
                        item {
                            IfaceCard(iface)
                        }
                    }
                    item { Spacer(Modifier.navigationBarsPadding().height(6.dp)) }
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
private fun IfaceCard(iface: IfaceDetail) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (iface.up) colors.success else colors.onSurfaceVariant, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(
                iface.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                iface.proto ?: "—",
                style = MaterialTheme.typography.labelMedium,
                color = if (iface.up) colors.success else colors.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
        DetailRow("设备", iface.device)
        DetailRow(
            "IPv4",
            iface.ipv4.joinToString("、").ifBlank { "—" }
        )
        if (iface.ipv6.isNotEmpty()) DetailRow("IPv6", iface.ipv6.joinToString("、"))
        DetailRow(
            "网关",
            iface.gateways.joinToString("、").ifBlank { "—" }
        )
        if (iface.dns.isNotEmpty()) DetailRow("DNS", iface.dns.joinToString("、"))
        DetailRow("在线时长", formatUptime(iface.uptimeSeconds))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val colors = LocalAppColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
