package com.mmckb.openwrtstatus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mmckb.openwrtstatus.data.local.SettingsStore
import com.mmckb.openwrtstatus.data.model.DashboardData
import com.mmckb.openwrtstatus.data.model.HistorySample
import com.mmckb.openwrtstatus.data.model.LeaseInfo
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.model.StatusUiState
import com.mmckb.openwrtstatus.data.model.TrafficRate
import com.mmckb.openwrtstatus.data.remote.RouterException
import com.mmckb.openwrtstatus.data.repository.OpenWrtRepository
import com.mmckb.openwrtstatus.data.ssh.SshExec
import com.mmckb.openwrtstatus.data.ssh.SshTerminal
import com.mmckb.openwrtstatus.notify.AppNotifier
import com.mmckb.openwrtstatus.ui.components.ConnectionMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.delay
import kotlin.math.max

private const val HISTORY_LIMIT = 60

/**
 * Holds the device list, the active router config, the latest dashboard snapshot,
 * DHCP leases, a rolling monitoring history and the SSH terminal session.
 */
class RouterViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val repository = OpenWrtRepository()

    /** Interactive SSH shell used by the terminal screen. */
    val terminal = SshTerminal()

    private val initialDevices = settingsStore.loadDevices()

    private val _devices = MutableStateFlow(initialDevices)
    val devices: StateFlow<List<RouterConfig>> = _devices

    private val _activeId = MutableStateFlow(settingsStore.loadActiveId(initialDevices))
    val activeId: StateFlow<String> = _activeId

    private val _config = MutableStateFlow(configFor(initialDevices, _activeId.value))
    val config: StateFlow<RouterConfig> = _config

    private fun configFor(devices: List<RouterConfig>, id: String): RouterConfig =
        devices.firstOrNull { it.id == id }
            ?: devices.firstOrNull()
            ?: RouterConfig(id = SettingsStore.ID_LEGACY)

    private val _uiState = MutableStateFlow<StatusUiState>(StatusUiState.Initial)
    val uiState: StateFlow<StatusUiState> = _uiState

    private val _leases = MutableStateFlow<List<LeaseInfo>>(emptyList())
    val leases: StateFlow<List<LeaseInfo>> = _leases

    private val _leaseError = MutableStateFlow<String?>(null)
    val leaseError: StateFlow<String?> = _leaseError

    private val _history = MutableStateFlow<List<HistorySample>>(emptyList())
    val history: StateFlow<List<HistorySample>> = _history

    private val _connNotifyEnabled =
        MutableStateFlow(settingsStore.isConnectionNotifyEnabled())
    val connNotifyEnabled: StateFlow<Boolean> = _connNotifyEnabled

    // Used to compute per-interface throughput from two consecutive samples.
    private val previousTraffic = mutableMapOf<String, Pair<Long, Long>>()
    private var previousTime = 0L
    private val refreshMutex = Mutex()

    init {
        refresh()
        // 轮询在 ViewModel 层常驻（不随页面切换启停），断连监控因此始终有效。
        viewModelScope.launch {
            while (true) {
                delay(_config.value.refreshIntervalSec.coerceAtLeast(2) * 1000L)
                refresh()
            }
        }
        // 连接状态通知：在线↔离线切换时发送（已连接通知带计秒器动画，实时走时）。
        viewModelScope.launch {
            var previous = ConnectionMonitor.Status.Unknown
            ConnectionMonitor.status.collect { status ->
                val app = getApplication<Application>()
                val enabled = _connNotifyEnabled.value
                if (previous == ConnectionMonitor.Status.Online &&
                    status == ConnectionMonitor.Status.Offline && enabled
                ) {
                    AppNotifier.notifyDisconnected(app)
                }
                if (previous == ConnectionMonitor.Status.Offline &&
                    status == ConnectionMonitor.Status.Online && enabled
                ) {
                    AppNotifier.notifyConnected(app, System.currentTimeMillis())
                }
                previous = status
            }
        }
    }

    /** Fetches a dashboard snapshot; [forceConfig] overrides the active config once. */
    fun refresh(forceConfig: RouterConfig? = null) {
        viewModelScope.launch {
            val cfg = forceConfig ?: _config.value
        if (_uiState.value !is StatusUiState.Success) {
            _uiState.value = StatusUiState.Loading
        }
        if (!refreshMutex.tryLock()) return@launch
        try {
                val status = repository.fetchStatus(cfg)
                val now = System.currentTimeMillis()

                val rates = status.interfaces.map { iface ->
                    val prev = previousTraffic[iface.name]
                    val dt = if (previousTime > 0) (now - previousTime) / 1000.0 else 0.0
                    val rxRate = if (prev != null && dt > 0) max(0.0, (iface.rxBytes - prev.first) / dt) else 0.0
                    val txRate = if (prev != null && dt > 0) max(0.0, (iface.txBytes - prev.second) / dt) else 0.0
                    TrafficRate(iface.name, iface.rxBytes, iface.txBytes, rxRate, txRate)
                }
                previousTraffic.clear()
                status.interfaces.forEach { previousTraffic[it.name] = it.rxBytes to it.txBytes }
                previousTime = now

                val memTotal = status.memoryTotalBytes
                val memAvailable = status.memoryAvailableBytes
                val memPct = if (memTotal > 0) ((memTotal - memAvailable).coerceAtLeast(0) * 100f / memTotal) else 0f
                val swapTotal = status.swapTotalBytes
                val swapAvailable = status.swapAvailableBytes
                val swapPct = if (swapTotal > 0) ((swapTotal - swapAvailable).coerceAtLeast(0) * 100f / swapTotal) else 0f

                if (status.leases.isNotEmpty()) _leases.value = status.leases

                val totalRx = rates.sumOf { it.rxRate }
                val totalTx = rates.sumOf { it.txRate }

                _history.value = (_history.value + HistorySample(
                    at = now,
                    memoryPercent = memPct,
                    load1 = (status.loadAverage.firstOrNull() ?: 0.0).toFloat(),
                    rxRate = totalRx,
                    txRate = totalTx
                )).takeLast(HISTORY_LIMIT)

                _uiState.value = StatusUiState.Success(
                    DashboardData(
                        online = status.online,
                        hostname = status.hostname,
                        uptimeSeconds = status.uptimeSeconds,
                        loadAverage = status.loadAverage,
                        memoryUsedPercent = memPct,
                        memoryTotalBytes = memTotal,
                        memoryAvailableBytes = memAvailable,
                        swapUsedPercent = swapPct,
                        hasSwap = swapTotal > 0,
                        leases = _leases.value,
                        interfaces = rates,
                        interfaceDetails = status.interfaces,
                        wireless = status.wireless,
                        firmware = status.firmware,
                        model = status.model,
                        boardName = status.boardName,
                        cpuInfo = status.cpuInfo,
                        kernel = status.kernel,
                        rootfsType = status.rootfsType,
                        distribution = status.distribution,
                        releaseVersion = status.releaseVersion,
                        releaseRevision = status.releaseRevision,
                        target = status.target,
                        localtime = status.localtime,
                        rootFsTotalBytes = status.rootFsTotalBytes,
                        rootFsFreeBytes = status.rootFsFreeBytes,
                        tmpTotalBytes = status.tmpTotalBytes,
                        tmpFreeBytes = status.tmpFreeBytes,
                        warnings = status.warnings,
                        lastUpdated = now
                    )
                )

                if (cfg.sshEnabled) refreshLeases()
                ConnectionMonitor.status.value = ConnectionMonitor.Status.Online
            } catch (e: RouterException) {
                _uiState.value = StatusUiState.Error(e.message ?: "连接失败", e.hint)
                ConnectionMonitor.status.value = ConnectionMonitor.Status.Offline
            } catch (e: Exception) {
                _uiState.value = StatusUiState.Error(e.message ?: "未知错误", null)
                ConnectionMonitor.status.value = ConnectionMonitor.Status.Offline
            } finally {
                refreshMutex.unlock()
            }
        }
    }

    /** Reads DHCP leases over SSH (`cat /tmp/dhcp.leases`). */
    fun refreshLeases() {
        viewModelScope.launch {
            val cfg = _config.value
            if (!cfg.sshEnabled) {
                _leaseError.value = null
                return@launch
            }
            val ssh = SshConfig(
                host = cfg.sshHost.ifBlank { cfg.ip },
                port = cfg.sshPort,
                username = cfg.sshUsername,
                password = cfg.sshPassword
            )
            runCatching { SshExec.run(ssh, "cat /tmp/dhcp.leases") }
                .onSuccess { raw ->
                    _leases.value = repository.parseLeases(raw)
                    _leaseError.value = if (_leases.value.isEmpty()) "未读取到 DHCP 租约。" else null
                }
                .onFailure { _leaseError.value = "读取租约失败：${it.message}" }
        }
    }

    fun connectSsh() {
        viewModelScope.launch {
            val cfg = _config.value
            terminal.connect(
                SshConfig(
                    host = cfg.sshHost.ifBlank { cfg.ip },
                    port = cfg.sshPort,
                    username = cfg.sshUsername,
                    password = cfg.sshPassword
                )
            )
        }
    }

    fun sendCommand(command: String) {
        terminal.send(command)
    }

    fun disconnectSsh() {
        terminal.disconnect()
    }

    /** 开关实时网速 Live Update 通知；关闭时立即撤回常驻通知。 */
    fun setConnectionNotifyEnabled(enabled: Boolean) {
        settingsStore.saveConnectionNotifyEnabled(enabled)
        _connNotifyEnabled.value = enabled
        if (!enabled) {
            AppNotifier.cancel(getApplication(), AppNotifier.ID_CONN_STATUS)
        }
    }

    /** Adds a device and makes it the active one. */
    fun addDevice(device: RouterConfig) {
        val new = device.copy(id = java.util.UUID.randomUUID().toString())
        _devices.value = _devices.value + new
        _activeId.value = new.id
        syncActiveConfig()
        persist()
        resetSessionState()
        refresh(new)
    }

    /** Updates an existing device; refreshes immediately when it is the active one. */
    fun updateDevice(device: RouterConfig) {
        _devices.value = _devices.value.map { if (it.id == device.id) device else it }
        syncActiveConfig()
        persist()
        if (device.id == _activeId.value) {
            resetSessionState()
            refresh(device)
        }
    }

    /** Removes a device; falls back to the first remaining device when needed. */
    fun deleteDevice(id: String) {
        val remaining = _devices.value.filterNot { it.id == id }
        _devices.value = remaining
        if (_activeId.value == id) {
            _activeId.value = remaining.firstOrNull()?.id.orEmpty()
            syncActiveConfig()
            persist()
            resetSessionState()
            remaining.firstOrNull()?.let { refresh(it) }
        } else {
            persist()
        }
    }

    /** Switches the active device; history and session state are reset. */
    fun selectDevice(id: String) {
        if (id == _activeId.value) return
        _activeId.value = id
        syncActiveConfig()
        persist()
        resetSessionState()
        refresh(_config.value)
    }

    private fun syncActiveConfig() {
        _config.value = configFor(_devices.value, _activeId.value)
    }

    private fun persist() {
        settingsStore.saveDevices(_devices.value, _activeId.value)
    }

    private fun resetSessionState() {
        previousTraffic.clear()
        previousTime = 0
        _history.value = emptyList()
        _leases.value = emptyList()
        _leaseError.value = null
        terminal.disconnect()
    }

    override fun onCleared() {
        terminal.close()
        super.onCleared()
    }
}
