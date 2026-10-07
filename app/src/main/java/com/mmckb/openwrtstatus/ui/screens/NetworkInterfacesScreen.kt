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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.components.AppSwitch
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
    val scope = rememberCoroutineScope()
    val client = remember { NetworkInterfacesClient() }
    val alertStack = rememberAlertStackState()
    var editSection by remember { mutableStateOf<String?>(null) } // null=关闭，""=新增
    var deleteTarget by mutableStateOf<com.mmckb.openwrtstatus.data.remote.IfaceUci?>(null)
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

    fun doSave(section: String, isNew: Boolean, values: Map<String, String>, dns: List<String>, dhcp: Map<String, String>, onDone: () -> Unit) {
        val s = ssh ?: run {
            setAlert(AppAlertType.Error, "需要 SSH 访问", "接口编辑需要开启 SSH 后重试。")
            onDone()
            return
        }
        opJob = scope.launch {
            busy = true
            try {
                withTimeout(NIF_TIMEOUT_MS) {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        cfgClient.saveIface(config, s, section, isNew, values, dns, dhcp)
                    }
                }
                setAlert(AppAlertType.Success, "接口配置已保存并重载")
                onDone()
                load()
            } catch (e: Exception) {
                if (e !is CancellationException) setAlert(AppAlertType.Error, "接口配置保存失败", nifErrText(e))
            } finally {
                busy = false
            }
        }
    }

    fun doDelete(target: com.mmckb.openwrtstatus.data.remote.IfaceUci) {
        val s = ssh ?: run {
            setAlert(AppAlertType.Error, "需要 SSH 访问", "接口删除需要开启 SSH 后重试。")
            return
        }
        opJob = scope.launch {
            busy = true
            try {
                withTimeout(NIF_TIMEOUT_MS) {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        cfgClient.deleteIface(config, s, target.section, target.name)
                    }
                }
                setAlert(AppAlertType.Success, "接口已删除")
                load()
            } catch (e: Exception) {
                if (e !is CancellationException) setAlert(AppAlertType.Error, "接口删除失败", nifErrText(e))
            } finally {
                busy = false
            }
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
                    item {
                        Button(
                            onClick = { editSection = "" },
                            enabled = !busy && sshEnabled,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (sshEnabled) "＋ 添加接口" else "添加接口需要开启 SSH") }
                    }
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
                            val u = ucis.firstOrNull { it.name == iface.name || it.device == iface.name }
                            IfaceCard(
                                uci = u,
                                live = iface,
                                sshEnabled = sshEnabled,
                                onEdit = if (u != null) ({ editSection = u.section }) else null,
                                onDelete = if (u != null) ({ deleteTarget = u }) else null
                            )
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

        editSection?.let { section ->
            val isNew = section.isBlank()
            val uci = ucis.firstOrNull { it.section == section }
            IfaceEditDialog(
                initial = uci,
                isNew = isNew,
                sshEnabled = sshEnabled,
                busy = busy,
                onDismiss = { editSection = null },
                onSave = { values, dns, dhcp ->
                    doSave(section.ifBlank { "if_" + System.currentTimeMillis() / 1000 }, isNew, values, dns, dhcp) {
                        editSection = null
                    }
                }
            )
        }

        deleteTarget?.let { target ->
            AppDialog(
                title = "删除接口",
                message = "确定删除「" + target.name + "」吗？该接口的配置将一并移除。",
                confirmLabel = "删除",
                confirmColor = colors.error,
                onConfirm = {
                    deleteTarget = null
                    doDelete(target)
                },
                onDismiss = { deleteTarget = null }
            )
        }
    }
}

@Composable
private fun IfaceCard(
    uci: com.mmckb.openwrtstatus.data.remote.IfaceUci?,
    iface: com.mmckb.openwrtstatus.data.remote.IfaceDetail,
    sshEnabled: Boolean,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
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
        if (sshEnabled && uci != null) {
            HorizontalDivider(color = colors.outline)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(
                    "编辑",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onEdit?.invoke() }
                        .padding(vertical = 4.dp)
                )
                Text(
                    "删除",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDelete?.invoke() }
                        .padding(vertical = 4.dp)
                )
            }
        }
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

/** 接口编辑弹窗：协议/设备/地址/DNS + DHCP 服务器（static 时显示）。 */
@Composable
private fun IfaceEditDialog(
    initial: com.mmckb.openwrtstatus.data.remote.IfaceUci?,
    isNew: Boolean,
    sshEnabled: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>, List<String>, Map<String, String>) -> Unit
) {
    val colors = LocalAppColors.current
    var proto by remember { mutableStateOf(initial?.proto ?: "static") }
    var device by remember { mutableStateOf(initial?.device ?: "") }
    var ipaddr by remember { mutableStateOf(initial?.ipaddr ?: "") }
    var netmask by remember { mutableStateOf(initial?.netmask ?: "255.255.255.0") }
    var gateway by remember { mutableStateOf(initial?.gateway ?: "") }
    var dnsText by remember { mutableStateOf(initial?.dns?.joinToString("\n") ?: "") }
    var pppoeUser by remember { mutableStateOf(initial?.pppoeUser ?: "") }
    var pppoePass by remember { mutableStateOf(initial?.pppoePass ?: "") }
    var dhcpEnabled by remember { mutableStateOf(initial?.dhcpEnabled ?: true) }
    var dhcpStart by remember { mutableStateOf(initial?.dhcpStart ?: "100") }
    var dhcpLimit by remember { mutableStateOf(initial?.dhcpLimit ?: "150") }
    var dhcpLeasetime by remember { mutableStateOf(initial?.dhcpLeasetime ?: "12h") }
    AppDialog(
        title = if (isNew) "添加接口" else "编辑接口 " + (initial?.name ?: ""),
        confirmLabel = "保存",
        dismissLabel = "取消",
        confirmEnabled = !busy,
        onConfirm = {
            val values = mutableMapOf("proto" to proto, "device" to device)
            if (proto == "static") {
                values["ipaddr"] = ipaddr
                values["netmask"] = netmask
                if (gateway.isNotBlank()) values["gateway"] = gateway
            }
            if (proto == "pppoe") {
                values["username"] = pppoeUser
                values["password"] = pppoePass
            }
            val dnsList = dnsText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (dnsList.isNotEmpty()) values["dns"] = dnsList.joinToString(" ")
            val dhcp = if (proto == "static" && dhcpEnabled) mutableMapOf(
                "start" to dhcpStart, "limit" to dhcpLimit, "leasetime" to dhcpLeasetime
            ) else mutableMapOf<String, String>()
            onSave(values, dnsList, dhcp)
        },
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("协议", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("static" to "静态", "dhcp" to "DHCP", "none" to "不配置", "pppoe" to "PPPoE").forEach { (v, label) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (proto == v) colors.onPrimary else colors.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (proto == v) colors.primary else colors.surfaceVariant)
                            .clickable { proto = v }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            AppTextField(
                value = device,
                onValueChange = { device = it },
                label = { Text("设备（如 eth0、br-lan）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (proto == "static") {
                Spacer(Modifier.height(4.dp))
                AppTextField(
                    value = ipaddr,
                    onValueChange = { ipaddr = it },
                    label = { Text("IPv4 地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                AppTextField(
                    value = netmask,
                    onValueChange = { netmask = it },
                    label = { Text("子网掩码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                AppTextField(
                    value = gateway,
                    onValueChange = { gateway = it },
                    label = { Text("网关（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(4.dp))
            AppTextField(
                value = dnsText,
                onValueChange = { dnsText = it },
                label = { Text("DNS 服务器（每行一个）") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            if (proto == "pppoe") {
                Spacer(Modifier.height(4.dp))
                AppTextField(
                    value = pppoeUser,
                    onValueChange = { pppoeUser = it },
                    label = { Text("PPPoE 用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                AppTextField(
                    value = pppoePass,
                    onValueChange = { pppoePass = it },
                    label = { Text("PPPoE 密码") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (proto == "static") {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DHCP 服务器", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                    AppSwitch(checked = dhcpEnabled, onCheckedChange = { dhcpEnabled = it })
                }
                if (dhcpEnabled) {
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = dhcpStart,
                        onValueChange = { dhcpStart = it },
                        label = { Text("起始地址（偏移，如 100）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = dhcpLimit,
                        onValueChange = { dhcpLimit = it },
                        label = { Text("可分配数量（如 150）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    AppTextField(
                        value = dhcpLeasetime,
                        onValueChange = { dhcpLeasetime = it },
                        label = { Text("租期（如 12h）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}