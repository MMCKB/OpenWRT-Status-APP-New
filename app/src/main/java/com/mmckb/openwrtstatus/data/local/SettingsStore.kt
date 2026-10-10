package com.mmckb.openwrtstatus.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.migrations.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mmckb.openwrtstatus.data.model.RouterConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** App 深浅色模式（主题设置页三选一；默认跟随系统）。 */
enum class AppThemeMode(val storeValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStoreValue(value: String): AppThemeMode =
            entries.firstOrNull { it.storeValue == value } ?: SYSTEM
    }
}

private const val LEGACY_PREFS_NAME = "openwrt_status_prefs"
private const val DATASTORE_FILE = "datastore/openwrt_status_prefs.preferences_pb"
private val dataStoreLock = Any()

@Volatile
private var dataStoreInstance: DataStore<Preferences>? = null

/** 进程级 DataStore 单例：首次访问自动把旧 SharedPreferences 的键迁移进来（设备数据不丢）。 */
private fun settingsDataStore(context: Context): DataStore<Preferences> =
    dataStoreInstance ?: synchronized(dataStoreLock) {
        dataStoreInstance ?: PreferenceDataStoreFactory.create(
            migrations = listOf(
                SharedPreferencesMigration(
                    context = context,
                    sharedPreferencesName = LEGACY_PREFS_NAME,
                    migrate = { sharedPreferencesView, current ->
                        // 旧 SharedPreferences 的全部键值平移进 DataStore（类型保持）
                        val builder = current.toMutablePreferences()
                        sharedPreferencesView.getAll().forEach { (key, value) ->
                            when (value) {
                                is String -> builder[stringPreferencesKey(key)] = value
                                is Int -> builder[intPreferencesKey(key)] = value
                                is Boolean -> builder[booleanPreferencesKey(key)] = value
                                is Long -> builder[longPreferencesKey(key)] = value
                                is Float -> builder[floatPreferencesKey(key)] = value
                                is Double -> builder[doublePreferencesKey(key)] = value
                                is Set<*> -> builder[stringSetPreferencesKey(key)] =
                                    value.filterIsInstance<String>().toSet()
                            }
                        }
                        builder
                    }
                )
            ),
            produceFile = { File(context.filesDir, DATASTORE_FILE) }
        ).also { dataStoreInstance = it }
    }

/**
 * Persists the device list (multi-router support), the active device id, feature
 * toggles and SSH host key fingerprints (TOFU) in Jetpack DataStore.
 *
 * 全部读写为 suspend 函数，由调用方在自身作用域内执行（UI 线程不再做磁盘 I/O）。
 *
 * 敏感字段（路由器密码 / SSH 密码）经 [CredentialCipher] 加密后落盘，
 * 其余字段保持明文以便排错；旧版本遗留的明文密码会在首次读取时自动加密回写。
 */
class SettingsStore(private val context: Context) {

    private val data = settingsDataStore(context)

    /**
     * 读取设备列表。
     *
     * 整个流程跑在 [Dispatchers.IO]：JSON 映射会调用 [CredentialCipher]，
     * 而 Keystore 的首次取密钥/生成密钥是阻塞操作，不能在主线程做
     * （[com.mmckb.openwrtstatus.ui.RouterViewModel] 的 init 协程默认就在 Main）。
     */
    suspend fun loadDevices(): List<RouterConfig> = withContext(Dispatchers.IO) {
        val prefs = currentPrefs()
        val raw = prefs[KEY_DEVICES]
        if (raw != null) {
            val parsed = runCatching {
                val array = JSONArray(raw)
                var hasPlaintext = false
                val devices = (0 until array.length()).map { index ->
                    val obj = array.getJSONObject(index)
                    if (hasPlaintextSecret(obj)) hasPlaintext = true
                    deviceFromJson(obj)
                }
                devices to hasPlaintext
            }.getOrNull() ?: return@withContext emptyList()
            val (devices, hasPlaintext) = parsed
            // 旧版本以明文保存密码：读入后立即以密文回写，完成一次性迁移（失败则下次启动重试）。
            if (hasPlaintext) {
                val activeId = loadActiveId(devices)
                runCatching { saveDevices(devices, activeId) }
                    .onFailure { Log.w(TAG, "明文凭据迁移回写失败，下次启动会重试", it) }
            }
            return@withContext devices
        }
        if (prefs.contains(stringPreferencesKey("ip"))) {
            val legacy = loadLegacy(prefs).copy(id = ID_LEGACY)
            // 旧单机配置迁入设备列表：loadLegacy 已解出密码，saveDevices 会重新加密。
            // 落盘失败不阻断加载——本次仍以内存中的配置运行，下次启动重试。
            runCatching {
                saveDevices(listOf(legacy), ID_LEGACY)
                // 旧的单机键已无用，清掉避免明文密码残留（loadLegacy 的读取发生在上一步之前）。
                data.edit {
                    it.remove(stringPreferencesKey("password"))
                    it.remove(stringPreferencesKey("sshPassword"))
                }
            }.onFailure { Log.w(TAG, "旧单机配置迁移失败，下次启动会重试", it) }
            return@withContext listOf(legacy)
        }
        emptyList()
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

    /** App 深浅色模式（主题设置页），默认跟随系统。 */
    suspend fun themeMode(): AppThemeMode {
        val stored = currentPrefs()[KEY_THEME_MODE] ?: return AppThemeMode.SYSTEM
        return AppThemeMode.fromStoreValue(stored)
    }

    suspend fun saveThemeMode(mode: AppThemeMode) {
        data.edit { it[KEY_THEME_MODE] = mode.storeValue }
    }

    /** AMOLED 纯黑：深色模式下把背景与中性面压成纯黑（默认关闭）。 */
    suspend fun isAmoledDark(): Boolean = currentPrefs()[KEY_AMOLED] ?: false

    suspend fun saveAmoledDark(enabled: Boolean) {
        data.edit { it[KEY_AMOLED] = enabled }
    }

    /** 保存设备列表；与 [loadDevices] 一样整体跑在 IO——序列化会调用 Keystore 加密。 */
    suspend fun saveDevices(devices: List<RouterConfig>, activeId: String) {
        withContext(Dispatchers.IO) {
            val array = JSONArray()
            devices.forEach { array.put(it.toJson()) }
            data.edit {
                it[KEY_DEVICES] = array.toString()
                it[KEY_ACTIVE] = activeId
            }
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
        password = CredentialCipher.decrypt(prefs[stringPreferencesKey("password")] ?: ""),
        useHttps = prefs[booleanPreferencesKey("useHttps")] ?: false,
        allowInsecureTls = prefs[booleanPreferencesKey("allowInsecureTls")] ?: false,
        refreshIntervalSec = (prefs[intPreferencesKey("refreshIntervalSec")] ?: 5).coerceIn(2, 60),
        sshEnabled = prefs[booleanPreferencesKey("sshEnabled")] ?: false,
        sshHost = prefs[stringPreferencesKey("sshHost")] ?: "",
        sshPort = (prefs[intPreferencesKey("sshPort")] ?: 22).coerceIn(1, 65535),
        sshUsername = prefs[stringPreferencesKey("sshUsername")] ?: "root",
        sshPassword = CredentialCipher.decrypt(prefs[stringPreferencesKey("sshPassword")] ?: "")
    )

    /** 该 JSON 对象里是否还残留未加密的非空密码（用于触发一次性迁移回写）。 */
    private fun hasPlaintextSecret(o: JSONObject): Boolean {
        val password = o.optString("password")
        val sshPassword = o.optString("sshPassword")
        return (password.isNotEmpty() && !CredentialCipher.isEncrypted(password)) ||
            (sshPassword.isNotEmpty() && !CredentialCipher.isEncrypted(sshPassword))
    }

    private fun RouterConfig.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("ip", ip)
        put("port", port)
        put("username", username)
        // 敏感字段：加密后落盘（见 CredentialCipher）。
        put("password", CredentialCipher.encrypt(password))
        put("useHttps", useHttps)
        put("allowInsecureTls", allowInsecureTls)
        put("refreshIntervalSec", refreshIntervalSec)
        put("sshEnabled", sshEnabled)
        put("sshHost", sshHost)
        put("sshPort", sshPort)
        put("sshUsername", sshUsername)
        put("sshPassword", CredentialCipher.encrypt(sshPassword))
    }

    private fun deviceFromJson(o: JSONObject): RouterConfig = RouterConfig(
        id = o.optString("id"),
        name = o.optString("name"),
        ip = o.optString("ip", "192.168.1.1"),
        port = o.optInt("port", 80),
        username = o.optString("username", "root"),
        // 无前缀的旧值会被 decrypt 原样返回，从而兼容明文遗留数据。
        password = CredentialCipher.decrypt(o.optString("password")),
        useHttps = o.optBoolean("useHttps"),
        allowInsecureTls = o.optBoolean("allowInsecureTls"),
        refreshIntervalSec = o.optInt("refreshIntervalSec", 5).coerceIn(2, 60),
        sshEnabled = o.optBoolean("sshEnabled"),
        sshHost = o.optString("sshHost"),
        sshPort = o.optInt("sshPort", 22),
        sshUsername = o.optString("sshUsername", "root"),
        sshPassword = CredentialCipher.decrypt(o.optString("sshPassword"))
    )

    companion object {
        private const val TAG = "SettingsStore"
        const val ID_LEGACY = "device-legacy"

        private val KEY_DEVICES = stringPreferencesKey("devices_json")
        private val KEY_ACTIVE = stringPreferencesKey("activeDeviceId")
        private val KEY_CONN_NOTIFY = booleanPreferencesKey("connection_notify_enabled")
        private val KEY_TOOLS_GRID = booleanPreferencesKey("tools_grid_enabled")
        private val KEY_TERMINAL_INLINE = booleanPreferencesKey("terminal_inline_input")
        private val KEY_HIDDEN_DIAG = booleanPreferencesKey("hidden_diag_unlocked")
        private val KEY_NOTIF_ASKED = booleanPreferencesKey("notif_permission_asked")
        private val KEY_SSH_KEYS = stringPreferencesKey("ssh_host_keys")
        private val KEY_THEME_MODE = stringPreferencesKey("app_theme_mode")
        private val KEY_AMOLED = booleanPreferencesKey("app_theme_amoled")
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

/**
 * 主题设置的进程级状态（深浅色模式 + AMOLED 纯黑）。
 *
 * 全部 16 个 Activity 的 `OpenWrtStatusTheme` 都从这里读当前主题——任一页面修改，
 * 所有存活的 Activity 即时重组换色（StateFlow 热流，跨 Activity 生效）。
 *
 * 预热时机：各 Activity onCreate 里的 `setupEdgeToEdge()` 调用 [ensureLoaded]，
 * 首个 Activity 在 setContent 前同步读一次 DataStore（runBlocking + 超时兜底），
 * 保证首帧即用户所选主题；进程内后续调用直接命中内存值。
 */
object ThemePrefs {

    data class State(
        val mode: AppThemeMode = AppThemeMode.SYSTEM,
        val amoled: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile
    private var store: SettingsStore? = null

    @Volatile
    private var loaded = false

    private val writeScope = CoroutineScope(Dispatchers.IO)

    fun init(store: SettingsStore) {
        if (this.store == null) this.store = store
    }

    /** 从 DataStore 读入内存（幂等；进程内通常只在预热时执行一次）。 */
    suspend fun load() {
        val s = store ?: return
        _state.value = State(mode = s.themeMode(), amoled = s.isAmoledDark())
        loaded = true
    }

    /**
     * 同步预热（主线程 onCreate 调用）：DataStore 首读很快（文件很小），超时兜底防
     * 极端磁盘慢——此时先按默认主题渲染并异步补读，避免卡住首帧。
     */
    fun ensureLoaded(context: Context) {
        init(SettingsStore(context.applicationContext))
        if (loaded) return
        runBlocking { withTimeoutOrNull(400) { load() } }
        if (!loaded) {
            writeScope.launch { runCatching { load() } }
        }
    }

    /** 当前生效的深色状态（同步读内存；跟随系统时按传入的系统判定）。 */
    fun isDarkTheme(systemDark: Boolean): Boolean = when (_state.value.mode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    fun setMode(mode: AppThemeMode) {
        _state.value = _state.value.copy(mode = mode)
        writeScope.launch { store?.saveThemeMode(mode) }
    }

    fun setAmoled(enabled: Boolean) {
        _state.value = _state.value.copy(amoled = enabled)
        writeScope.launch { store?.saveAmoledDark(enabled) }
    }
}
