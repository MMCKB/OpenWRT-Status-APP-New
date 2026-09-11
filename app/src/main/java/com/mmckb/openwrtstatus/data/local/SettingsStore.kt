package com.mmckb.openwrtstatus.data.local

import android.content.Context
import android.content.SharedPreferences
import com.mmckb.openwrtstatus.data.model.RouterConfig

/**
 * Persists [RouterConfig] in a private SharedPreferences file.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("openwrt_status_prefs", Context.MODE_PRIVATE)

    fun load(): RouterConfig = RouterConfig(
        ip = prefs.getString("ip", "192.168.1.1") ?: "192.168.1.1",
        port = prefs.getInt("port", 80),
        username = prefs.getString("username", "root") ?: "root",
        password = prefs.getString("password", "") ?: "",
        useHttps = prefs.getBoolean("useHttps", false),
        useMock = prefs.getBoolean("useMock", false),
        refreshIntervalSec = prefs.getInt("refreshIntervalSec", 5).coerceIn(2, 60)
    )

    fun save(config: RouterConfig) {
        prefs.edit().apply {
            putString("ip", config.ip)
            putInt("port", config.port)
            putString("username", config.username)
            putString("password", config.password)
            putBoolean("useHttps", config.useHttps)
            putBoolean("useMock", config.useMock)
            putInt("refreshIntervalSec", config.refreshIntervalSec)
        }.apply()
    }
}
