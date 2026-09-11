# OpenWrt 状态 (OpenWrt Status)

一个用于查看 **OpenWrt 路由器状态** 的 Android 应用，使用 **Kotlin + Jetpack Compose** 实现。
可直观展示路由器的在线状态、连接设备、网络流量与系统负载等核心信息，并配套 **GitHub Actions**
自动构建 APK。

## 功能

- **在线状态**：实时显示路由器是否在线，并展示最近更新时间。
- **系统概览**：主机名、设备型号、固件版本、持续运行时间。
- **系统负载**：1/5/15 分钟平均负载、内存使用率、交换分区使用率。
- **网络流量**：各网络接口（如 `br-lan` / `eth0` / `wlan0`）的累计收发字节与实时速率（↓/↑）。
- **连接设备**：通过路由器 ARP 表列出已连接设备（IP / MAC / 接口 / 名称）。
- **自动刷新**：按设定间隔（默认 5 秒）自动轮询；支持手动刷新。
- **演示模式**：无需真实路由器即可预览全部界面（内置模拟数据）。
- **本地保存**：路由器地址、端口、账号、刷新间隔等配置保存在本机。

> ⚠️ **安全说明**：应用默认通过 **HTTP** 明文访问路由器（局域网内常见），并在 `AndroidManifest.xml`
> 中开启了 `usesCleartextTraffic`。若你的路由器启用了 HTTPS，请在设置中开启「使用 HTTPS」。
> 账号密码仅保存在本机 `SharedPreferences`，不会上传。

## 支持的路由器接口

应用通过 OpenWrt 的 **LuCI JSON-RPC** 接口（`luci-rpc`）获取数据：

| 调用 | 说明 |
| --- | --- |
| `sys.system.info` | 主机名、负载、内存、交换分区 |
| `sys.uptime` | 运行时长（秒） |
| `sys.net.arp` | 已连接设备（IP / MAC / 接口） |
| `sys.net.deviceinfo` | 各接口累计收发字节 |

使用前请确认路由器已安装 `luci-rpc` 软件包：

```bash
opkg update && opkg install luci-rpc
```

> 注：部分固件（如官方 23.05+）已内置该接口；若使用 `uhttpd-mod-ubus` / `rpcd` 鉴权，
> 请使用具有 `uci` / `ubus` 读权限的账号（通常是 `root`）。

## 本地构建

### 方式一：Android Studio（推荐）
1. 用 Android Studio 打开本项目根目录。
2. 若缺少 Gradle Wrapper，Android Studio 会提示并自动生成；或先执行 `gradle wrapper --gradle-version 8.6`。
3. 点击 **Run** 或执行 `./gradlew :app:assembleDebug`。

### 方式二：命令行（已安装 Gradle）
```bash
gradle :app:assembleDebug        # 调试包
gradle :app:assembleRelease      # 发布包（需自行配置签名）
```
APK 产物位于 `app/build/outputs/apk/debug/`。

### 环境要求
- JDK 17
- Android SDK（Platform 34、Build-Tools 34.0.0）
- Gradle 8.6+（CI 中由 `gradle/gradle-build-action` 自动安装）

## 项目结构

```
.
├── .github/workflows/build.yml   # GitHub Actions：自动构建并上传 APK
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/mmckb/openwrtstatus/
│       │   ├── MainActivity.kt
│       │   ├── data/
│       │   │   ├── model/        # 数据模型
│       │   │   ├── remote/       # LuCI RPC 客户端
│       │   │   ├── repository/   # 状态聚合与模拟数据
│       │   │   └── local/        # 配置持久化
│       │   └── ui/
│       │       ├── theme/        # Compose 主题
│       │       ├── screens/      # 仪表盘 / 设置
│       │       └── RouterViewModel.kt
│       └── res/values/           # 字符串与主题
├── build.gradle.kts             # 根构建脚本（插件版本管理）
├── settings.gradle.kts
└── gradle.properties
```

## 自动构建（CI）

推送代码到 `main` / `master` 分支，或手动在 **Actions → Build APK → Run workflow**，
GitHub Actions 会自动：
1. 配置 JDK 17 与 Android SDK；
2. 安装 Gradle 8.6 并构建 `assembleDebug`；
3. 将生成的 `app-debug.apk` 作为构建产物（Artifact）上传，可在 Actions 页面下载。

## 许可证

见仓库根目录 `LICENSE`。
