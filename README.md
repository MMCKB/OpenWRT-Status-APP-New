# OpenWrt 状态 (OpenWrt Status)

一个用于查看与管理 **OpenWrt 路由器** 的 Android 应用，使用 **Kotlin + Jetpack Compose** 实现。
支持**多台路由器管理**、实时状态概览、流量与资源监控、DHCP 租约查看与 **SSH 远程终端**，
并配套 **GitHub Actions** 自动构建签名 APK。

## 功能

### 多设备管理
- 「设备」页以**卡片**管理多台路由器：点卡片一键切换当前设备
- 向左滑动卡片呼出**编辑 / 删除**操作（删除有二次确认）
- 添加/编辑页合并填写连接配置（rpcd ubus 地址与凭据、HTTPS、SSH 配置）
- 旧版单机配置会自动迁移为设备列表中的一条记录

### 概览
- 主机名、型号、固件、运行时间、在线状态与最近更新时间
- 系统资源：1/5/15 分钟平均负载、内存与交换分区占用
- 网络流量：各接口累计收发字节与实时速率（↓/↑）

### 监控
- 自绘折线图：下行 / 上行速率、内存占用、负载的滚动历史（最近 60 个采样点）
- 网络接口（状态 / IPv4 / 运行时长 / 收发计数）
- 无线（SSID / 信道 / 客户端数）
- **DHCP 租约**（经 SSH 读取 `/tmp/dhcp.leases`）
- 以上均作用于**当前选中的设备**

### SSH 远程终端
- 持久 PTY Shell 会话，支持交互式命令与常用快捷命令
- 远程行编辑：Tab 补全、shell 历史、`Ctrl+C` 等控制键均可正常使用

### 设置
- 只保留一个「关于」入口，内含版本号、GitHub 仓库与开源许可信息

### 视觉
- 底部悬浮 Tab 使用 Kyant0 的**液态玻璃**库（`backdrop`）：实时模糊、折射、高光与阴影，
  切换 Tab 有按压/拖拽果冻动效（交互层改编自其官方示例）
- 扁平卡片 + 描边分层的自定义色板，跟随系统深色模式

### 其他
- **自动刷新**：按固定间隔（默认 5 秒）自动轮询；支持手动刷新
- **本地保存**：设备列表与 SSH 配置仅保存在本机 `SharedPreferences`

## 连接方式：rpcd ubus

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

## Android 兼容性

- 最低支持 **Android 7.0（API 24）**
- 面向 **Android 17（targetSdk 37）** 构建
- 液态玻璃 Tab 特效按系统版本降级：blur 需 Android 12+，lens 需 Android 13+，低版本自动退化为半透明胶囊

## 安全说明

应用默认通过 **HTTP** 明文访问路由器（局域网内常见），在 `AndroidManifest.xml`
与 `res/xml/network_security_config.xml` 中显式允许明文流量并信任用户证书。
若路由器使用 HTTPS 且为自签名证书，请在设备编辑页开启「忽略证书校验」。
账号密码仅保存在本机，不会上传。

## 本地构建

### 方式一：Android Studio（推荐）
1. 用 Android Studio 打开本项目根目录。
2. 若缺少 Gradle Wrapper，Android Studio 会提示并自动生成；或先执行 `gradle wrapper --gradle-version 9.7.1`。
3. 点击 **Run** 或执行 `./gradlew :app:assembleDebug`。

### 方式二：命令行（已安装 Gradle）
```bash
gradle :app:assembleDebug        # 调试包（已用 MMCKB 密钥签名）
gradle :app:assembleRelease      # 发布包（同一密钥签名）
```
APK 产物位于 `app/build/outputs/apk/`。

### 环境要求
- JDK 17 及以上（CI 使用 21）
- Android SDK（Platform android-37.2、Build-Tools 36+）
- Gradle 9.7.1（CI 中显式安装；需 Gradle 9.x 以配合 AGP 9）

## 统一签名

`debug` 与 `release` 使用同一把密钥签名（安装互相覆盖不会冲突）：

- 密钥库：`app/keystore/mmckb-release.p12`（PKCS12）
- 别名：`mmckb`；有效期至 2056 年
- 证书颁发者 / 所有者：`CN=MMCKB, OU=OpenWrt Status, O=MMCKB, L=Shenzhen, ST=Guangdong, C=CN`
- 口令与路径通过 `gradle.properties` 的 `MMCKB_*` 项注入，CI 中无需额外配置

## 自动构建（CI）

推送代码到 `main` / `Dev` 分支，或手动在 **Actions → Build APK → Run workflow**，
GitHub Actions 会自动：
1. 配置 JDK 21 与 Android SDK（Platform android-37.2、Build-Tools 36.0.0）；
2. 安装 Gradle 9.7.1 并构建 `assembleDebug` 与 `assembleRelease`（统一 MMCKB 密钥签名）；
3. 分别上传 `openwrt-status-app-debug` 与 `openwrt-status-app-release` 产物。

## 技术栈

| 组件 | 版本（Dev 分支） |
| --- | --- |
| Kotlin | 2.4.20 |
| Android Gradle Plugin | 9.4.0（AGP 9 内置 Kotlin 支持） |
| Gradle | 9.7.1 |
| Compose BOM | 2026.09.00（Compose 1.12.1） |
| compileSdk / targetSdk / minSdk | 37.2 / 37 / 24 |
| 液态玻璃 | `io.github.kyant0:backdrop:2.0.1` + `shapes:1.2.1` |
| SSH | `com.github.mwiede:jsch:2.28.7` |
| 网络 | `com.squareup.okhttp3:okhttp:5.5.0` |

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
│       │   │   ├── model/        # 数据模型（设备配置 / 状态 / 接口 / 无线 / 租约 / 历史）
│       │   │   ├── local/        # 设备列表持久化（JSON + SharedPreferences）
│       │   │   ├── remote/       # rpcd ubus JSON-RPC 客户端
│       │   │   ├── ssh/          # SSH 终端与单次命令执行
│       │   │   └── repository/   # 状态聚合
│       │   └── ui/
│       │       ├── theme/        # 自定义色板与形状
│       │       ├── components/   # 卡片 / 顶栏 / 液态玻璃悬浮 Tab / 折线图
│       │       ├── glass/        # 改编自 Kyant0/AndroidLiquidGlass 的玻璃交互动画
│       │       ├── screens/      # 概览 / 设备管理 / 监控 / 终端 / 设置 / 关于
│       │       └── RouterViewModel.kt
│       └── res/                  # 图标、主题、网络安全配置
├── build.gradle.kts             # 根构建脚本（插件版本管理）
├── settings.gradle.kts
├── LICENSE                      # MIT
└── THIRD_PARTY_NOTICES.md       # 第三方组件许可声明
```

## 许可证

本项目代码以 [MIT](./LICENSE) 协议发布（Copyright (c) 2026 MMCKB）。

所使用的第三方组件及其许可见 [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)，许可全文位于
[`licenses/`](./licenses/) 目录。其中 `ui/glass/` 下 4 个文件改编自
[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0，
修改已在文件头声明）；JSch 为 BSD-3-Clause，二进制分发时须保留其版权声明。
