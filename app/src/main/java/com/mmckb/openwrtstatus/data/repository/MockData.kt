package com.mmckb.openwrtstatus.data.repository

import com.mmckb.openwrtstatus.data.model.DeviceInfo
import com.mmckb.openwrtstatus.data.model.InterfaceStat
import com.mmckb.openwrtstatus.data.model.RouterStatus

/**
 * Deterministic sample data used by the "演示模式" (demo mode) so the UI is fully
 * usable without a physical router.
 */
object MockData {
    fun sample(): RouterStatus = RouterStatus(
        online = true,
        hostname = "OpenWrt",
        uptimeSeconds = 1_234_567,
        loadAverage = listOf(0.15, 0.10, 0.05),
        memoryTotalBytes = 256L * 1024 * 1024,
        memoryFreeBytes = 120L * 1024 * 1024,
        swapTotalBytes = 512L * 1024 * 1024,
        swapFreeBytes = 480L * 1024 * 1024,
        devices = listOf(
            DeviceInfo("192.168.1.10", "AA:BB:CC:11:22:33", "br-lan", "Phone"),
            DeviceInfo("192.168.1.11", "AA:BB:CC:44:55:66", "br-lan", "Laptop"),
            DeviceInfo("192.168.1.20", "AA:BB:CC:77:88:99", "br-lan", "Smart TV"),
            DeviceInfo("192.168.1.30", "AA:BB:CC:AA:BB:CC", "wlan0", "Tablet")
        ),
        interfaces = listOf(
            InterfaceStat("br-lan", 12_345_678_901L, 3_456_789_012L),
            InterfaceStat("eth0", 9_876_543_210L, 2_345_678_901L),
            InterfaceStat("wlan0", 1_234_567_890L, 987_654_321L)
        ),
        firmware = "OpenWrt 23.05.3",
        model = "Xiaomi Redmi Router AC2100"
    )
}
