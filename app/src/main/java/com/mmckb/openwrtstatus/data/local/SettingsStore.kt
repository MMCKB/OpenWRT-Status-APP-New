package com.mmckb.openwrtstatus.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.ReplaceFileCorruptionHandler
import androidx.datastore.migrations.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mmckb.openwrtstatus.data.model.RouterConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val LEGACY_PREFS_NAME = "openwrt_status_prefs"
private const val DATASTORE_FILE = "datastore/openwrt_status_prefs.preferences_pb"
private val dataStoreLock = Any()

@Volatile
private var dataStoreInstance: DataStore<Preferences>? = null

/** 进程级 DataStore 单例：首次访问自动把旧 SharedPreferences 的键迁移进来（设备数据不丢）。 */
private fun settingsDataStore(context: Context): DataStore<Preferences> =
    dataStoreInstance ?: synchronized(dataStoreLock) {
        dataStoreInstance ?: PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            migrations = listOf(SharedPreferencesMigration(context, LEGACY_PREFS_NAME)),
            produceFile = { File(context.filesDir, DATASTORE_FILE) }
        ).also { dataStoreInstance = it }
    }

/**
 * Persists the device list (multi-router support), the active device id, feature
 * toggles and SSH host key fingerprints (TOFU) in Jetpack DataStore.
 *
 * 全部读写为 suspend 函数，由调用方在自身作用域内执行（UI 线程不再做磁盘 I/O）。
 */
class SettingsStore(private val context: Context) {

    private val data = settingsDataStore(context)

    suspend fun loadDevices(): List<RouterConfig> {
        val prefs = currentPrefs()
        val raw = prefs[KEY_DEVICES]
        if (raw != null) {
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).map { deviceFromJson(array.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
        if (prefs.contains(stringPreferencesKey("ip"))) {
            return listOf(loadLegacy(prefs).copy(id = ID_LEGACY))
        }
        return emptyList()
    }

    /** Stored active id if still valid, otherwise the first device. */
    suspend fun loadActiveId(devices: List<RouterConfig>): String {
        val stored = currentPrefs()[KEY_ACTIVE].orEmpty()
        return if (devices.any { it.id == stored }) stored else devices.firstOrNull()?.id.orEmpty()
    }

    /** 是否启用路由器连接/断开状态通知（默认开启）。 */
    suspend fun isConnectionNotifyEnabled(): Boolean = currentPrefs()[KEY_CONN_NOTIFY] ?: true

    suspend fun saveConnectionNotifyEnabled(enabled: Boolean) {
        data.edit { it[KEY_CONN_NOTIFY] = enabled }
    }

    /** 工具页排版：true = 两列磁贴；默认 false = 列表卡片。 */
    suspend fun isToolsGridEnabled(): Boolean = currentPrefs()[KEY_TOOLS_GRID] ?: false

    suspend fun saveToolsGridEnabled(enabled: Boolean) {
        data.edit { it[KEY_TOOLS_GRID] = enabled }
    }

    /** 终端直接输入：true = 输出区底部内联输入（无独立输入框/发送键）。 */
    suspend fun isTerminalInlineInput(): Boolean = currentPrefs()[KEY_TERMINAL_INLINE] ?: false

    suspend fun saveTerminalInlineInput(enabled: Boolean) {
        data.edit { it[KEY_TERMINAL_INLINE] = enabled }
    }

    /** 隐藏的设备扩展信息（内存/存储/端口状态）：关于页图标连点 7 次解锁。 */
    suspend fun isHiddenDiagUnlocked(): Boolean = currentPrefs()[KEY_HIDDEN_DIAG] ?: false

    suspend fun saveHiddenDiagUnlocked(unlocked: Boolean) {
        data.edit { it[KEY_HIDDEN_DIAG] = unlocked }
    }

    suspend fun saveDevices(devices: List<RouterConfig>, activeId: String) {
        val array = JSONArray()
        devices.forEach { array.put(it.toJson()) }
        data.edit {
            it[KEY_DEVICES] = array.toString()
            it[KEY_ACTIVE] = activeId
        }
    }

    /** 通知运行时权限是否已向用户请求过（首次进主界面只请求一次）。 */
    suspend fun isNotifPermissionAsked(): Boolean = currentPrefs()[KEY_NOTIF_ASKED] ?: false

    suspend fun saveNotifPermissionAsked() {
        data.edit { it[KEY_NOTIF_ASKED] = true }
    }

    /** SSH 主机指纹（TOFU）：host:port -> 指纹。 */
    suspend fun sshHostKeys(): Map<String, String> {
        val prefs = currentPrefs()
        val raw = prefs[KEY_SSH_KEYS] ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { key -> o.optString(key) }
        }.getOrDefault(emptyMap())
    }

    suspend fun saveSshHostKey(hostPort: String, fingerprint: String) {
        data.edit { prefs ->
            val o = JSONObject(prefs[KEY_SSH_KEYS] ?: "{}")
            o.put(hostPort, fingerprint)
            prefs[KEY_SSH_KEYS] = o.toString()
        }
    }

    private suspend fun currentPrefs(): Preferences =
        runCatching { data.data.first() }.getOrDefault(emptyPreferences())

    /** Legacy single-config storage（键已随迁移进入 DataStore，仅用于老安装的迁移读取）。 */
    private fun loadLegacy(prefs: Preferences): RouterConfig = RouterConfig(
        ip = prefs[stringPreferencesKey("ip")] ?: "192.168.1.1",
        port = prefs[intPreferencesKey("port")] ?: 80,
        username = prefs[stringPreferencesKey("username")] ?: "root",
        password = prefs[stringPreferencesKey("password")] ?: "",
        useHttps = prefs[booleanPreferencesKey("useHttps")] ?: false,
        allowInsecureTls = prefs[booleanPreferencesKey("allowInsecureTls")] ?: false,
        refreshIntervalSec = (prefs[intPreferencesKey("refreshIntervalSec")] ?: 5).coerceIn(2, 60),
        sshEnabled = prefs[booleanPreferencesKey("sshEnabled")] ?: false,
        sshHost = prefs[stringPreferencesKey("sshHost")] ?: "",
        sshPort = (prefs[intPreferencesKey("sshPort")] ?: 22).coerceIn(1, 65535),
        sshUsername = prefs[stringPreferencesKey("sshUsername")] ?: "root",
        sshPassword = prefs[stringPreferencesKey("sshPassword")] ?: ""
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
        sshPort = o.optInt("sshPort", 22),
        sshUsername = o.optString("sshUsername", "root"),
        sshPassword = o.optString("sshPassword")
    )

    companion object {
        const val ID_LEGACY = "device-legacy"

        private val KEY_DEVICES = stringPreferencesKey("devices_json")
        private val KEY_ACTIVE = stringPreferencesKey("activeDeviceId")
        private val KEY_CONN_NOTIFY = booleanPreferencesKey("connection_notify_enabled")
        private val KEY_TOOLS_GRID = booleanPreferencesKey("tools_grid_enabled")
        private val KEY_TERMINAL_INLINE = booleanPreferencesKey("terminal_inline_input")
        private val KEY_HIDDEN_DIAG = booleanPreferencesKey("hidden_diag_unlocked")
        private val KEY_NOTIF_ASKED = booleanPreferencesKey("notif_permission_asked")
        private val KEY_SSH_KEYS = stringPreferencesKey("ssh_host_keys")
    }
}

/**
 * SSH 主机指纹（TOFU）的进程级内存缓存：HostKeyRepository 的同步回调从这里读，
 * 首次记录同时异步落盘到 [SettingsStore]。RouterViewModel 初始化时调用 [init]，
 * 并在其加载协程里调用 [load]。
 */
object SshHostKeys {

    private val keys = ConcurrentHashMap<String, String>()

    @Volatile
    private var store: SettingsStore? = null

    private val writeScope = CoroutineScope(Dispatchers.IO)

    fun init(store: SettingsStore) {
        this.store = store
    }

    /** 启动时从存储加载已记录的指纹。 */
    suspend fun load() {
        store?.sshHostKeys()?.forEach { (hostPort, fp) -> keys[hostPort] = fp }
    }

    fun fingerprint(hostPort: String): String? = keys[hostPort]

    fun remember(hostPort: String, fingerprint: String) {
        keys[hostPort] = fingerprint
        writeScope.launch { store?.saveSshHostKey(hostPort, fingerprint) }
    }
}
