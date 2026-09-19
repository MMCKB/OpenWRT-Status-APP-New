package com.mmckb.openwrtstatus.data.local

import android.content.Context
import android.content.SharedPreferences
import com.mmckb.openwrtstatus.data.model.RouterConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the device list (multi-router support) plus the active device id in
 * SharedPreferences as JSON. A legacy single-router install is migrated into a
 * one-entry device list on first read.
 */
class SettingsStore(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("openwrt_status_prefs", Context.MODE_PRIVATE)

    /** 用户自定义背景图的调整参数。offset 为屏幕宽高的比例（-0.5..0.5）。 */
    data class BackgroundAdjust(
        val blurDp: Float = 0f,
        val scale: Float = 1f,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f
    )

    fun backgroundFile(): File = File(context.filesDir, "background.jpg")

    fun hasBackgroundImage(): Boolean = backgroundFile().exists()

    fun isBackgroundEnabled(): Boolean =
        prefs.getBoolean(KEY_BG_ENABLED, false) && hasBackgroundImage()

    fun saveBackgroundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BG_ENABLED, enabled).apply()
    }

    fun loadBackgroundAdjust(): BackgroundAdjust = BackgroundAdjust(
        blurDp = prefs.getFloat(KEY_BG_BLUR, 0f),
        scale = prefs.getFloat(KEY_BG_SCALE, 1f),
        offsetX = prefs.getFloat(KEY_BG_OFFSET_X, 0f),
        offsetY = prefs.getFloat(KEY_BG_OFFSET_Y, 0f)
    )

    fun saveBackgroundAdjust(adjust: BackgroundAdjust) {
        prefs.edit()
            .putFloat(KEY_BG_BLUR, adjust.blurDp)
            .putFloat(KEY_BG_SCALE, adjust.scale)
            .putFloat(KEY_BG_OFFSET_X, adjust.offsetX)
            .putFloat(KEY_BG_OFFSET_Y, adjust.offsetY)
            .putBoolean(KEY_BG_ENABLED, true)
            .apply()
    }

    /** 移除背景：清掉图片文件与全部设置。 */
    fun clearBackground() {
        backgroundFile().delete()
        prefs.edit()
            .putBoolean(KEY_BG_ENABLED, false)
            .putFloat(KEY_BG_BLUR, 0f)
            .putFloat(KEY_BG_SCALE, 1f)
            .putFloat(KEY_BG_OFFSET_X, 0f)
            .putFloat(KEY_BG_OFFSET_Y, 0f)
            .apply()
    }

    fun loadDevices(): List<RouterConfig> {
        val raw = prefs.getString(KEY_DEVICES, null)
        if (raw != null) {
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).map { deviceFromJson(array.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
        if (prefs.contains("ip")) {
            return listOf(loadLegacy().copy(id = ID_LEGACY))
        }
        return emptyList()
    }

    /** Stored active id if still valid, otherwise the first device. */
    fun loadActiveId(devices: List<RouterConfig>): String {
        val stored = prefs.getString(KEY_ACTIVE, null).orEmpty()
        return if (devices.any { it.id == stored }) stored else devices.firstOrNull()?.id.orEmpty()
    }

    /** Whether the realtime network-speed Live Update notification is enabled. */
    fun isSpeedNotificationEnabled(): Boolean = prefs.getBoolean(KEY_SPEED_NOTIFY, false)

    fun saveSpeedNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEED_NOTIFY, enabled).apply()
    }

    fun saveDevices(devices: List<RouterConfig>, activeId: String) {
        val array = JSONArray()
        devices.forEach { array.put(it.toJson()) }
        prefs.edit()
            .putString(KEY_DEVICES, array.toString())
            .putString(KEY_ACTIVE, activeId)
            .apply()
    }

    /** Legacy single-config storage, kept only to migrate old installs. */
    private fun loadLegacy(): RouterConfig = RouterConfig(
        ip = prefs.getString("ip", "192.168.1.1") ?: "192.168.1.1",
        port = prefs.getInt("port", 80),
        username = prefs.getString("username", "root") ?: "root",
        password = prefs.getString("password", "") ?: "",
        useHttps = prefs.getBoolean("useHttps", false),
        allowInsecureTls = prefs.getBoolean("allowInsecureTls", false),
        refreshIntervalSec = prefs.getInt("refreshIntervalSec", 5).coerceIn(2, 60),
        sshEnabled = prefs.getBoolean("sshEnabled", false),
        sshHost = prefs.getString("sshHost", "") ?: "",
        sshPort = prefs.getInt("sshPort", 22).coerceIn(1, 65535),
        sshUsername = prefs.getString("sshUsername", "root") ?: "root",
        sshPassword = prefs.getString("sshPassword", "") ?: ""
    )

    private fun RouterConfig.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("ip", ip)
        put("port", port)
        put("username", username)
        put("password", password)
        put("useHttps", useHttps)
        put("allowInsecureTls", allowInsecureTls)
        put("refreshIntervalSec", refreshIntervalSec)
        put("sshEnabled", sshEnabled)
        put("sshHost", sshHost)
        put("sshPort", sshPort)
        put("sshUsername", sshUsername)
        put("sshPassword", sshPassword)
    }

    private fun deviceFromJson(o: JSONObject): RouterConfig = RouterConfig(
        id = o.optString("id"),
        name = o.optString("name"),
        ip = o.optString("ip", "192.168.1.1"),
        port = o.optInt("port", 80),
        username = o.optString("username", "root"),
        password = o.optString("password"),
        useHttps = o.optBoolean("useHttps"),
        allowInsecureTls = o.optBoolean("allowInsecureTls"),
        refreshIntervalSec = o.optInt("refreshIntervalSec", 5).coerceIn(2, 60),
        sshEnabled = o.optBoolean("sshEnabled"),
        sshHost = o.optString("sshHost"),
        sshPort = o.optInt("sshPort", 22).coerceIn(1, 65535),
        sshUsername = o.optString("sshUsername", "root"),
        sshPassword = o.optString("sshPassword")
    )

    companion object {
        const val ID_LEGACY = "device-legacy"

        private const val KEY_DEVICES = "devices_json"
        private const val KEY_ACTIVE = "activeDeviceId"
        private const val KEY_SPEED_NOTIFY = "speed_notification_enabled"
        private const val KEY_BG_ENABLED = "bg_enabled"
        private const val KEY_BG_BLUR = "bg_blur"
        private const val KEY_BG_SCALE = "bg_scale"
        private const val KEY_BG_OFFSET_X = "bg_offset_x"
        private const val KEY_BG_OFFSET_Y = "bg_offset_y"
    }
}
