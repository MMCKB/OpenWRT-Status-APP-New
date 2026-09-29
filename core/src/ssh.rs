//! SSH 传输核心：对接 Kotlin 侧 `SshExec`（一次性命令执行）。
//!
//! 语义与 JSch 版本逐条对齐：
//! - 每次调用独立建链：连接 → 认证 → exec → 收流 → 断开；
//! - 任意阶段超过 `timeout_ms` 无进展即报超时（连接超时与「无响应」分开提示）；
//! - 认证先试 password，被拒后自动走 keyboard-interactive（用同一密码作答），
//!   对应 JSch 的 `PreferredAuthentications=publickey,keyboard-interactive,password`
//!   与 `PasswordUserInfo` 的行为；
//! - 不校验主机指纹，对应 `StrictHostKeyChecking=no`；
//! - 只返回 stdout，stderr 与退出码一并带回供后续功能使用。

use std::sync::Arc;
use std::time::Duration;

use russh::client::{self, Handle, KeyboardInteractiveAuthResponse};
use russh::{ChannelMsg, Disconnect};

/// 单条命令输出上限（8 MiB），防止异常输出把内存吃满。
const MAX_OUTPUT_BYTES: usize = 8 * 1024 * 1024;

const MSG_CONNECT_TIMEOUT: &str = "SSH 连接超时：请确认路由器可达且已开启 SSH。";
const MSG_AUTH_FAILED: &str = "SSH 认证失败：请检查 SSH 用户名与密码。";

/// 与 Kotlin `SshConfig` 对齐的连接参数。
#[derive(Clone, Debug)]
pub struct SshConfig {
    pub host: String,
    pub port: u16,
    pub username: String,
    pub password: String,
}

/// 执行结果。Kotlin 侧目前只用 stdout，stderr/exit_status 留给后续功能。
pub struct ExecOutcome {
    pub stdout: String,
    pub stderr: String,
    pub exit_status: Option<u32>,
}

/// 错误消息直接用人话（与 Kotlin `readableError` 同一套文案），JNI 层原样抛给 Java。
#[derive(Debug, thiserror::Error)]
pub enum SshError {
    #[error("{0}")]
    Connect(String),
    #[error("{0}")]
    Auth(String),
    #[error("{0}")]
    Timeout(String),
    #[error("{0}")]
    Io(String),
    #[error("{0}")]
    Output(String),
}

/// 对齐 JSch `StrictHostKeyChecking=no`：不做主机指纹校验。
struct AcceptAllKeys;

impl client::Handler for AcceptAllKeys {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        _server_public_key: &russh::keys::PublicKeyOrCertificate,
    ) -> Result<bool, Self::Error> {
        Ok(true)
    }
}

/// 把 russh 的传输层错误映射成用户能看懂的提示，分类口径与 Kotlin `readableError` 一致。
fn map_error_message(msg: String) -> SshError {
    let lower = msg.to_ascii_lowercase();
    if lower.contains("timeout") {
        SshError::Timeout(format!("{MSG_CONNECT_TIMEOUT}（{msg}）"))
    } else if lower.contains("refused") {
        SshError::Connect(format!(
            "SSH 连接被拒绝：请确认路由器已开启 SSH 且端口正确。（{msg}）"
        ))
    } else if lower.contains("resolve")
        || lower.contains("name or service")
        || lower.contains("temporary failure")
        || lower.contains("host unknown")
    {
        SshError::Connect(format!("无法解析 SSH 主机地址：请检查主机是否填写正确。（{msg}）"))
    } else {
        SshError::Connect(format!("SSH 连接失败：{msg}"))
    }
}

fn map_transport_error(err: russh::Error) -> SshError {
    map_error_message(err.to_string())
}

/// password 被拒后的 keyboard-interactive 重试：所有 prompt 都用密码作答。
/// 部分 sshd 只接受 keyboard-interactive，JSch 版靠 `PasswordUserInfo` 支持这种场景。
async fn authenticate(
    session: &mut Handle<AcceptAllKeys>,
    config: &SshConfig,
    timeout: Duration,
) -> Result<(), SshError> {
    let phase = |msg: russh::Error| SshError::Auth(format!("{MSG_AUTH_FAILED}（{msg}）"));

    match tokio::time::timeout(
        timeout,
        session.authenticate_password(config.username.clone(), config.password.clone()),
    )
    .await
    {
        Err(_) => return Err(SshError::Timeout(MSG_CONNECT_TIMEOUT.to_string())),
        Ok(Ok(res)) if res.success() => return Ok(()),
        Ok(Ok(_)) => {}  // 密码被拒，尝试 keyboard-interactive
        Ok(Err(e)) => return Err(phase(e)),
    }

    let mut response = match tokio::time::timeout(
        timeout,
        session.authenticate_keyboard_interactive_start(config.username.clone(), None),
    )
    .await
    {
        Err(_) => return Err(SshError::Timeout(MSG_CONNECT_TIMEOUT.to_string())),
        Ok(Ok(res)) => res,
        Ok(Err(e)) => return Err(phase(e)),
    };

    // InfoRequest 每轮带若干 prompt，全部用密码作答；设上限防服务器恶意循环。
    for _ in 0..8 {
        response = match response {
            KeyboardInteractiveAuthResponse::Success => return Ok(()),
            KeyboardInteractiveAuthResponse::Failure { .. } => break,
            KeyboardInteractiveAuthResponse::InfoRequest { prompts, .. } => {
                let answers = prompts.iter().map(|_| config.password.clone()).collect();
                match tokio::time::timeout(
                    timeout,
                    session.authenticate_keyboard_interactive_respond(answers),
                )
                .await
                {
                    Err(_) => return Err(SshError::Timeout(MSG_CONNECT_TIMEOUT.to_string())),
                    Ok(Ok(res)) => res,
                    Ok(Err(e)) => return Err(phase(e)),
                }
            }
        };
    }

    Err(SshError::Auth(MSG_AUTH_FAILED.to_string()))
}

pub async fn ssh_exec(
    config: SshConfig,
    command: String,
    timeout_ms: u64,
) -> Result<ExecOutcome, SshError> {
    let timeout = Duration::from_millis(timeout_ms);
    let idle_timeout = || {
        SshError::Timeout(format!(
            "SSH {} 秒无响应，连接可能已中断。",
            timeout_ms / 1000
        ))
    };
    let disconnect = |session: &Handle<AcceptAllKeys>| {
        // 断开失败没有补救意义，静默忽略。
        let _ = session.disconnect(Disconnect::ByApplication, "", "en");
    };

    let client_config = Arc::new(client::Config {
        nodelay: true,
        ..client::Config::default()
    });

    // 1. 建链
    let mut session: Handle<AcceptAllKeys> = tokio::time::timeout(
        timeout,
        client::connect(
            client_config,
            (config.host.as_str(), config.port),
            AcceptAllKeys,
        ),
    )
    .await
    .map_err(|_| SshError::Timeout(MSG_CONNECT_TIMEOUT.to_string()))?
    .map_err(map_transport_error)?;

    // 2. 认证
    if let Err(e) = authenticate(&mut session, &config, timeout).await {
        disconnect(&session);
        return Err(e);
    }

    // 3. 打开 exec 通道
    let mut channel = match tokio::time::timeout(timeout, session.channel_open_session()).await {
        Err(_) => {
            disconnect(&session);
            return Err(SshError::Timeout(MSG_CONNECT_TIMEOUT.to_string()));
        }
        Ok(Err(e)) => {
            disconnect(&session);
            return Err(SshError::Io(format!("SSH 连接失败：{e}")));
        }
        Ok(Ok(channel)) => channel,
    };

    if let Err(e) = channel.exec(true, command).await {
        disconnect(&session);
        return Err(SshError::Io(format!("SSH 命令执行失败：{e}")));
    }
    // 对齐 JSch setInputStream(null)：立即关闭 stdin，避免 cat 等命令等输入挂住。
    let _ = channel.eof().await;

    // 4. 收流：无数据超过 timeout 即报错（半死连接保护，语义同 JSch 版的轮询上限）
    let mut stdout: Vec<u8> = Vec::with_capacity(4096);
    let mut stderr: Vec<u8> = Vec::new();
    let mut exit_status: Option<u32> = None;

    loop {
        match tokio::time::timeout(timeout, channel.wait()).await {
            Err(_) => {
                disconnect(&session);
                return Err(idle_timeout());
            }
            Ok(None) => break, // 通道已关闭
            Ok(Some(ChannelMsg::Data { data })) => {
                if stdout.len().saturating_add(data.len()) > MAX_OUTPUT_BYTES {
                    disconnect(&session);
                    return Err(SshError::Output(
                        "SSH 命令输出超过 8 MiB 上限，已中止。".to_string(),
                    ));
                }
                stdout.extend_from_slice(&data[..]);
            }
            Ok(Some(ChannelMsg::ExtendedData { data, .. })) => {
                if stderr.len().saturating_add(data.len()) <= MAX_OUTPUT_BYTES {
                    stderr.extend_from_slice(&data[..]);
                }
            }
            Ok(Some(ChannelMsg::ExitStatus { exit_status: code })) => exit_status = Some(code),
            Ok(Some(_)) => {}
        }
    }

    disconnect(&session);

    Ok(ExecOutcome {
        stdout: String::from_utf8_lossy(&stdout).into_owned(),
        stderr: String::from_utf8_lossy(&stderr).into_owned(),
        exit_status,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn timeout_message_maps_to_timeout() {
        assert!(matches!(
            map_error_message("Connection timed out".to_string()),
            SshError::Timeout(_)
        ));
    }

    #[test]
    fn refused_message_maps_to_connect() {
        assert!(matches!(
            map_error_message("Connection refused (os error 111)".to_string()),
            SshError::Connect(_)
        ));
    }

    #[test]
    fn dns_message_maps_to_unknown_host() {
        assert!(matches!(
            map_error_message("failed to lookup address information: Name or service not known"
                .to_string()),
            SshError::Connect(_)
        ));
    }

    #[test]
    fn other_message_maps_to_generic_connect() {
        assert!(matches!(
            map_error_message("protocol mismatch".to_string()),
            SshError::Connect(m) if m.contains("protocol mismatch")
        ));
    }
}
