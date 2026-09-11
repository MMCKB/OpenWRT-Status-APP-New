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
        allowInsecureTls = prefs.getBoolean("allowInsecureTls", false),
        useMock = prefs.getBoolean("useMock", false),
        refreshIntervalSec = prefs.getInt("refreshIntervalSec", 5).coerceIn(2, 60),
        sshEnabled = prefs.getBoolean("sshEnabled", false),
        sshHost = prefs.getString("sshHost", "") ?: "",
        sshPort = prefs.getInt("sshPort", 22).coerceIn(1, 65535),
        sshUsername = prefs.getString("sshUsername", "root") ?: "root",
        sshPassword = prefs.getString("sshPassword", "") ?: ""
    )

    fun save(config: RouterConfig) {
        prefs.edit().apply {
            putString("ip", config.ip)
            putInt("port", config.port)
            putString("username", config.username)
            putString("password", config.password)
            putBoolean("useHttps", config.useHttps)
            putBoolean("allowInsecureTls", config.allowInsecureTls)
            putBoolean("useMock", config.useMock)
            putInt("refreshIntervalSec", config.refreshIntervalSec)
            putBoolean("sshEnabled", config.sshEnabled)
            putString("sshHost", config.sshHost)
            putInt("sshPort", config.sshPort)
            putString("sshUsername", config.sshUsername)
            putString("sshPassword", config.sshPassword)
        }.apply()
    }
}
