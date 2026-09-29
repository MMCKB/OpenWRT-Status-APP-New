package com.mmckb.openwrtstatus.data.ssh

import android.util.Log

/**
 * Rust 核心抛出的 SSH 错误。消息在 Rust 侧已生成成人话（与 JSch 路径 readableError
 * 同一套文案），可直接展示。
 */
class RustSshException(message: String) : Exception(message)

/**
 * Rust 核心（libowrt_core.so，russh 实现）的 SSH 传输。
 *
 * 库加载失败（APK 未带上 .so、ABI 不匹配等）时 [isAvailable] 为 false，
 * [SshExec] 会整体走 JSch；单次调用失败由 [SshExec] 做回退，这里不重复处理。
 */
internal object RustSshTransport {

    private const val TAG = "RustSsh"
    private const val LIB_NAME = "owrt_core"

    private const val LOAD_NOT_TRIED = 0
    private const val LOAD_OK = 1
    private const val LOAD_FAILED = 2

    @Volatile
    private var loadState = LOAD_NOT_TRIED

    val isAvailable: Boolean
        get() {
            if (loadState == LOAD_NOT_TRIED) {
                synchronized(this) {
                    if (loadState == LOAD_NOT_TRIED) {
                        loadState = try {
                            System.loadLibrary(LIB_NAME)
                            LOAD_OK
                        } catch (t: Throwable) {
                            Log.w(TAG, "Rust 核心库加载失败，SSH 全部走 JSch：${t.message}")
                            LOAD_FAILED
                        }
                    }
                }
            }
            return loadState == LOAD_OK
        }

    /**
     * 阻塞的 JNI 调用（到命令结束才返回），调用方保证已在 Dispatchers.IO 上。
     * 失败抛 [RustSshException]；Kotlin 协程取消无法打断进行中的调用，
     * 最多阻塞 timeoutMs 后自然返回，行为可接受。
     */
    fun exec(config: SshConfig, command: String, timeoutMs: Int): String =
        sshExec(
            config.host,
            config.port,
            config.username,
            config.password,
            command,
            timeoutMs
        )

    private external fun sshExec(
        host: String,
        port: Int,
        username: String,
        password: String,
        command: String,
        timeoutMs: Int
    ): String
}
