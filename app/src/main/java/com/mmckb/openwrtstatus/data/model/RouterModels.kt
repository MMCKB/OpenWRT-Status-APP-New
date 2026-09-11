package com.mmckb.openwrtstatus.data.model

/**
 * Connection configuration for the target OpenWrt router.
 */
data class RouterConfig(
    val ip: String = "192.168.1.1",
    val port: Int = 80,
    val username: String = "root",
    val password: String = "",
    val useHttps: Boolean = false,
    val useMock: Boolean = false,
    val refreshIntervalSec: Int = 5
)

/**
 * A device discovered on the LAN (ARP table entry reported by the router).
 */
data class DeviceInfo(
    val ip: String,
    val mac: String,
    val iface: String?,
    val name: String?
)

/**
 * Raw per-interface byte counters reported by the router.
 */
data class InterfaceStat(
    val name: String,
    val rxBytes: Long,
    val txBytes: Long
)

/**
 * Complete snapshot of router status fetched from the device.
 */
data class RouterStatus(
    val online: Boolean,
    val hostname: String,
    val uptimeSeconds: Long,
    val loadAverage: List<Double>,
    val memoryTotalBytes: Long,
    val memoryFreeBytes: Long,
    val swapTotalBytes: Long,
    val swapFreeBytes: Long,
    val devices: List<DeviceInfo>,
    val interfaces: List<InterfaceStat>,
    val firmware: String?,
    val model: String?,
    val errorMessage: String? = null
)

/**
 * UI representation of traffic for a single interface, including computed rates.
 */
data class TrafficRate(
    val name: String,
    val rxBytes: Long,
    val txBytes: Long,
    val rxRate: Double,
    val txRate: Double
)

/**
 * Aggregated state consumed by the dashboard UI.
 */
data class DashboardData(
    val online: Boolean,
    val hostname: String,
    val uptimeSeconds: Long,
    val loadAverage: List<Double>,
    val memoryUsedPercent: Float,
    val memoryTotalBytes: Long,
    val swapUsedPercent: Float,
    val hasSwap: Boolean,
    val deviceCount: Int,
    val devices: List<DeviceInfo>,
    val interfaces: List<TrafficRate>,
    val firmware: String?,
    val model: String?,
    val lastUpdated: Long
)

/**
 * State exposed by the [com.mmckb.openwrtstatus.ui.RouterViewModel].
 */
sealed interface StatusUiState {
    data object Initial : StatusUiState
    data object Loading : StatusUiState
    data class Success(val data: DashboardData) : StatusUiState
    data class Error(val message: String) : StatusUiState
}
