package com.mmckb.openwrtstatus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mmckb.openwrtstatus.data.model.RouterConfig
import com.mmckb.openwrtstatus.data.model.SshConfig
import com.mmckb.openwrtstatus.data.remote.AdminClient
import com.mmckb.openwrtstatus.data.remote.DropbearInstance
import com.mmckb.openwrtstatus.data.remote.RepoPublicKey
import com.mmckb.openwrtstatus.data.remote.RouterException
import com.mmckb.openwrtstatus.data.remote.SshPublicKey
import com.mmckb.openwrtstatus.data.remote.SshPublicKeyDecoder
import com.mmckb.openwrtstatus.ui.components.AppAlertType
import com.mmckb.openwrtstatus.ui.components.AppBackButton
import com.mmckb.openwrtstatus.ui.components.AppDialog
import com.mmckb.openwrtstatus.ui.components.AppSwitch
import com.mmckb.openwrtstatus.ui.components.AppTextField
import com.mmckb.openwrtstatus.ui.components.ConnectionMonitor
import com.mmckb.openwrtstatus.ui.components.SmoothOptionSwitcher
import com.mmckb.openwrtstatus.ui.components.StackedAlertHost
import com.mmckb.openwrtstatus.ui.components.rememberAlertStackState
import com.mmckb.openwrtstatus.ui.theme.LocalAppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 应用类操作（uci apply + 确认窗口）的总时长上限。 */
private const val ADMIN_APPLY_TIMEOUT_MS = 100_000L
/** 普通操作（读写密钥、公钥文件等）的总时长上限。 */
private const val ADMIN_OP_TIMEOUT_MS = 45_000L
/** 读取类操作的总时长上限。 */
private const val ADMIN_LOAD_TIMEOUT_MS = 30_000L

/** 统一的失败文案：超时（连接中断/无响应）给出可操作的提示，其余透出原始信息。 */
private fun adminErrText(e: Exception): String = when {
    e is TimeoutCancellationException -> "路由器连接中断或长时间无响应，请检查网络后重试。"
    e is CancellationException -> "连接已断开，操作已终止。"
    else -> e.message ?: "请稍后重试。"
}

/** 接口选择弹窗状态（Dropbear 绑定接口用）。 */
private data class AdmSelectState(
    val title: String,
    val options: List<Pair<String, String>>,
    val selected: String,
    val onPick: (String) -> Unit
)

/**
 * 管理权页（工具页入口，LuCI admin/system/admin 的完整复刻），五个页签：
 * 路由器密码 / SSH 访问（Dropbear 实例）/ SSH 密钥 / HTTP(S) 访问 / 软件包仓库公钥。
 * 各选项与 LuCI 一一对应，保存/删除行为也与 LuCI 一致（密码与密钥即时生效，
 * uci 配置走「保存并应用」）。
 */
@Composable
fun AdminScreen(
    config: RouterConfig,
    sshEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val client = remember { AdminClient() }
    val sshOrNull = remember(config) {
        if (sshEnabled) {
            SshConfig(
                host = config.sshHost.ifBlank { config.ip },
                port = config.sshPort,
                username = config.sshUsername,
                password = config.sshPassword
            )
        } else null
    }

    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val alertStack = rememberAlertStackState()
    val density = LocalDensity.current
    var alertTopPadding by remember { mutableStateOf(0.dp) }
    var tab by remember { mutableStateOf("password") }

    fun setAlert(type: AppAlertType, title: String, description: String? = null) {
        alertStack.push(type, title, description)
    }

    /** 点击保存/应用前的快速检查：已断开则直接红色提示，不再发起操作。 */
    fun ensureConnected(): Boolean {
        if (ConnectionMonitor.status.value == ConnectionMonitor.Status.Offline) {
            setAlert(AppAlertType.Error, "与路由器已断开连接", "请检查手机与路由器的网络后重试。")
            return false
        }
        return true
    }

    // 跟踪进行中的操作；路由器断开连接时立即取消，按钮即刻恢复并弹红色提示
    var opJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) {
        ConnectionMonitor.status.collect { status ->
            if (status == ConnectionMonitor.Status.Offline) opJob?.cancel()
        }
    }

    // --- 路由器密码 ---
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var pw1Visible by remember { mutableStateOf(false) }
    var pw2Visible by remember { mutableStateOf(false) }

    // --- SSH 访问 ---
    var instances by remember { mutableStateOf<List<DropbearInstance>>(emptyList()) }
    var deletedSections by remember { mutableStateOf<List<String>>(emptyList()) }
    var networks by remember { mutableStateOf<List<String>>(emptyList()) }

    // --- SSH 密钥 ---
    var sshKeyLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var sshKeyInput by remember { mutableStateOf("") }

    // --- HTTP(S) 访问 ---
    var httpRedirect by remember { mutableStateOf(false) }

    // --- 仓库公钥 ---
    var repoDir by remember { mutableStateOf("/etc/apk/keys") }
    var repoKeys by remember { mutableStateOf<List<RepoPublicKey>>(emptyList()) }
    var repoInput by remember { mutableStateOf("") }

    // 弹窗
    var selectState by remember { mutableStateOf<AdmSelectState?>(null) }
    var confirmSshKey by remember { mutableStateOf<SshPublicKey?>(null) }
    var confirmRepoKey by remember { mutableStateOf<RepoPublicKey?>(null) }
    var confirmInstanceIndex by remember { mutableStateOf<Int?>(null) }

    fun load() {
        opJob = scope.launch {
            loading = true
            var hadError: String? = null
            try {
                val d = withTimeout(ADMIN_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.loadDropbear(config) }
                }
                instances = d
                deletedSections = emptyList()
                networks = withTimeout(ADMIN_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.listNetworks(config) }
                }
            } catch (e: Exception) {
                hadError = adminErrText(e)
            }
            try {
                sshKeyLines = withTimeout(ADMIN_OP_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.loadAuthorizedKeys(config, sshOrNull) }
                }
            } catch (e: Exception) {
                hadError = hadError ?: adminErrText(e)
            }
            try {
                httpRedirect = withTimeout(ADMIN_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.loadHttpRedirect(config) }
                }
            } catch (e: Exception) {
                hadError = hadError ?: adminErrText(e)
            }
            try {
                val loaded = withTimeout(ADMIN_OP_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.loadRepoKeys(config, sshOrNull) }
                }
                if (loaded != null) {
                    repoDir = loaded.first
                    repoKeys = loaded.second
                } else {
                    hadError = hadError ?: "无法读取仓库公钥（需要开启 SSH）。"
                }
            } catch (e: Exception) {
                hadError = hadError ?: adminErrText(e)
            }
            if (hadError != null) setAlert(AppAlertType.Error, "部分内容读取失败", hadError)
            loading = false
        }
    }

    var loadedOnce by remember { mutableStateOf(false) }
    if (!loadedOnce) {
        loadedOnce = true
        load()
    }

    fun saveDropbear() {
        if (!ensureConnected()) return
        opJob = scope.launch {
            busy = true
            try {
                withTimeout(ADMIN_APPLY_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        client.applyDropbear(
                            config, instances, deletedSections.toList(), sshOrNull
                        ) { phase -> setAlert(AppAlertType.Info, phase) }
                    }
                }
                setAlert(AppAlertType.Success, "已保存并应用")
                load()
            } catch (e: RouterException) {
                setAlert(
                    AppAlertType.Error, "保存失败",
                    if (e.ubusCode == 6) "本固件限制了无 SSH 的配置修改，请先在设备编辑页开启 SSH。"
                    else adminErrText(e)
                )
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "保存失败", adminErrText(e))
            } finally {
                busy = false
            }
        }
    }

    fun savePassword() {
        if (!ensureConnected()) return
        when {
            pw1.isEmpty() -> setAlert(AppAlertType.Error, "请输入新密码。")
            pw1 != pw2 -> setAlert(AppAlertType.Error, "两次输入的密码不一致", "密码未修改！")
            else -> opJob = scope.launch {
                busy = true
                try {
                    val ok = withTimeout(ADMIN_OP_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { client.changePassword(config, "root", pw1) }
                    }
                    if (ok) {
                        setAlert(AppAlertType.Success, "系统密码已成功修改")
                        pw1 = ""
                        pw2 = ""
                    } else {
                        setAlert(
                            AppAlertType.Error, "修改系统密码失败",
                            "密码未通过系统校验，请换一个更复杂的密码。"
                        )
                    }
                } catch (e: Exception) {
                    setAlert(AppAlertType.Error, "修改系统密码失败", adminErrText(e))
                } finally {
                    busy = false
                }
            }
        }
    }

    fun saveHttp() {
        if (!ensureConnected()) return
        opJob = scope.launch {
            busy = true
            try {
                withTimeout(ADMIN_APPLY_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        client.applyHttpRedirect(config, httpRedirect, sshOrNull) { phase ->
                            setAlert(AppAlertType.Info, phase)
                        }
                    }
                }
                setAlert(AppAlertType.Success, "已保存并应用")
            } catch (e: RouterException) {
                setAlert(
                    AppAlertType.Error, "保存失败",
                    if (e.ubusCode == 6) "本固件限制了无 SSH 的配置修改，请先在设备编辑页开启 SSH。"
                    else adminErrText(e)
                )
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "保存失败", adminErrText(e))
            } finally {
                busy = false
            }
        }
    }

    fun addSshKey() {
        if (!ensureConnected()) return
        val key = sshKeyInput.trim()
        if (key.isEmpty()) return
        when {
            sshKeyLines.any { it == key } -> setAlert(AppAlertType.Warning, "该 SSH 公钥已存在。")
            SshPublicKeyDecoder.decode(key) == null ->
                setAlert(AppAlertType.Error, "SSH 公钥无效", "请提供有效的 RSA、ED25519 或 ECDSA 公钥。")
            else -> opJob = scope.launch {
                busy = true
                try {
                    val newKeys = sshKeyLines + key
                    withTimeout(ADMIN_OP_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { client.saveAuthorizedKeys(config, newKeys, sshOrNull) }
                    }
                    sshKeyLines = newKeys
                    sshKeyInput = ""
                    setAlert(AppAlertType.Success, "密钥已添加")
                } catch (e: Exception) {
                    setAlert(AppAlertType.Error, "添加密钥失败", adminErrText(e))
                } finally {
                    busy = false
                }
            }
        }
    }

    fun removeSshKey(key: SshPublicKey) {
        if (!ensureConnected()) return
        opJob = scope.launch {
            busy = true
            try {
                val newKeys = sshKeyLines.filter { it != key.source }
                withTimeout(ADMIN_OP_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.saveAuthorizedKeys(config, newKeys, sshOrNull) }
                }
                sshKeyLines = newKeys
                setAlert(AppAlertType.Success, "密钥已删除")
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "删除密钥失败", adminErrText(e))
            } finally {
                busy = false
            }
        }
    }

    fun addRepoKeyContent(content: String, baseName: String?) {
        if (!ensureConnected()) return
        opJob = scope.launch {
            busy = true
            try {
                val isApk = repoDir == "/etc/apk/keys"
                if (isApk && !AdminClient.isValidPem(content)) {
                    setAlert(AppAlertType.Error, "密钥格式无效", "该密钥不是 PEM 格式（apk 环境要求 PEM 公钥）。")
                    return@launch
                }
                if (!isApk && AdminClient.isValidPem(content)) {
                    setAlert(AppAlertType.Error, "密钥格式无效", "该密钥是 PEM 格式，opkg 环境不支持 PEM 公钥。")
                    return@launch
                }
                val normalized = content.replace(Regex("\\s+"), " ").trim()
                if (repoKeys.any {
                        it.content.replace(Regex("\\s+"), " ").trim() == normalized
                    }
                ) {
                    setAlert(AppAlertType.Warning, "该仓库公钥已存在。")
                    return@launch
                }
                val filename = withTimeout(ADMIN_OP_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        client.addRepoKey(config, repoDir, content, baseName, sshOrNull)
                    }
                }
                repoKeys = repoKeys + RepoPublicKey(filename, content, AdminClient.isProtectedRepoKey(filename))
                repoInput = ""
                setAlert(AppAlertType.Success, "公钥已添加", filename)
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "添加公钥失败", adminErrText(e))
            } finally {
                busy = false
            }
        }
    }

    fun addRepoKey() {
        if (!ensureConnected()) return
        val raw = repoInput.trim()
        if (raw.isEmpty()) return
        if (Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(raw)) {
            opJob = scope.launch {
                busy = true
                try {
                    val (content, name) = withTimeout(ADMIN_OP_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { client.fetchKeyFromUrl(raw) }
                    }
                    addRepoKeyContent(content, name)
                } catch (e: Exception) {
                    setAlert(AppAlertType.Error, "拉取公钥失败", adminErrText(e))
                    busy = false
                }
            }
        } else {
            addRepoKeyContent(raw, null)
        }
    }

    fun removeRepoKey(key: RepoPublicKey) {
        if (!ensureConnected()) return
        opJob = scope.launch {
            busy = true
            try {
                withTimeout(ADMIN_OP_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { client.deleteRepoKey(config, repoDir, key.filename, sshOrNull) }
                }
                repoKeys = repoKeys.filterNot { it.filename == key.filename }
                setAlert(AppAlertType.Success, "公钥已删除")
            } catch (e: Exception) {
                setAlert(AppAlertType.Error, "删除公钥失败", adminErrText(e))
            } finally {
                busy = false
            }
        }
    }

    fun updateInstance(index: Int, transform: (DropbearInstance) -> DropbearInstance) {
        instances = instances.mapIndexed { i, inst -> if (i == index) transform(inst) else inst }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 2.dp, bottom = 0.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBackButton(onBack = onBack)
                Spacer(Modifier.weight(1f))
            }
            Text(
                "管理权",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Spacer(Modifier.height(10.dp))
            SmoothOptionSwitcher(
                options = listOf(
                    "password" to "密码",
                    "ssh" to "SSH 访问",
                    "keys" to "SSH 密钥",
                    "http" to "HTTP(S)",
                    "repo" to "仓库公钥"
                ),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        // 记录选择器底边在页面中的位置：提示栈浮层的顶部锚点
                        alertTopPadding = with(density) {
                            (coords.positionInParent().y + coords.size.height).toDp() + 8.dp
                        }
                    }
            )
            Spacer(Modifier.height(10.dp))

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (tab) {
                        "password" -> {
                            AdmDescText("更改访问设备的管理员密码")
                            AppTextField(
                                value = pw1,
                                onValueChange = { pw1 = it },
                                singleLine = true,
                                label = { Text("密码") },
                                visualTransformation = if (pw1Visible) VisualTransformation.None
                                else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(
                                        onClick = { pw1Visible = !pw1Visible },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            if (pw1Visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = "显示/隐藏 密码",
                                            modifier = Modifier.size(20.dp),
                                            tint = colors.onSurfaceVariant
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            val strength = passwordStrength(pw1)
                            strength?.let { label ->
                                val strengthColor = when (label) {
                                    "强" -> colors.success
                                    "中" -> Color(0xFFE58E2A)
                                    else -> colors.error
                                }
                                Text(
                                    "密码强度：$label",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = strengthColor
                                )
                            }
                            AppTextField(
                                value = pw2,
                                onValueChange = { pw2 = it },
                                singleLine = true,
                                label = { Text("确认密码") },
                                visualTransformation = if (pw2Visible) VisualTransformation.None
                                else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(
                                        onClick = { pw2Visible = !pw2Visible },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            if (pw2Visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = "显示/隐藏 密码",
                                            modifier = Modifier.size(20.dp),
                                            tint = colors.onSurfaceVariant
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = { savePassword() },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (busy) "正在修改…" else "保存") }
                        }

                        "ssh" -> {
                            AdmDescText("Dropbear 提供 SSH 访问和 SCP 服务")
                            AdmSectionTitle("Dropbear 实例")
                            instances.forEachIndexed { index, inst ->
                                DropbearInstanceCard(
                                    inst = inst,
                                    networks = networks,
                                    busy = busy,
                                    onSelect = { title, current, onPick ->
                                        selectState = AdmSelectState(
                                            title,
                                            listOf("" to "全部接口") + networks.map { it to it },
                                            current,
                                            onPick
                                        )
                                    },
                                    onDelete = { confirmInstanceIndex = index },
                                    onChange = { transform -> updateInstance(index, transform) }
                                )
                                if (index != instances.lastIndex) {
                                    HorizontalDivider(color = colors.outline, modifier = Modifier.padding(vertical = 2.dp))
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    instances = instances + DropbearInstance(
                                        section = "",
                                        enabled = true,
                                        directBind = false,
                                        directInterface = "",
                                        interface_ = "",
                                        port = "",
                                        passwordAuth = true,
                                        rootPasswordAuth = true,
                                        gatewayPorts = false
                                    )
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("添加实例")
                            }
                            Button(
                                onClick = { saveDropbear() },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (busy) "正在应用…" else "保存并应用") }
                            OutlinedButton(
                                onClick = { load() },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("重置") }
                        }

                        "keys" -> {
                            AdmDescText(
                                "与使用普通密码相比，公钥允许无密码 SSH 登录且具有更高的安全性。" +
                                    "将 OpenSSH 兼容的公钥粘贴到下方即可上传到设备。"
                            )
                            if (sshKeyLines.isEmpty()) {
                                AdmDescText("当前还没有公钥。")
                            }
                            sshKeyLines.forEach { line ->
                                val decoded = SshPublicKeyDecoder.decode(line)
                                if (decoded != null) {
                                    SshKeyRow(
                                        key = decoded,
                                        enabled = !busy,
                                        onDelete = { confirmSshKey = decoded }
                                    )
                                } else {
                                    SshKeyRow(
                                        key = SshPublicKey(
                                            source = line, kind = "未知", bits = null, curve = null,
                                            comment = "无法解析的密钥", options = emptyList(), displayKey = line
                                        ),
                                        enabled = !busy,
                                        onDelete = { confirmSshKey = SshPublicKey(
                                            source = line, kind = "未知", bits = null, curve = null,
                                            comment = "无法解析的密钥", options = emptyList(), displayKey = line
                                        ) }
                                    )
                                }
                            }
                            AppTextField(
                                value = sshKeyInput,
                                onValueChange = { sshKeyInput = it },
                                label = { Text("粘贴或拖动 SSH 密钥文件…") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = { addSshKey() },
                                enabled = !busy && sshKeyInput.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (busy) "正在保存…" else "添加密钥") }
                        }

                        "http" -> {
                            AdmDescText("uHTTPd 提供 HTTP 和 HTTPS 网络访问服务。")
                            AdmSectionTitle("设置")
                            AdmSettingRow(
                                label = "重定向到 HTTPS",
                                description = "自动将 HTTP 请求重定向至 HTTPS 端口。",
                                checked = httpRedirect,
                                enabled = !busy
                            ) { httpRedirect = it }
                            Button(
                                onClick = { saveHttp() },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (busy) "正在应用…" else "保存并应用") }
                        }

                        else -> {
                            AdmDescText(
                                "每个软件仓库的公钥（来自官方或第三方仓库）使其签名的软件包能够被包管理器安装。" +
                                    "每个公钥都以文件的形式存储在 $repoDir 文件夹中。"
                            )
                            repoKeys.forEach { key ->
                                RepoKeyRow(
                                    key = key,
                                    enabled = !busy,
                                    onDelete = { confirmRepoKey = key }
                                )
                            }
                            AppTextField(
                                value = repoInput,
                                onValueChange = { repoInput = it },
                                label = { Text("粘贴或拖动软件包仓库公钥") },
                                placeholder = { Text("粘贴文件内容、密钥文件的 URL，或直接拖到此处以上传软件包仓库公钥…") },
                                minLines = 3,
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = { addRepoKey() },
                                enabled = !busy && repoInput.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (busy) "正在保存…" else "添加密钥") }
                        }
                    }
                    // 背景延伸到手势条区域，最后一张卡片垫在 inset 之上
                    Spacer(Modifier.navigationBarsPadding().height(6.dp))
                }
            }
        }

        // 悬浮提示栈：浮在内容上方（不推挤布局），位于方案选择器正下方；
        // 反复触发堆叠（最多 3 层），从第 3 层到第 1 层连续加速消失
        StackedAlertHost(
            state = alertStack,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = alertTopPadding)
        )

        // 接口选择对话框
        selectState?.let { sel ->
            AppDialog(
                title = sel.title,
                confirmLabel = "关闭",
                dismissLabel = "",
                onConfirm = { selectState = null },
                onDismiss = { selectState = null }
            ) {
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    sel.options.forEach { (value, label) ->
                        val isSelected = value == sel.selected
                        Text(
                            text = if (isSelected) "● $label" else label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) colors.primary else colors.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sel.onPick(value)
                                    selectState = null
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        )
                    }
                }
            }
        }

        // 删除 Dropbear 实例确认
        confirmInstanceIndex?.let { index ->
            AppDialog(
                title = "删除实例",
                message = "确定删除该 Dropbear 实例？删除后需「保存并应用」才会生效。",
                confirmLabel = "删除",
                confirmColor = colors.error,
                onConfirm = {
                    val inst = instances.getOrNull(index)
                    if (inst != null) {
                        if (inst.section.isNotBlank()) {
                            deletedSections = deletedSections + inst.section
                        }
                        instances = instances.filterIndexed { i, _ -> i != index }
                    }
                    confirmInstanceIndex = null
                },
                onDismiss = { confirmInstanceIndex = null }
            )
        }

        // 删除 SSH 公钥确认（LuCI：展示完整密钥行）
        confirmSshKey?.let { key ->
            AppDialog(
                title = "删除密钥",
                message = "确定删除以下 SSH 密钥？\n\n${key.source}",
                confirmLabel = "删除密钥",
                confirmColor = colors.error,
                onConfirm = {
                    removeSshKey(key)
                    confirmSshKey = null
                },
                onDismiss = { confirmSshKey = null }
            )
        }

        // 删除仓库公钥确认
        confirmRepoKey?.let { key ->
            AppDialog(
                title = "删除公钥",
                message = "确定删除以下软件包仓库公钥？\n${key.filename}",
                confirmLabel = "删除",
                confirmColor = colors.error,
                onConfirm = {
                    removeRepoKey(key)
                    confirmRepoKey = null
                },
                onDismiss = { confirmRepoKey = null }
            )
        }
    }
}

/** 密码强度（LuCI password.js 的三组正则同源）：返回 强/中/弱/更多字符，空密码为 null。 */
private fun passwordStrength(value: String): String? {
    if (value.isEmpty()) return null
    val strong = Regex("^(?=.{8,})(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*\\W).*$")
    val medium = Regex("^(?=.{7,})(((?=.*[A-Z])(?=.*[a-z]))|((?=.*[A-Z])(?=.*[0-9]))|((?=.*[a-z])(?=.*[0-9]))).*$")
    val enough = Regex("^(?=.{6,}).*$")
    return when {
        strong.matches(value) -> "强"
        medium.matches(value) -> "中"
        enough.matches(value) -> "弱"
        else -> "更多字符"
    }
}

@Composable
private fun AdmDescText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalAppColors.current.onSurfaceVariant
    )
}

@Composable
private fun AdmSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = LocalAppColors.current.onSurface
    )
}

/** 单个 Dropbear 实例卡片：选项与 LuCI dropbear.js 一致。 */
@Composable
private fun DropbearInstanceCard(
    inst: DropbearInstance,
    networks: List<String>,
    busy: Boolean,
    onSelect: (title: String, current: String, onPick: (String) -> Unit) -> Unit,
    onDelete: () -> Unit,
    onChange: ((DropbearInstance) -> DropbearInstance) -> Unit
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (inst.section.isBlank()) "新实例" else "实例 ${inst.section}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                "删除",
                style = MaterialTheme.typography.labelLarge,
                color = colors.error,
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy, onClick = onDelete)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        AdmSettingRow(
            label = "启用实例",
            description = "启用 SSH 服务实例",
            checked = inst.enabled,
            enabled = !busy
        ) { v -> onChange { it.copy(enabled = v) } }
        AdmSettingRow(
            label = "绑定接口",
            description = "切换接口绑定模式",
            checked = inst.directBind,
            enabled = !busy
        ) { v -> onChange { it.copy(directBind = v) } }
        if (inst.directBind) {
            AdmSelectRow(
                label = "接口",
                value = inst.directInterface.ifBlank { "全部接口" },
                description = "仅侦听给定接口，如未指定则侦听全部接口",
                enabled = !busy
            ) {
                onSelect("选择接口", inst.directInterface) { v ->
                    onChange { it.copy(directInterface = v) }
                }
            }
        } else {
            AdmSelectRow(
                label = "接口",
                value = inst.interface_.ifBlank { "全部接口" },
                description = "侦听给定接口上最多 10 个 IP，如未指定则为全部接口",
                enabled = !busy
            ) {
                onSelect("选择接口", inst.interface_) { v ->
                    onChange { it.copy(interface_ = v) }
                }
            }
        }
        AppTextField(
            value = inst.port,
            onValueChange = { v -> onChange { it.copy(port = v.filter { c -> c.isDigit() }.take(5)) } },
            singleLine = true,
            label = { Text("端口") },
            placeholder = { Text("22") },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        AdmSettingRow(
            label = "密码验证",
            description = "允许 SSH 密码验证",
            checked = inst.passwordAuth,
            enabled = !busy
        ) { v -> onChange { it.copy(passwordAuth = v) } }
        AdmSettingRow(
            label = "允许 root 用户凭密码登录",
            description = "允许 root 用户使用密码登录 SSH",
            checked = inst.rootPasswordAuth,
            enabled = !busy
        ) { v -> onChange { it.copy(rootPasswordAuth = v) } }
        AdmSettingRow(
            label = "网关端口",
            description = "允许远程主机连接到本地 SSH 转发端口",
            checked = inst.gatewayPorts,
            enabled = !busy
        ) { v -> onChange { it.copy(gatewayPorts = v) } }
    }
}

/** SSH 公钥行：类型/位数（曲线）/选项/缩略密钥，点击删除图标进入确认。 */
@Composable
private fun SshKeyRow(key: SshPublicKey, enabled: Boolean, onDelete: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                key.comment.ifBlank { "未命名密钥" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = colors.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        val meta = buildString {
            append(key.kind)
            append(", ")
            append(key.curve ?: key.bits?.let { "$it Bit" } ?: "未知")
            if (key.options.isNotEmpty()) {
                append(" / 选项: ")
                append(key.options.joinToString(", "))
            }
        }
        Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Text(
            key.displayKey,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/** 仓库公钥行：文件名 + 只读内容 + 删除（受保护键禁用删除，LuCI 同款）。 */
@Composable
private fun RepoKeyRow(key: RepoPublicKey, enabled: Boolean, onDelete: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    key.filename,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (key.protected) {
                    Text(
                        "受保护的系统密钥，不可删除",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Text(
                "删除",
                style = MaterialTheme.typography.labelLarge,
                color = if (key.protected) colors.onSurfaceVariant else colors.error,
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled && !key.protected, onClick = onDelete)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            key.content,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
}

@Composable
private fun AdmSettingRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChanged: (Boolean) -> Unit
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            AppSwitch(
                checked = checked,
                onCheckedChange = if (enabled) onChanged else null,
                modifier = Modifier.scale(0.75f)
            )
        }
        if (!description.isNullOrBlank()) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AdmSelectRow(
    label: String,
    value: String,
    description: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.outline, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(value, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("›", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
        }
        if (!description.isNullOrBlank()) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}
