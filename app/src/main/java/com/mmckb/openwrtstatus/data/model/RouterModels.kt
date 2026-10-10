package com.mmckb.openwrtstatus.data.model

/**
 * Connection configuration for one OpenWrt router.
 *
 * The router is reached over the rpcd ubus endpoint (`http(s)://host:port/ubus`).
 * Multiple [RouterConfig] entries are kept in the device list; `id` is the stable key
 * used for selection and `name` is the user-facing label (falls back to the address).
 *
 * 刻意**不实现 `Serializable`/`Parcelable`**：这个对象带着明文凭据，一旦可序列化就
 * 容易被塞进 Intent/Bundle，凭据会经 Binder 落到 system_server。二级页改为只收设备 id
 * 并按 id 从本地读取（见 `ui/DeviceHandoff.kt`）。
 */
data class RouterConfig(
    val id: String = "",
    val name: String = "",
    val ip: String = "192.168.1.1",
    val port: Int = 80,
    val username: String = "root",
    val password: String = "",
    val useHttps: Boolean = false,
    /** Trust self-signed certificates (common on router HTTPS). */
    val allowInsecureTls: Boolean = false,
    val refreshIntervalSec: Int = 5,
    // --- SSH (remote shell / DHCP leases) ---
    val sshEnabled: Boolean = false,
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUsername: String = "root",
    val sshPassword: String = ""
) {
    /** Label shown in device lists. */
    val displayName: String get() = name.ifBlank { ip }
}

/**
 * SSH connection settings derived from [RouterConfig].
 */
data class SshConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String
)

/**
 * A logical network interface reported by `network.interface dump`.
 */
data class InterfaceInfo(
    val name: String,
    val device: String,
    val up: Boolean,
    val ipv4: List<String>,
    val ipv6: List<String> = emptyList(),
    val uptimeSeconds: Long,
    val rxBytes: Long,
    val txBytes: Long
)

/**
 * A wireless radio/SSID reported by `network.wireless status`.
 */
data class WirelessInfo(
    val name: String,
    val ssid: String,
    val up: Boolean,
    val channel: String,
    val clients: Int?
)

/**
 * A DHCP lease (from `/tmp/dhcp.leases`, read over SSH).
 */
data class LeaseInfo(
    val mac: String,
    val ip: String,
    val name: String,
    val expiresAt: Long
)

/**
 * Aggregated traffic for one interface, including computed rates.
 */
data class TrafficRate(
    val name: String,
    val rxBytes: Long,
    val txBytes: Long,
    val rxRate: Double,
    val txRate: Double
)

/**
 * A single point in the rolling monitoring history.
 */
data class HistorySample(
    val at: Long,
    val memoryPercent: Float,
    val load1: Float,
    val rxRate: Double,
    val txRate: Double
)

/**
 * Complete snapshot of router status.
 */
data class RouterStatus(
    val online: Boolean,
    val hostname: String,
    val uptimeSeconds: Long,
    val loadAverage: List<Double>,
    val memoryTotalBytes: Long,
    val memoryAvailableBytes: Long,
    val swapTotalBytes: Long,
    val swapAvailableBytes: Long,
    val interfaces: List<InterfaceInfo>,
    val wireless: List<WirelessInfo>,
    val leases: List<LeaseInfo>,
    val firmware: String?,
    val model: String?,
    val boardName: String? = null,
    val cpuInfo: String? = null,
    val kernel: String? = null,
    val rootfsType: String? = null,
    val distribution: String? = null,
    val releaseVersion: String? = null,
    val releaseRevision: String? = null,
    val target: String? = null,
    val localtime: Long? = null,
    val rootFsTotalBytes: Long = 0L,
    val rootFsFreeBytes: Long = 0L,
    val tmpTotalBytes: Long = 0L,
    val tmpFreeBytes: Long = 0L,
    val warnings: List<String> = emptyList()
)

/** 端口状态（来自 /sys/class/net，lo 除外）。 */
data class PortStatus(
    val name: String,
    val up: Boolean,
    val speedMbps: Int? = null
)

/** 存储挂载点（df -k 行）。 */
data class StorageMount(
    val fs: String,
    val mount: String,
    val totalKB: Long,
    val usedKB: Long,
    val availKB: Long
)

/**
 * 隐藏区（关于页图标连点 7 次解锁）的扩展信息：端口状态 + 存储挂载点；
 * 内存信息直接复用 [DashboardData] 的字段。
 */
data class HiddenDiagData(
    val ports: List<PortStatus> = emptyList(),
    val mounts: List<StorageMount> = emptyList()
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
    val memoryAvailableBytes: Long,
    val swapUsedPercent: Float,
    val hasSwap: Boolean,
    val leases: List<LeaseInfo>,
    val interfaces: List<TrafficRate>,
    val interfaceDetails: List<InterfaceInfo>,
    val wireless: List<WirelessInfo>,
    val firmware: String?,
    val model: String?,
    val boardName: String? = null,
    val cpuInfo: String? = null,
    val kernel: String? = null,
    val rootfsType: String? = null,
    val distribution: String? = null,
    val releaseVersion: String? = null,
    val releaseRevision: String? = null,
    val target: String? = null,
    val localtime: Long? = null,
    val rootFsTotalBytes: Long = 0L,
    val rootFsFreeBytes: Long = 0L,
    val tmpTotalBytes: Long = 0L,
    val tmpFreeBytes: Long = 0L,
    val warnings: List<String>,
    val lastUpdated: Long
)

/**
 * State exposed by [com.mmckb.openwrtstatus.ui.RouterViewModel].
 */
sealed interface StatusUiState {
    data object Initial : StatusUiState
    data object Loading : StatusUiState
    data class Success(val data: DashboardData) : StatusUiState
    data class Error(val message: String, val hint: String? = null) : StatusUiState
}
