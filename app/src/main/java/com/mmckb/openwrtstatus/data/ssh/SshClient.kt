package com.mmckb.openwrtstatus.data.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.mmckb.openwrtstatus.data.model.SshConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

private const val CONNECT_TIMEOUT_MS = 10_000
private const val MAX_OUTPUT_CHARS = 24_000

/**
 * Answers password / keyboard-interactive auth prompts with the stored password.
 * These are background operations, so nothing ever pops UI; some sshd builds only
 * authenticate via keyboard-interactive, which JSch cancels without a callback.
 */
internal class PasswordUserInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
    override fun getPassword(): String = password
    override fun getPassphrase(): String = password
    override fun promptYesNo(str: String?): Boolean = false
    override fun showMessage(message: String?) {}
    override fun promptPassword(message: String?): Boolean = true
    override fun promptPassphrase(message: String?): Boolean = true
    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompts: Array<out String>?,
        echo: BooleanArray?
    ): Array<String>? = prompts?.map { password }?.toTypedArray()
}

/**
 * Interactive SSH shell backed by JSch (`com.github.mwiede:jsch`, the maintained fork that
 * keeps the `com.jcraft.jsch` API).
 *
 * A PTY shell channel is opened once and kept alive; output is accumulated into [output]
 * and commands are written through [send].
 */
class SshTerminal {

    sealed interface State {
        data object Disconnected : State
        data object Connecting : State
        data object Connected : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buffer = StringBuilder()

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state

    @Volatile private var session: Session? = null
    @Volatile private var channel: ChannelShell? = null
    @Volatile private var writer: OutputStream? = null

    suspend fun connect(config: SshConfig) = withContext(Dispatchers.IO) {
        _state.value = State.Connecting
        buffer.clear()
        _output.value = ""
        try {
            val jsch = JSch()
            val newSession = jsch.getSession(config.username, config.host, config.port)
            newSession.setPassword(config.password)
            newSession.setConfig(Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("PreferredAuthentications", "publickey,keyboard-interactive,password")
            })
            newSession.userInfo = PasswordUserInfo(config.password)
            newSession.timeout = CONNECT_TIMEOUT_MS
            newSession.connect(CONNECT_TIMEOUT_MS)

            val shell = newSession.openChannel("shell") as ChannelShell
            shell.setPty(true)
            shell.connect(CONNECT_TIMEOUT_MS)

            session = newSession
            channel = shell
            writer = shell.outputStream
            _state.value = State.Connected
            append("已连接 ${config.username}@${config.host}:${config.port}\n")
            startReader(shell.inputStream)
        } catch (e: Exception) {
            closeQuietly()
            _state.value = State.Failed(readableError(e))
        }
    }

    fun send(command: String) {
        val target = writer ?: return
        scope.launch {
            try {
                target.write((command + "\n").toByteArray(Charsets.UTF_8))
                target.flush()
            } catch (e: Exception) {
                _state.value = State.Failed("发送命令失败：${e.message}")
            }
        }
    }

    fun disconnect() {
        closeQuietly()
        _state.value = State.Disconnected
    }

    fun close() {
        closeQuietly()
        scope.cancel()
    }

    /** Releases the channel/session but deliberately does not touch [state]. */
    private fun closeQuietly() {
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        channel = null
        session = null
        writer = null
    }

    private fun startReader(input: InputStream) {
        scope.launch {
            val chunk = ByteArray(4096)
            try {
                while (isActive) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (count > 0) append(String(chunk, 0, count, Charsets.UTF_8))
                }
            } catch (_: Exception) {
                // Stream closes when the session ends; nothing to recover.
            } finally {
                if (_state.value is State.Connected) _state.value = State.Disconnected
            }
        }
    }

    private fun append(text: String) {
        synchronized(buffer) {
            buffer.append(text)
            val overflow = buffer.length - MAX_OUTPUT_CHARS
            if (overflow > 0) buffer.delete(0, overflow)
        }
        _output.value = buffer.toString()
    }

    private fun readableError(e: Exception): String {
        val message = e.message.orEmpty()
        return when {
            e is JSchException && message.contains("Auth", ignoreCase = true) ->
                "SSH 认证失败：请检查 SSH 用户名与密码。"
            message.contains("UnknownHost", ignoreCase = true) ||
                message.contains("unknown host", ignoreCase = true) ->
                "无法解析 SSH 主机地址：请检查主机是否填写正确。"
            message.contains("timeout", ignoreCase = true) ->
                "SSH 连接超时：请确认路由器可达且已开启 SSH。"
            message.contains("refused", ignoreCase = true) ->
                "SSH 连接被拒绝：请确认路由器已开启 SSH 且端口正确。"
            else -> "SSH 连接失败：${message.ifBlank { e.javaClass.simpleName }}"
        }
    }
}

/**
 * Runs a single command over SSH and returns its stdout.
 *
 * Used for one-shot reads such as `/tmp/dhcp.leases`.
 */
object SshExec {

    suspend fun run(config: SshConfig, command: String, timeoutMs: Int = 10_000): String =
        withContext(Dispatchers.IO) {
            val jsch = JSch()
            val session = jsch.getSession(config.username, config.host, config.port)
            try {
                session.setPassword(config.password)
                session.setConfig(Properties().apply {
                    put("StrictHostKeyChecking", "no")
                    put("PreferredAuthentications", "publickey,keyboard-interactive,password")
                })
                session.userInfo = PasswordUserInfo(config.password)
                session.timeout = timeoutMs
                session.connect(timeoutMs)

                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand(command)
                channel.setInputStream(null)
                val stdout = channel.inputStream
                channel.connect(timeoutMs)

                val result = StringBuilder()
                val chunk = ByteArray(4096)
                while (true) {
                    while (stdout.available() > 0) {
                        val count = stdout.read(chunk, 0, minOf(chunk.size, stdout.available()))
                        if (count < 0) break
                        result.append(String(chunk, 0, count, Charsets.UTF_8))
                    }
                    if (channel.isClosed) {
                        if (stdout.available() > 0) continue
                        break
                    }
                    Thread.sleep(40)
                }
                channel.disconnect()
                result.toString()
            } finally {
                try { session.disconnect() } catch (_: Exception) {}
            }
        }
}
