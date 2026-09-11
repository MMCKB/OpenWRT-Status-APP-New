# OpenWrt 状态 (OpenWrt Status)

一个用于查看与管理 **OpenWrt 路由器** 的 Android 应用，使用 **Kotlin + Jetpack Compose** 实现。
提供在线状态、系统资源、网络流量、接入设备、实时监控与 SSH 远程终端，并配套 **GitHub Actions**
自动构建签名 APK。

## 功能

- **概览**：主机名、型号、固件、运行时间、在线状态与最近更新时间。
- **系统资源**：1/5/15 分钟平均负载、内存与交换分区占用。
- **网络流量**：各网络接口（`lan` / `wan` / `wlan`）的累计收发字节与实时速率（↓/↑）。
- **设备状态**：网络接口（状态 / IPv4 / 运行时长 / 收发计数）、无线（SSID / 信道 / 客户端数）、
  DHCP 租约（经 SSH 读取 `/tmp/dhcp.leases`）。
- **流量与资源监控**：自绘折线图展示下行 / 上行速率、内存占用、负载的滚动历史（最近 60 个采样点）。
- **SSH 远程终端**：持久 PTY Shell 会话，支持交互式命令与常用快捷命令。
- **自动刷新**：按设定间隔（默认 5 秒）自动轮询；支持手动刷新。
- **演示模式**：无需真实路由器即可预览全部界面（内置模拟数据）。
- **本地保存**：连接与 SSH 配置保存在本机 `SharedPreferences`。

> ⚠️ **安全说明**：应用默认通过 **HTTP** 明文访问路由器（局域网内常见），在 `AndroidManifest.xml`
> 与 `res/xml/network_security_config.xml` 中显式允许明文流量并信任用户证书。若路由器使用 HTTPS
> 且为自签名证书，请在设置中开启「忽略证书校验」。账号密码仅保存在本机，不会上传。

## 连接方式：rpcd ubus（重要）

应用通过 **rpcd 的 ubus JSON-RPC 端点 `/ubus`** 与路由器通信，这也是 LuCI 网页自身使用的接口。

```
POST http://<地址>:<端口>/ubus
{"jsonrpc":"2.0","id":1,"method":"call","params":[<session>,"system","info",{}]}
```

登录使用空会话 id 调用 `session.login`，返回 `ubus_rpc_session` 后用于后续调用。

| 调用 | 说明 |
| --- | --- |
| `system board` | 主机名、型号、固件版本 |
| `system info` | 运行时长、负载、内存与交换分区 |
| `network.interface dump` | 逻辑接口：状态、IPv4、运行时长、收发计数 |
| `network.device status` | 各设备的累计收发字节（接口计数的回退来源） |
| `network.wireless status` | 无线射频 / SSID / 已连接客户端 |

> **历史说明**：早期版本使用旧的 `luci-rpc` 接口（`/cgi-bin/luci/rpc/auth`）。该接口在
> OpenWrt 19.07+ 已被移除并取代为 rpcd/ubus，因此会出现「浏览器能打开、应用连不上」的现象。
> 现已切换为 ubus，与网页端一致。

**排错提示**：应用内的错误提示会区分「无法解析地址」「连接被拒绝」「连接超时」「HTTP 404」
「认证失败」「ubus 权限不足（代码 6）」等情况，并给出对应建议。

## OpenWrt 兼容性

| 版本 | 状态 |
| --- | --- |
| 21.02 / 22.03 / 23.05 / 24.10 / 25.12 | 兼容（`rpcd` 为官方固件默认组件） |

- 官方固件默认包含 `rpcd`，无需额外安装。若为精简固件，请确认存在 `rpcd` 与 `uhttpd-mod-ubus`
  （LuCI 可正常打开即说明已具备）。
- 鉴权使用具备 `ubus` 读权限的账号（通常为 `root`）。
- ⚠️ 以上基于 **API 契约** 判断（ubus 对象/方法在各版本保持一致），非真机逐版本验证。

## 统一签名

`debug` 与 `release` 使用同一把密钥签名（安装互相覆盖不会冲突）：

- 密钥库：`app/keystore/mmckb-release.p12`（PKCS12）
- 别名：`mmckb`；有效期至 2056 年
- 证书颁发者 / 所有者：`CN=MMCKB, OU=OpenWrt Status, O=MMCKB, L=Shenzhen, ST=Guangdong, C=CN`
- 口令与路径通过 `gradle.properties` 的 `MMCKB_*` 项注入，CI 中无需额外配置

```bash
# 重新生成（如需）：
keytool -genkeypair -keystore app/keystore/mmckb-release.p12 -storetype PKCS12 \
  -keyalg RSA -keysize 2048 -validity 10950 -alias mmckb \
  -dname "CN=MMCKB, OU=OpenWrt Status, O=MMCKB, L=Shenzhen, ST=Guangdong, C=CN"
```

## 应用图标

图标为程序化生成的矢量风格图形（深蓝 `#1565C0` 底 + 青色 `#00A6FF` 信号波 + 白色路由机身），
输出各密度 `mipmap-*/ic_launcher.png` 与自适应图标（`mipmap-anydpi-v26` + 透明前景）。

## 本地构建

### 方式一：Android Studio（推荐）
1. 用 Android Studio 打开本项目根目录。
2. 若缺少 Gradle Wrapper，Android Studio 会提示并自动生成；或先执行 `gradle wrapper --gradle-version 8.13`。
3. 点击 **Run** 或执行 `./gradlew :app:assembleDebug`。

### 方式二：命令行（已安装 Gradle）
```bash
gradle :app:assembleDebug        # 调试包（已用 MMCKB 密钥签名）
gradle :app:assembleRelease      # 发布包（同一密钥签名）
```
APK 产物位于 `app/build/outputs/apk/`。

### 环境要求
- JDK 17
- Android SDK（Platform 36、Build-Tools 36.0.0）
- Gradle 8.13+（CI 中显式安装）

## 项目结构

```
.
├── .github/workflows/build.yml   # GitHub Actions：构建 debug + release 并上传
├── app/
│   ├── build.gradle.kts
│   ├── keystore/                 # 统一签名密钥库（PKCS12）
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/mmckb/openwrtstatus/
│       │   ├── MainActivity.kt
│       │   ├── data/
│       │   │   ├── model/        # 数据模型（配置 / 状态 / 接口 / 无线 / 租约 / 历史）
│       │   │   ├── remote/       # rpcd ubus JSON-RPC 客户端
│       │   │   ├── ssh/          # SSH 终端与单次命令执行
│       │   │   ├── repository/   # 状态聚合与模拟数据
│       │   │   └── local/        # 配置持久化
│       │   └── ui/
│       │       ├── theme/        # 自定义色板与形状
│       │       ├── components/   # 卡片 / 顶栏 / 液态玻璃悬浮 Tab / 折线图
│       │       ├── screens/      # 概览 / 设备 / 监控 / 终端 / 设置
│       │       └── RouterViewModel.kt
│       └── res/                  # 图标、主题、网络安全配置
├── build.gradle.kts             # 根构建脚本（插件版本管理）
├── settings.gradle.kts
└── gradle.properties
```

## 自动构建（CI）

推送代码到 `main` / `master` 分支，或手动在 **Actions → Build APK → Run workflow**，
GitHub Actions 会自动：
1. 配置 JDK 17 与 Android SDK（Platform 36、Build-Tools 36.0.0）；
2. 安装 Gradle 8.13 并构建 `assembleDebug` 与 `assembleRelease`（统一 MMCKB 密钥签名）；
3. 分别上传 `openwrt-status-app-debug` 与 `openwrt-status-app-release` 产物。

## 技术栈

| 组件 | 版本 |
| --- | --- |
| Kotlin | 2.3.10 |
| Android Gradle Plugin | 8.13.2 |
| Gradle | 8.13 |
| Compose BOM | 2026.02.00（Compose 1.10.3） |
| compileSdk / targetSdk / minSdk | 36 / 35 / 24 |
| 液态玻璃 | `io.github.kyant0:backdrop:1.0.6` + `shapes:1.2.0` |
| SSH | `com.github.mwiede:jsch:0.2.22` |

## 许可证

见仓库根目录 `LICENSE`。
