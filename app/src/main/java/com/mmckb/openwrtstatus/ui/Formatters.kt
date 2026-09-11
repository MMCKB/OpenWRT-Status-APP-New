package com.mmckb.openwrtstatus.ui

import java.util.Locale

/**
 * Human readable byte count, e.g. 1536 -> "1.5 KB".
 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024.0
        i++
    }
    return "%.1f %s".format(Locale.US, value, units[i])
}

/**
 * Throughput string, e.g. "1.2 MB/s".
 */
fun formatRate(bytesPerSec: Double): String = formatBytes(bytesPerSec.toLong()) + "/s"

/**
 * Uptime in days/hours/minutes/seconds, e.g. "14天 6时 32分 5秒".
 */
fun formatUptime(seconds: Long): String {
    if (seconds <= 0) return "—"
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return buildString {
        if (d > 0) append("${d}天 ")
        if (d > 0 || h > 0) append("${h}时 ")
        if (d > 0 || h > 0 || m > 0) append("${m}分 ")
        append("${s}秒")
    }
}
