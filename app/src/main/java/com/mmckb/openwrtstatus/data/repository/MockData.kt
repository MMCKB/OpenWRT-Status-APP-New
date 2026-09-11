package com.mmckb.openwrtstatus.data.repository

import com.mmckb.openwrtstatus.data.model.InterfaceInfo
import com.mmckb.openwrtstatus.data.model.LeaseInfo
import com.mmckb.openwrtstatus.data.model.RouterStatus
import com.mmckb.openwrtstatus.data.model.WirelessInfo

/**
 * Deterministic sample data used by the "演示模式" (demo mode) so every screen is fully
 * usable without a physical router.
 */
object MockData {
    fun sample(): RouterStatus = RouterStatus(
        online = true,
        hostname = "OpenWrt",
        uptimeSeconds = 1_234_567,
        loadAverage = listOf(0.15, 0.10, 0.05),
        memoryTotalBytes = 256L * 1024 * 1024,
        memoryAvailableBytes = 136L * 1024 * 1024,
        swapTotalBytes = 512L * 1024 * 1024,
        swapAvailableBytes = 480L * 1024 * 1024,
        interfaces = listOf(
            InterfaceInfo("lan", "br-lan", true, listOf("192.168.2.1"), 1_200_000, 12_345_678_901L, 3_456_789_012L),
            InterfaceInfo("wan", "eth0", true, listOf("10.0.0.5"), 1_100_000, 9_876_543_210L, 2_345_678_901L),
            InterfaceInfo("wlan", "wlan0", true, emptyList(), 900_000, 1_234_567_890L, 987_654_321L)
        ),
        wireless = listOf(
            WirelessInfo("radio0", "MMCKB-5G", true, "36", 3),
            WirelessInfo("radio1", "MMCKB-2.4G", true, "6", 2)
        ),
        leases = listOf(
            LeaseInfo("AA:BB:CC:11:22:33", "192.168.2.10", "Phone", System.currentTimeMillis() + 3_600_000),
            LeaseInfo("AA:BB:CC:44:55:66", "192.168.2.11", "Laptop", System.currentTimeMillis() + 3_600_000),
            LeaseInfo("AA:BB:CC:77:88:99", "192.168.2.20", "Smart TV", System.currentTimeMillis() + 1_800_000)
        ),
        firmware = "OpenWrt 23.05.3",
        model = "Xiaomi Redmi Router AC2100"
    )
}
