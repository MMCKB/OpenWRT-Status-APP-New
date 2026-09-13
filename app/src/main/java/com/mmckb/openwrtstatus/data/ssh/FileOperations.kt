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
    private const val PROGRESS_INTERVAL_MS = 100L

    /** Lists the contents of [path]; dotfiles included, `.`/`..` excluded. */
    suspend fun list(config: SshConfig, path: String): List<FileEntry> {
        val output = exec(config, "ls -lA ${quote(shellSafe(path))}")
        return output.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("total ") }
            .mapNotNull { parseLsLine(it) }
            .toList()
            .sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** Reads a file as text for the built-in editor (no size cap; NUL bytes mark binary). */
    suspend fun readText(config: SshConfig, path: String): String =
        exec(config, "cat ${quote(shellSafe(path))}", timeoutMs = TRANSFER_TIMEOUT_MS)

    /** Downloads a file with `cat`, returning its raw bytes (SSH channels are 8-bit clean). */
    suspend fun download(
        config: SshConfig,
        path: String,
        onProgress: (Long) -> Unit = {}
    ): ByteArray =
        execBytes(
            config,
            "cat ${quote(shellSafe(path))}",
            timeoutMs = TRANSFER_TIMEOUT_MS,
            onProgress = onProgress
        )

    /**
     * Uploads [data] to [path]: raw bytes are piped into one exec channel running
     * `cat > tmp`, then the temp file is moved into place. `cat` exists in every
     * BusyBox build, unlike base64.
     */
    suspend fun upload(
        config: SshConfig,
        path: String,
        data: ByteArray,
        onProgress: (Long) -> Unit = {}
    ) {
        val tmp = "$path.upload.tmp"
        run(
            config,
            command = "cat > ${quote(shellSafe(tmp))}",
            stdin = data,
            timeoutMs = TRANSFER_TIMEOUT_MS,
            onProgress = onProgress
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
        timeoutMs: Int = 15_000,
        onProgress: (Long) -> Unit = {}
    ) {
        val result = execInternal(config, command, stdin, timeoutMs, onProgress)
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
        val result = execInternal(config, command, stdin, timeoutMs)
        if (result.exitStatus != 0 && result.stdout.isEmpty()) {
            throw SshFileException(result.detail().ifBlank { "远端命令执行失败（退出码 ${result.exitStatus}）。" })
        }
        return String(result.stdout, Charsets.UTF_8)
    }

    /** Runs a command and returns raw stdout bytes (no size cap). */
    private suspend fun execBytes(
        config: SshConfig,
        command: String,
        timeoutMs: Int,
        onProgress: (Long) -> Unit = {}
    ): ByteArray {
        val result = execInternal(config, command, null, timeoutMs, onProgress)
        if (result.exitStatus != 0) {
            throw SshFileException(result.detail().ifBlank { "远端命令执行失败（退出码 ${result.exitStatus}）。" })
        }
        return result.stdout
    }

    /** Copies a file or directory into [destDir], keeping its basename. */
    suspend fun copy(config: SshConfig, from: String, destDir: String) {
        run(config, "cp -R ${quote(shellSafe(from))} ${quote(shellSafe(destDir))}")
    }

    /**
     * Searches [root] recursively for names containing [query]; returns (absolutePath, isDir).
     * Two `find` passes (dirs/files) keep the result type without a stat per hit.
     */
    suspend fun find(
        config: SshConfig,
        root: String,
        query: String,
        limit: Int = 200
    ): List<Pair<String, Boolean>> {
        // Escape glob metacharacters so user input stays a literal substring.
        val glob = "*" + query.replace(Regex("[*?\\[\\\\]")) { "\\${it.value}" } + "*"
        val dirs = exec(
            config,
            "find ${quote(shellSafe(root))} -type d -name ${quote(glob)} -print 2>/dev/null | head -n $limit"
        ).lines().filter { it.isNotBlank() && it != root }
        val files = exec(
            config,
            "find ${quote(shellSafe(root))} -type f -name ${quote(glob)} -print 2>/dev/null | head -n $limit"
        ).lines().filter { it.isNotBlank() }
        return (dirs.map { it to true } + files.map { it to false }).take(limit)
    }

    /** Lists archive contents via BusyBox unzip/tar (zip, tar, tar.gz, tgz, tar.bz2, tar.xz). */
    suspend fun listArchive(config: SshConfig, path: String): List<FileEntry> {
        val isZip = path.lowercase().endsWith(".zip")
        val command =
            if (isZip) "unzip -l ${quote(shellSafe(path))}" else "tar -tvf ${quote(shellSafe(path))}"
        val output = try {
            exec(config, command, timeoutMs = TRANSFER_TIMEOUT_MS)
        } catch (e: SshFileException) {
            throw SshFileException("无法读取压缩包内容（路由器可能缺少 unzip/tar 命令）：${e.message}")
        }
        val parsed = if (isZip) parseUnzipList(output) else parseTarList(output)
        if (parsed.isEmpty()) throw SshFileException("压缩包为空或无法解析其列表。")
        return parsed.sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    private class ExecResult(val stdout: ByteArray, val stderr: String, val exitStatus: Int) {
        fun detail(): String = stderr.trim().lineSequence().firstOrNull().orEmpty()
    }

    private suspend fun execInternal(
        config: SshConfig,
        command: String,
        stdin: ByteArray?,
        timeoutMs: Int,
        onProgress: (Long) -> Unit = {}
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
            // deadlocks against a full pipe window (large uploads). Progress is
            // reported per 32KB chunk, throttled to ~10 Hz.
            val stdinDone = CountDownLatch(1)
            if (stdin != null) {
                thread {
                    try {
                        var written = 0
                        var lastAt = 0L
                        while (written < stdin.size) {
                            val len = minOf(32 * 1024, stdin.size - written)
                            stdinPipe.write(stdin, written, len)
                            written += len
                            val now = System.currentTimeMillis()
                            if (now - lastAt >= PROGRESS_INTERVAL_MS) {
                                lastAt = now
                                runCatching { onProgress(written.toLong()) }
                            }
                        }
                        stdinPipe.flush()
                        runCatching { onProgress(stdin.size.toLong()) }
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
            var lastProgressAt = 0L
            while (true) {
                var progressed = false
                while (stdout.available() > 0) {
                    val count = stdout.read(chunk, 0, minOf(chunk.size, stdout.available()))
                    if (count < 0) break
                    out.write(chunk, 0, count)
                    val now = System.currentTimeMillis()
                    if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                        lastProgressAt = now
                        runCatching { onProgress(out.size().toLong()) }
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

    /** `unzip -l` row: `12345  2024-01-01 10:00   name` (BusyBox and GNU share the shape). */
    private fun parseUnzipList(output: String): List<FileEntry> {
        val regex = Regex("^(\\d+)\\s+\\d{2,4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}\\s+(.+)$")
        return output.lineSequence().mapNotNull { line ->
            val m = regex.find(line.trim()) ?: return@mapNotNull null
            val rawName = m.groupValues[2]
            val isDir = rawName.endsWith("/")
            FileEntry(
                name = rawName.removeSuffix("/"),
                isDir = isDir,
                size = if (isDir) 0L else m.groupValues[1].toLongOrNull() ?: 0L
            )
        }.filter { it.name.isNotEmpty() }.toList()
    }

    /** `tar -tvf` row: `-rw-r--r-- root/root 1234 2024-01-01 10:00 name` (busybox/GNU alike). */
    private fun parseTarList(output: String): List<FileEntry> {
        val regex = Regex("^([dbc-lps@-][rwxst-]{9})\\s+\\S+\\s+(\\d+)\\s+\\S+\\s+\\S+\\s+(.+)$")
        return output.lineSequence().mapNotNull { line ->
            val m = regex.find(line) ?: return@mapNotNull null
            val rawName = m.groupValues[3]
            val isDir = rawName.endsWith("/") || m.groupValues[1].startsWith("d")
            FileEntry(
                name = rawName.removeSuffix("/"),
                isDir = isDir,
                size = if (isDir) 0L else m.groupValues[2].toLongOrNull() ?: 0L
            )
        }.filter { it.name.isNotEmpty() && it.name != "." }.toList()
    }
}
