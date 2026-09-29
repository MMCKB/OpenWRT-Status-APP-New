//! JNI 入口：把 [`ssh::ssh_exec`] 暴露为 Kotlin 的 `RustSsh.sshExec`。
//!
//! Kotlin 侧声明（com.mmckb.openwrtstatus.data.ssh.RustSsh）：
//! ```kotlin
//! private external fun sshExec(
//!     host: String, port: Int, username: String,
//!     password: String, command: String, timeoutMs: Int
//! ): String
//! ```
//! 成功返回 stdout；失败向 Java 抛 `RustSshException`，消息可直接展示。

mod ssh;

use std::sync::OnceLock;

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use tokio::runtime::Runtime;

use crate::ssh::{ssh_exec, SshConfig};

/// 常驻 tokio 运行时。App 全程只建一次，后续 sshExec 复用。
static RUNTIME: OnceLock<Runtime> = OnceLock::new();

fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("owrt_core: build tokio runtime")
    })
}

fn java_string(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value)
        .map(|s| s.into())
        .map_err(|e| format!("JNI 字符串读取失败：{e}"))
}

/// # Safety
/// 由 JNI 按 Kotlin `RustSsh` 的 native 声明调用，参数由 JVM 保证非空。
#[no_mangle]
pub extern "system" fn Java_com_mmckb_openwrtstatus_data_ssh_RustSsh_sshExec<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    host: JString<'local>,
    port: jint,
    username: JString<'local>,
    password: JString<'local>,
    command: JString<'local>,
    timeout_ms: jint,
) -> jstring {
    // 阻塞到命令结束；调用方（SshExec）已在 IO 线程池上，与 JSch 版一致。
    let outcome = (|| {
        let host = java_string(&mut env, &host)?;
        let username = java_string(&mut env, &username)?;
        let password = java_string(&mut env, &password)?;
        let command = java_string(&mut env, &command)?;
        if !(1..=65535).contains(&port) {
            return Err("SSH 端口不合法。".to_string());
        }
        if timeout_ms <= 0 {
            return Err("SSH 超时参数不合法。".to_string());
        }
        let config = SshConfig {
            host,
            port: port as u16,
            username,
            password,
        };
        runtime()
            .block_on(ssh_exec(config, command, timeout_ms as u64))
            .map(|outcome| outcome.stdout)
            .map_err(|e| e.to_string())
    })();

    match outcome {
        Ok(stdout) => match env.new_string(stdout) {
            Ok(js) => js.into_raw(),
            Err(e) => {
                let _ = env.throw_new("java/lang/RuntimeException", format!("构造返回值失败：{e}"));
                std::ptr::null_mut()
            }
        },
        Err(message) => {
            let _ = env.throw_new(
                "com/mmckb/openwrtstatus/data/ssh/RustSshException",
                message,
            );
            std::ptr::null_mut()
        }
    }
}
