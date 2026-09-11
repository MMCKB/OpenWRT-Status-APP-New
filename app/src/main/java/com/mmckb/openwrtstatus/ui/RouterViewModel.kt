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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

private const val HISTORY_LIMIT = 60

/**
 * Holds router connection config, the latest dashboard snapshot, DHCP leases,
 * a rolling monitoring history and the SSH terminal session.
 */
class RouterViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val repository = OpenWrtRepository()

    /** Interactive SSH shell used by the terminal screen. */
    val terminal = SshTerminal()

    private val _config = MutableStateFlow(settingsStore.load())
    val config: StateFlow<RouterConfig> = _config

    private val _uiState = MutableStateFlow<StatusUiState>(StatusUiState.Initial)
    val uiState: StateFlow<StatusUiState> = _uiState

    private val _leases = MutableStateFlow<List<LeaseInfo>>(emptyList())
    val leases: StateFlow<List<LeaseInfo>> = _leases

    private val _history = MutableStateFlow<List<HistorySample>>(emptyList())
    val history: StateFlow<List<HistorySample>> = _history

    private val _leaseError = MutableStateFlow<String?>(null)
    val leaseError: StateFlow<String?> = _leaseError

    // Used to compute per-interface throughput from two consecutive samples.
    private val previousTraffic = mutableMapOf<String, Pair<Long, Long>>()
    private var previousTime = 0L

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            if (_uiState.value !is StatusUiState.Success) {
                _uiState.value = StatusUiState.Loading
            }
            try {
                val status = repository.fetchStatus(_config.value)
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
                        warnings = status.warnings,
                        lastUpdated = now
                    )
                )

                if (_config.value.sshEnabled) refreshLeases()
            } catch (e: RouterException) {
                _uiState.value = StatusUiState.Error(e.message ?: "连接失败", e.hint)
            } catch (e: Exception) {
                _uiState.value = StatusUiState.Error(e.message ?: "未知错误", null)
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

    fun saveConfig(newConfig: RouterConfig) {
        _config.value = newConfig
        settingsStore.save(newConfig)
        previousTraffic.clear()
        previousTime = 0
        _history.value = emptyList()
        refresh()
    }

    override fun onCleared() {
        terminal.close()
        super.onCleared()
    }
}
