package com.mmckb.openwrtstatus.data.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
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

/**
 * File management over plain SSH exec channels, so it works with stock OpenWrt
 * (dropbear) where no SFTP server is installed. Directory listings are parsed
 * from `ls -lA`; transfers go through BusyBox `base64`/`base64 -d` on stdin/stdout.
 */
object SshFiles {

    private const val TRANSFER_TIMEOUT_MS = 120_000

    class SshFileException(message: String) : IOException(message)

    /** Lists the contents of [path]; dotfiles included, `.`/`..` excluded. */
    suspend fun list(config: SshConfig, path: String): List<FileEntry> {
        val output = exec(config, "ls -lA ${quote(shellSafe(path))}")
        return output.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("total ") }
            .mapNotNull { parseLsLine(it) }
            .sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** Reads the first [maxBytes] bytes of a file as text for preview. */
    suspend fun readText(config: SshConfig, path: String, maxBytes: Int = 64 * 1024): String =
        exec(
            config,
            "head -c $maxBytes ${quote(shellSafe(path))}",
            timeoutMs = TRANSFER_TIMEOUT_MS
        )

    /** Downloads a file through `base64`, returning its raw bytes. */
    suspend fun download(config: SshConfig, path: String): ByteArray {
        val encoded = exec(
            config,
            "base64 ${quote(shellSafe(path))}",
            timeoutMs = TRANSFER_TIMEOUT_MS
        )
        return try {
            java.util.Base64.getMimeDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw SshFileException("下载失败：远端返回了无效数据（可能缺少 base64 命令或文件不可读）。")
        }
    }

    /**
     * Uploads [data] to [path]: base64 bytes are piped into one exec channel running
     * `base64 -d > tmp`, then the temp file is moved into place.
     */
    suspend fun upload(config: SshConfig, path: String, data: ByteArray) {
        val encoded = java.util.Base64.getEncoder().encodeToString(data)
        val tmp = "$path.upload.tmp"
        run(
            config,
            command = "base64 -d > ${quote(shellSafe(tmp))}",
            stdin = encoded.toByteArray(Charsets.US_ASCII),
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
        val result = execInternal(config, command, stdin, timeoutMs)
        if (result.exitStatus != 0) {
            val detail = result.stderr.trim().lineSequence().firstOrNull().orEmpty()
            throw SshFileException(detail.ifBlank { "远端命令执行失败（退出码 ${result.exitStatus}）。" })
        }
    }

    /** Runs a command and returns stdout; failures carry stderr detail when present. */
    private suspend fun exec(
        config: SshConfig,
        command: String,
        stdin: ByteArray? = null,
        timeoutMs: Int = 15_000
    ): String {
        val result = execInternal(config, command, stdin, timeoutMs)
        if (result.exitStatus != 0 && result.stdout.isBlank()) {
            val detail = result.stderr.trim().lineSequence().firstOrNull().orEmpty()
            throw SshFileException(detail.ifBlank { "远端命令执行失败（退出码 ${result.exitStatus}）。" })
        }
        return result.stdout
    }

    private class ExecResult(val stdout: String, val stderr: String, val exitStatus: Int)

    private suspend fun execInternal(
        config: SshConfig,
        command: String,
        stdin: ByteArray?,
        timeoutMs: Int
    ): ExecResult = withContext(Dispatchers.IO) {
        val jsch = JSch()
        val session = jsch.getSession(config.username, config.host, config.port)
        try {
            session.setPassword(config.password)
            session.setConfig(Properties().apply { put("StrictHostKeyChecking", "no") })
            session.timeout = timeoutMs
            session.connect(timeoutMs)

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

            val out = StringBuilder()
            val err = StringBuilder()
            val chunk = ByteArray(8192)
            while (true) {
                var progressed = false
                while (stdout.available() > 0) {
                    val count = stdout.read(chunk, 0, minOf(chunk.size, stdout.available()))
                    if (count < 0) break
                    out.append(String(chunk, 0, count, Charsets.UTF_8))
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
            ExecResult(out.toString(), err.toString(), channel.exitStatus)
        } finally {
            try { session.disconnect() } catch (_: Exception) {}
        }
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
