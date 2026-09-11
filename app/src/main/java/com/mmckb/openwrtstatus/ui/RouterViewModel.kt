package com.mmckb.openwrtstatus.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mmckb.openwrtstatus.data.local.SettingsStore
import com.mmckb.openwrtstatus.data.model.DashboardData
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.StatusUiState
import com.mmckb.openwrtstatus.data.model.TrafficRate
import com.mmckb.openwrtstatus.data.repository.OpenWrtRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Holds router connection config and the latest dashboard snapshot, and drives periodic refresh.
 */
class RouterViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val repository = OpenWrtRepository()

    private val _config = MutableStateFlow(settingsStore.load())
    val config: StateFlow<RouterConfig> = _config

    private val _uiState = MutableStateFlow<StatusUiState>(StatusUiState.Initial)
    val uiState: StateFlow<StatusUiState> = _uiState

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
                val memUsed = max(0L, memTotal - status.memoryFreeBytes)
                val memPct = if (memTotal > 0) (memUsed * 100f / memTotal) else 0f
                val swapTotal = status.swapTotalBytes
                val swapUsed = max(0L, swapTotal - status.swapFreeBytes)
                val swapPct = if (swapTotal > 0) (swapUsed * 100f / swapTotal) else 0f

                _uiState.value = StatusUiState.Success(
                    DashboardData(
                        online = status.online,
                        hostname = status.hostname,
                        uptimeSeconds = status.uptimeSeconds,
                        loadAverage = status.loadAverage,
                        memoryUsedPercent = memPct,
                        memoryTotalBytes = memTotal,
                        swapUsedPercent = swapPct,
                        hasSwap = swapTotal > 0,
                        deviceCount = status.devices.size,
                        devices = status.devices,
                        interfaces = rates,
                        firmware = status.firmware,
                        model = status.model,
                        lastUpdated = now
                    )
                )
            } catch (e: Exception) {
                _uiState.value = StatusUiState.Error(e.message ?: "未知错误")
            }
        }
    }

    fun saveConfig(newConfig: RouterConfig) {
        _config.value = newConfig
        settingsStore.save(newConfig)
        previousTraffic.clear()
        previousTime = 0
        refresh()
    }
}
