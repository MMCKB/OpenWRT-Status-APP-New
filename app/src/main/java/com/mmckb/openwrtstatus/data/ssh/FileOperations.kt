package com.mmckb.openwrtstatus.data.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.mmckb.openwrtstatus.data.model.SshConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Properties
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** One row in the remote file browser. */
data class FileEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long
)

/** Raised when a remote file operation fails; [message] is user-facing Chinese text. */
class SshFileException(message: String) : IOException(message)

/**
 * File management over plain SSH exec channels, so it works with stock OpenWrt
 * (dropbear) where no SFTP server is installed. Directory listings are parsed
 * from `ls -lA`; transfers go through BusyBox `base64`/`base64 -d` on stdin/stdout.
 */
object SshFiles {

    private const val TRANSFER_TIMEOUT_MS = 120_000
    private const val MAX_TRANSFER_BYTES = 64L * 1024 * 1024

    /** Lists the contents of [path]; dotfiles included, `.`/`..` excluded. */
    suspend fun list(config: SshConfig, path: String): List<FileEntry> {
        val output = exec(config, "ls -lA ${quote(shellSafe(path))}")
        return output.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("total ") }
            .mapNotNull { parseLsLine(it) }
            .toList()
            .sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** Reads the first [maxBytes] bytes of a file as text for preview. */
    suspend fun readText(config: SshConfig, path: String, maxBytes: Int = 64 * 1024): String =
        exec(
            config,
            "head -c $maxBytes ${quote(shellSafe(path))}",
            timeoutMs = TRANSFER_TIMEOUT_MS
        )

    /** Downloads a file with `cat`, returning its raw bytes (SSH channels are 8-bit clean). */
    suspend fun download(config: SshConfig, path: String): ByteArray =
        execBytes(
            config,
            "cat ${quote(shellSafe(path))}",
            maxBytes = MAX_TRANSFER_BYTES,
            timeoutMs = TRANSFER_TIMEOUT_MS
        )

    /**
     * Uploads [data] to [path]: raw bytes are piped into one exec channel running
     * `cat > tmp`, then the temp file is moved into place. `cat` exists in every
     * BusyBox build, unlike base64.
     */
    suspend fun upload(config: SshConfig, path: String, data: ByteArray) {
        val tmp = "$path.upload.tmp"
        run(
            config,
            command = "cat > ${quote(shellSafe(tmp))}",
            stdin = data,
            timeoutMs = TRANSFER_TIMEOUT_MS
        )
        run(config, "mv -f ${quote(shellSafe(tmp))} ${quote(shellSafe(path))}", timeoutMs = TRANSFER_TIMEOUT_MS)
    }

    suspend fun mkdir(config: SshConfig, path: String) {
        run(config, "mkdir ${quote(shellSafe(path))}")
    }

    suspend fun rename(config: SshConfig, from: String, to: String) {
        run(config, "mv ${quote(shellSafe(from))} ${quote(shellSafe(to))}")
    }

    suspend fun delete(config: SshConfig, path: String, isDir: Boolean) {
        run(config, if (isDir) "rm -rf ${quote(shellSafe(path))}" else "rm -f ${quote(shellSafe(path))}")
    }

    /** Runs a command and fails when the remote exit status is non-zero. */
    private suspend fun run(
        config: SshConfig,
        command: String,
        stdin: ByteArray? = null,
        timeoutMs: Int = 15_000
    ) {
        val result = execInternal(config, command, stdin, Long.MAX_VALUE, timeoutMs)
        if (result.exitStatus != 0) {
            throw SshFileException(result.detail().ifBlank { "远端命令执行失败（退出码 ${result.exitStatus}）。" })
        }
    }

    /** Runs a command and returns stdout as text; failures carry stderr detail when present. */
    private suspend fun exec(
        config: SshConfig,
        command: String,
        stdin: ByteArray? = null,
        timeoutMs: Int = 15_000
    ): String {
        val result = execInternal(config, command, stdin, Long.MAX_VALUE, timeoutMs)
        if (result.exitStatus != 0 && result.stdout.isEmpty()) {
            throw SshFileException(result.detail().ifBlank { "远端命令执行失败（退出码 ${result.exitStatus}）。" })
        }
        return String(result.stdout, Charsets.UTF_8)
    }

    /** Runs a command and returns raw stdout bytes, refusing output beyond [maxBytes]. */
    private suspend fun execBytes(
        config: SshConfig,
        command: String,
        maxBytes: Long,
        timeoutMs: Int
    ): ByteArray {
        val result = execInternal(config, command, null, maxBytes, timeoutMs)
        if (result.exitStatus != 0) {
            throw SshFileException(result.detail().ifBlank { "远端命令执行失败（退出码 ${result.exitStatus}）。" })
        }
        return result.stdout
    }

    private class ExecResult(val stdout: ByteArray, val stderr: String, val exitStatus: Int) {
        fun detail(): String = stderr.trim().lineSequence().firstOrNull().orEmpty()
    }

    private fun formatLimit(bytes: Long): String = "${bytes / (1024 * 1024)} MB"

    private suspend fun execInternal(
        config: SshConfig,
        command: String,
        stdin: ByteArray?,
        maxBytes: Long,
        timeoutMs: Int
    ): ExecResult = withContext(Dispatchers.IO) {
        val session = try {
            openSession(config, timeoutMs)
        } catch (e: JSchException) {
            throw readableSshError(e, config)
        }
        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.setInputStream(null)
            val stdout = channel.inputStream
            val stderr = channel.errStream
            val stdinPipe = channel.outputStream
            channel.connect(timeoutMs)

            // Feed stdin from a side thread so the stdout/stderr drain below never
            // deadlocks against a full pipe window (large uploads).
            val stdinDone = CountDownLatch(1)
            if (stdin != null) {
                thread {
                    try {
                        stdinPipe.write(stdin)
                        stdinPipe.flush()
                    } catch (_: Exception) {
                    } finally {
                        try { stdinPipe.close() } catch (_: Exception) {}
                        stdinDone.countDown()
                    }
                }
            } else {
                stdinPipe.close()
                stdinDone.countDown()
            }

            val out = java.io.ByteArrayOutputStream()
            val err = StringBuilder()
            val chunk = ByteArray(8192)
            while (true) {
                var progressed = false
                while (stdout.available() > 0) {
                    val count = stdout.read(chunk, 0, minOf(chunk.size, stdout.available()))
                    if (count < 0) break
                    out.write(chunk, 0, count)
                    if (out.size() > maxBytes) {
                        throw SshFileException("文件超过 ${formatLimit(maxBytes)}，传输已中止。")
                    }
                    progressed = true
                }
                while (stderr.available() > 0) {
                    val count = stderr.read(chunk, 0, minOf(chunk.size, stderr.available()))
                    if (count < 0) break
                    err.append(String(chunk, 0, count, Charsets.UTF_8))
                    progressed = true
                }
                if (channel.isClosed && stdout.available() == 0 && stderr.available() == 0) break
                if (!progressed) Thread.sleep(30)
            }
            stdinDone.await()
            ExecResult(out.toByteArray(), err.toString(), channel.exitStatus)
        } catch (e: JSchException) {
            throw readableSshError(e, config)
        } catch (e: IOException) {
            // The size-cap check above aborts with a SshFileException; keep it intact.
            if (e is SshFileException) throw e
            throw SshFileException("SSH 传输中断：${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { session.disconnect() } catch (_: Exception) {}
        }
    }

    /** Creates and connects a session with password / keyboard-interactive auth. */
    private fun openSession(config: SshConfig, timeoutMs: Int): Session {
        val jsch = JSch()
        val session = jsch.getSession(config.username, config.host, config.port)
        session.setPassword(config.password)
        session.setConfig(Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", "publickey,keyboard-interactive,password")
        })
        session.userInfo = PasswordUserInfo(config.password)
        session.timeout = timeoutMs
        session.connect(timeoutMs)
        return session
    }

    private fun readableSshError(e: JSchException, config: SshConfig): SshFileException {
        val message = e.message.orEmpty()
        val target = "${config.username}@${config.host}:${config.port}"
        return SshFileException(
            when {
                message.contains("Auth fail", ignoreCase = true) ||
                    message.contains("auth cancel", ignoreCase = true) ->
                    "SSH 认证失败（$target）：请到设备编辑页核对路由器密码 / SSH 密码。" +
                        if (config.password.isBlank()) "当前 SSH 密码为空。" else ""
                message.contains("UnknownHost", ignoreCase = true) ->
                    "无法解析 SSH 主机地址：请检查主机是否填写正确。"
                message.contains("timeout", ignoreCase = true) ->
                    "SSH 连接超时：请确认路由器可达且已开启 SSH。"
                message.contains("refused", ignoreCase = true) ->
                    "SSH 连接被拒绝：请确认路由器已开启 SSH 且端口正确。"
                else -> "SSH 连接失败：${message.ifBlank { e.javaClass.simpleName }}"
            }
        )
    }

    /** Single-quote shell escaping: `'` becomes `'\''`. */
    private fun quote(path: String): String = "'${path.replace("'", "'\\''")}'"

    /** Applets like `ls` misparse leading-dash names as options. */
    private fun shellSafe(path: String): String = if (path.startsWith("-")) "./$path" else path

    /**
     * Parses one `ls -l` line (BusyBox and coreutils share the shape):
     * perms links owner group size month day time/year name…
     * The name is everything after the 8th token, so spaces in filenames survive.
     */
    private fun parseLsLine(line: String): FileEntry? {
        val type = line.firstOrNull() ?: return null
        if (type !in "dl-bcps") return null

        val name = nameAfterTokens(line, 8) ?: nameAfterTokens(line, 7) ?: return null
        if (name.isEmpty() || name == "." || name == "..") return null
        val displayName = name.substringBefore(" -> ")
        val size = tokenAt(line, 4)?.toLongOrNull() ?: tokenAt(line, 3)?.toLongOrNull() ?: 0L
        return FileEntry(
            name = displayName,
            isDir = type == 'd',
            size = if (type == 'd') 0L else size
        )
    }

    private fun nameAfterTokens(line: String, count: Int): String? {
        var i = 0
        repeat(count) {
            while (i < line.length && line[i] == ' ') i++
            while (i < line.length && line[i] != ' ') i++
        }
        while (i < line.length && line[i] == ' ') i++
        return if (i < line.length) line.substring(i) else null
    }

    private fun tokenAt(line: String, index: Int): String? {
        val tokens = line.split(' ').filter { it.isNotEmpty() }
        return tokens.getOrNull(index)
    }
}
