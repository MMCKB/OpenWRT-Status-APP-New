# OpenWrt Status App — 问题清单（Dev 分支）

> 仓库：`https://github.com/MMCKB/OpenWRT-Status-APP-New` ｜ 分支：**Dev**
> 整理时间：2026-10-10；**2026-10-11 增补：对齐 Google 官方架构指南，新增 S30 与官方引用索引**
> 配套文档：《OpenWrt-Status-App-Dev-仓库分析与技术栈升级评估.md》（版本升级评估）
>
> **状态图例**：🔴 未修复 ｜ 🟡 部分缓解 ｜ 🟢 已修复 ｜ ⏸️ 暂缓
>
> **「统一管理」建议的依据**：本文件所有横切面（S22–S30）的归位建议，
> 均对齐 Google 官方《[应用架构指南](https://developer.android.google.cn/topic/architecture?hl=zh-cn)》
> 与《[有关 Android 架构的建议](https://developer.android.google.cn/topic/architecture/recommendations?hl=zh-cn)》，
> 逐条引用位于 **4.2.0 节** 与 **第七节（官方引用索引）**。

---

## 摘要

| 编号 | 级别 | 问题 | 状态 |
| --- | --- | --- | --- |
| S1 | 高 | 地址/端口/用户名/密码明文存 DataStore | 🟢 已修复 |
| S2 | 高 | 无 Gradle Wrapper，构建不可复现 | 🔴 |
| S3 | 中 | 解密后的凭据经 Intent 跨 17 个 Activity 传递 | 🟢 已修复 |
| S4 | 中 | 明文 HTTP + 信任用户证书 + 可选全信任 TLS | 🟡 设计取舍 |
| S5 | 中 | 零测试 | 🔴 |
| S6 | 中 | 20 个 Activity，无 Navigation Compose | ⏸️ 暂缓 |
| S7 | 中 | 巨型单文件（最大 2409 行） | 🔴 |
| S8 | 中 | 无 i18n，约 1552 处硬编码中文 | 🔴 |
| S9 | 中 | `material-icons-extended` 被上游冻结 | 🔴 |
| S10 | 低 | 死代码 `LuciRpcClient.kt` | 🟢 已修复（文件已删除） |
| S11 | 低 | `UbusRpcClient.clients` 非线程安全 | 🟢 已修复（ConcurrentHashMap） |
| S12 | 低 | 主线程 `runBlocking` 读 DataStore | 🔴 |
| S13 | 低 | 未开启 Gradle 配置缓存 | 🔴 |
| S14 | 低 | 无版本目录 `libs.versions.toml` | 🔴 |
| S15 | 低 | 无依赖自动更新（Dependabot/Renovate） | 🔴 |
| S16 | 高 | 共享存储 `/sdcard/` 残留**明文**配置快照与 UI dump | 🔴 待清理 |
| S17 | 中 | 默认走明文 HTTP，路由器密码在局域网内可被嗅探 | 🔴 |
| S18 | 中 | `allowInsecureTls` 是完全信任（含 hostname），开启后可被 MITM | 🟡 设计取舍 |
| S19 | 低 | TOFU 指纹不匹配时返回 `NOT_INCLUDED` 而非 `CHANGED` | 🔴 |
| S20 | 低 | 敏感页未启用 `FLAG_SECURE`，可被截屏/录屏 | 🔴 |
| S21 | 低 | DataStore 残留 `useMock` 死键 | 🟢 已修复（死键已清） |
| **S22** | **高** | **Shell 转义函数在 11 处重复定义** | 🔴 |
| **S23** | **高** | **错误文案映射在 13 处复制，且已现行为分叉** | 🔴 |
| **S24** | 中 | 超时常量分散 12 个文件，语义相近却取值不一 | 🔴 |
| **S25** | 中 | 18 个二级 Activity 是同一份 60 行模板 | 🔴 |
| **S26** | 中 | `AdminClient` 自建 OkHttp 实例，绕过统一信任/超时策略 | 🔴 |
| **S27** | 低 | 「加载/错误/空」三态 UI 在 13 个 Screen 各自实现 | 🔴 |
| **S28** | 低 | 6 处直接调 `Toast`，绕过统一组件（横屏不显示） | 🔴 |
| **S29** | 低 | 1227 条中文字面量硬编码，无 `strings.xml` | 🔴 |
| **S30** | **高** | **16 个 Screen 直接实例化数据源 Client，绕过 Repository**（违反官方「强烈建议」） | 🔴 |

> **S22–S30 的共性**：它们不是「某一行写错了」，而是**同一逻辑被复制到 N 个文件**。
> 详细分析见「**四之二、横切面：同一逻辑散落在 N 处**」一节——
> 该节开头（**4.2.0**）先给出**对照 Google 官方架构的归位表**，
> 逐条说明每个横切面在官方分层里属于哪一层、应该用什么机制统一。

---

## 一、安全与隐私

### S1 🟢 设备连接信息明文落盘 —— 已修复

**原问题**
`SettingsStore.toJson()` 把地址、端口、用户名与密码以明文写进 `devices_json` 这个
DataStore 字符串。root 设备、`adb backup`、或任何能读到
`files/datastore/openwrt_status_prefs.preferences_pb` 的途径都能直读。

**修复方式**（新增 `data/local/CredentialCipher.kt`，改造 `SettingsStore.kt`）
- 密钥：Android Keystore 内生成 **AES-256-GCM**，别名 `openwrt_status_credentials_v1`，
  密钥材料不可导出；有 TEE 的设备上落在安全硬件里。
- 存储格式：`enc:v1:<base64(iv ‖ ciphertext ‖ gcmTag)>`，96 位 IV + 128 位认证标签。
- **加密范围**（`SettingsStore.ENCRYPTED_KEYS`）：`ip` / `port` / `username` /
  `sshHost` / `sshPort` / `sshUsername` / `password` / `sshPassword`。
  地址与用户名同样能定位到具体路由器，只加密密码会让防护形同虚设。
  `port` / `sshPort` 是 Int，以十进制字符串加密存储，读取端 `decrypt` 后
  `toIntOrNull` 解析回 Int，并兼容旧版直接存的 JSON number。
  仅 `name`（用户自取标签）与几个功能开关保持明文以便排错。
- **SSH 主机指纹表**（`ssh_host_keys`）整串加密。这张表的**键就是「地址:端口」**，
  明文存着等于把 `ip` 的加密绕过去——第一轮只加密设备 JSON 时漏了这里，
  真机验证时才发现的（见下方「真机验证」）。
  指纹本身不是秘密，但没必要为了它暴露地址。
- 迁移：`decrypt` 对无前缀的值原样返回（兼容旧明文），`loadDevices` 检测到明文后
  一次性密文回写；旧单机配置的 `password` / `sshPassword` 键在迁入设备列表后被删除，
  避免明文残留。
- 降级不崩：加密失败退化为明文并记日志；解密失败退化为空串（提示用户重填），不抛异常。
- 线程安全：密钥的「读取或首次生成」用 `synchronized` 串行化，避免并发首调重复生成、
  覆盖密钥导致既有密文无法解开。
- 加解密调用点（`loadDevices` / `saveDevices`）整体包在 `withContext(Dispatchers.IO)`，
  因为 Keystore 首次取密钥是阻塞操作。

**残留风险（可接受，已记录）**
- `encrypt` 失败时会**静默写回明文**。这是「宁可退回明文也不丢配置」的取舍，
  但意味着极端情况下仍可能有明文落盘。每次启动会重试加密。
- 密钥与 DataStore 文件同设备，属于「防离线读取」而非「防本机提权」。
  已被 `backup_rules.xml` / `data_extraction_rules.xml` 排除出云备份与设备迁移，
  因此不会出现「密文过设备、密钥不过去」的错配。

**真机验证（Android 15 / API 35，root 直读 DataStore）**

在 MuMu 模拟器上装了 CI 产出的 arm64 包，直读
`files/datastore/openwrt_status_prefs.preferences_pb`，实测结果：

| 待检字符串 | 结果 |
| --- | --- |
| 新增设备的密码 `TestPass123` | ✅ 未出现 |
| 新增设备的地址 `192.168.1.1` | ✅ 未出现 |
| 端口 `8888` | ✅ 未出现 |
| 设备 JSON 里的 ip / port / username / password / sshPort / sshUsername / sshPassword | ✅ 全部为 `enc:v1:` 密文 |
| 路由器地址 `192.168.2.1` | ❌ **曾出现**（在 `ssh_host_keys` 的键里）→ 已修复 |

界面侧同时确认：设备卡片仍能正确显示 `192.168.2.1:8888 · root`，
说明**加密 → 落盘 → 读回解密**整条链路可用。

**升级迁移路径实测（用真机 dump 出的旧明文文件回灌）**

光看新装的包只能证明「新数据是加密的」，证明不了「老用户升级后旧数据也被加密」。
于是把设备上找到的一份**旧版明文 DataStore** 直接覆盖回去，再启动 App：

- ✅ `devices_json` 里的 ip / port / username / password / sshPort / sshUsername /
  sshPassword **全部被自动加密回写**（文件从 648 → 1071 字节）；
- ❌ **但旧单机键 `ip` / `password` / `sshPassword` / `username` 仍是明文**
  ——清理逻辑只写在「`devices_json` 不存在」的分支里，而这份数据**两者同时存在**，
  走的是另一个分支，旧键永远不会被清。

→ 已修复：抽出 `purgeLegacyKeys()`，在两条分支上都调用（无残留时不产生写入）。
这个问题编译期、代码审阅都发现不了，只有真的跑一次升级路径才会暴露。

**两处修复的复验结果（装新包后重跑同一套测试）**

| 测试 | 结果 |
| --- | --- |
| `ssh_host_keys` 迁移 | 启动前 `{"192.168.2.1:22":"71:79:..."}` → 启动后 `enc:v1:3xoWRE6e4yTZKnu...` ✅ |
| 旧单机键清理 | 启动前存在 `ip=192.168.2.1` → 启动后文件里**只剩** `activeDeviceId` / 开关 / `devices_json` / `ssh_host_keys` ✅ |
| 迁移后全文件明文扫描 | `192.168.2.1` / `192.168.1.1` / `8888` / `TestPass123` / `root` / `71:79:c9:a1` **全部未出现** ✅ |

---

### S3 🟢 解密后的凭据经 Intent 跨 Activity 传递 —— 已修复

**原问题**
`RouterConfig` 实现 `java.io.Serializable`，17 处
`intent.getSerializableExtra(EXTRA_CONFIG) as? RouterConfig`：每次进入二级页（无线、
日志、路由表、防火墙…），**已解密的明文地址、用户名与密码**都会作为 Serializable
extra 走一次 Binder 事务，短暂驻留在 system_server 的 Intent 记录里。
所有二级 Activity 均为 `android:exported="false"`，因此不存在跨应用泄露——属于
「不必要的暴露面」，但在 S1 修复后它是仅存的明文驻留点。

**修复方式**（新增 `ui/DeviceHandoff.kt`，改造 19 个 Activity + `AppRoot.kt`）
- **出向**：Intent 只带 `EXTRA_DEVICE_ID`（一个不含凭据的字符串）。页面用
  `rememberDeviceConfig(deviceId)` / `rememberDeviceContext(...)` 自己取配置：
  优先读进程内快照 `DeviceSnapshot`（由 `RouterViewModel` 在设备列表变化时发布，
  与主界面同源，不存在「刚改完设备、DataStore 还没写完就被打开二级页」的竞态），
  快照缺失时退回 DataStore 读取——进程被杀后重建，Intent 里的 id 仍在，仍能取到正确配置。
- **回向**：保存/删除的结果同样带着明文凭据，因此不经 `setResult` 返回（那部分数据由
  system_server 持有），改由 `DeviceEditResult` 进程内交接。Intent 只承载
  `EXTRA_OPEN_INLINE` 这类不含凭据的标记位。
- **编辑页的设备列表**（用于重名检查的 `existingNames`）也不再经 Intent 传递——
  标签为空时会回落到地址，同样属于敏感信息。
- **移除 `RouterConfig : java.io.Serializable`**：已无任何使用点，同时从根上堵住
  「顺手塞进 Intent」的可能。

**真机验证（Android 15 / API 35）**

在 MuMu 模拟器上按下面的路径实测，全程无崩溃：

1. 主界面 → 工具 → **无线设置**（`WirelessActivity`）：正确加载出真实路由器的
   radio0（信道 149 / 5 GHz）、radio1（信道 5 / 2.4 GHz）、SSID 与 BSSID
   → 说明设备 id 传递成功、配置解密正确、连接可用。
2. 设备页 → **添加** → 表单显示的是**空白默认值**（192.168.1.1 / 80 / 22），
   **没有被填入当前设备的 192.168.2.1:8888**
   → 说明 `fallbackToActive=false` 生效（这正是最容易写错的地方）。
3. 填名 + 密码 → **保存**：页面正常返回，设备列表变为 2 台，新设备被设为当前设备，
   地址显示正确。
4. 直读 DataStore：新设备的 ip / port / username / password 全部为 `enc:v1:` 密文
   → 出向、回向、落库、解密四条链路都通了。

**19 个二级页全量回归**（`am start` 逐个拉起，`deviceId` 用真实设备 id）

19/19 全部正常，**零崩溃**。覆盖 About / ThemeSettings / Admin / ChannelAnalysis /
Crontab / DeviceEdit / FileManager / Flash / Led / Logs / NetworkInterfaces / Nftables /
PackageManager / Processes / Realtime / Routes / Startup / System / Wireless。

**容错路径**（`rememberDeviceConfig` 的回落链）

| 场景 | 结果 |
| --- | --- |
| 不带 `deviceId`（模拟进程被杀后重建） | ✅ 无崩溃，回落到活动设备并加载出真实无线数据 |
| 传一个不存在的 `deviceId` | ✅ 无崩溃，同样回落到活动设备 |

**Intent 记录实证**：拉起二级页后全量 `dumpsys activity` 中搜
`192.168.2.1` / `sshPassword` / `password=` —— **命中 0 次**。
Intent 记录里只显示 `WirelessActivity (has extras)`，看不到任何凭据。

**残留风险**：`DeviceEditResult` 是进程内状态，若进程恰好在编辑页 `setResult` 之后、
主界面回调之前被杀，这次保存会丢失。窗口仅毫秒级，可接受。

---

### S4 🟡 明文 HTTP + 信任用户证书 + 可选全信任 TLS

**位置**：`AndroidManifest.xml`（`usesCleartextTraffic="true"`）、
`res/xml/network_security_config.xml`（`cleartextTrafficPermitted="true"` + 信任 user 证书）、
`UbusRpcClient.kt:121-131`（`hostnameVerifier { _, _ -> true }`）

**说明**：OpenWrt 在局域网内普遍走 HTTP，HTTPS 多用自签名证书，因此这套配置是
**有意的产品取舍**，不是疏漏。`trustAllCertificates()` 也只在用户显式勾选
「忽略证书校验」时才生效。

**建议**（非必须）
- 在 `network_security_config.xml` 里把 `cleartextTrafficPermitted` 收窄到
  `base-config` 之外，仅对私有网段放行（需配合 `domain-config`，但路由器地址不固定，
  实际难做，可维持现状）。
- 设备编辑页对「忽略证书校验」加一句风险提示文案。

---

### S16 🔴 共享存储残留明文配置快照与 UI dump

**位置**：设备共享存储（`/sdcard/`），非应用私有目录

**证据**（ADB 实测，全部为 2026-10-05 的遗留文件）

| 文件 | 内容 |
| --- | --- |
| `/sdcard/openwrt_status_prefs.preferences_pb` | **完整明文配置**：`ip=192.168.2.1`、`port=8888`、`username=root`、`password=root`、`sshPassword=root` |
| `/sdcard/Download/openwrt_status_prefs.preferences_pb` | 同上，另一份副本 |
| `/sdcard/u1.xml`、`u2.xml`、`u3.xml`、`ui.xml`、`ui2.xml`、`ui3.xml` | `uiautomator dump` 的 UI 层级，含屏幕上显示的 `root@192.168.2.1:8888` |

**影响**：`/sdcard/` 是共享存储，**任何拿到存储权限的 App 都能读**，
文件管理器里也直接可见，还容易被相册/网盘类应用扫走。

**归因**：**不是应用代码造成的**——`grep` 确认代码里没有任何
`getExternalFilesDir` / `Environment.getExternalStorage` / `/sdcard` 写入。
这些是早期调试时手工拷出来的快照与 dump 文件。

**建议**：直接删除这 8 个文件。同时提醒：以后排查问题时不要把
`preferences_pb` 拷到共享存储——即使内容是密文，拷出来也绕过了「只在私有目录」的保护。

---

### S17 🔴 默认明文 HTTP，路由器密码在局域网内可被嗅探

**位置**：`RouterConfig.useHttps` 默认 `false`；`UbusRpcClient.login()` 把
`{"username": ..., "password": ...}` 以 JSON 发到 `http://host:port/ubus`

**说明**：ubus 的 `session.login` 就是「把账号密码明文发给路由器换一个 sid」，
LuCI 本身也是这么做的，属于 OpenWrt 的协议现实。但在 HTTP 下，**同一局域网内
任何能抓包的人（ARP 欺骗、镜像端口、恶意 AP）都能直接拿到路由器密码**。
改密码（`AdminClient.changePassword`）同样如此。

**现状**：设备编辑页只有一个「使用 HTTPS」开关，**没有任何风险提示**。

**建议**（改动都很小）
1. 设备编辑页：`useHttps` 关闭时在开关下方显示一行提示，说明密码会明文传输；
2. 首次连接成功后，若发现路由器同时开放 HTTPS，提示用户可以改用它；
3. README 的安全说明里把这条列在最前面（目前只写了「默认 HTTP」而没说后果）。

---

### S18 🟡 `allowInsecureTls` 是完全信任，开启后可被 MITM

**位置**：`UbusRpcClient.kt:121-131`
```kotlin
override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
...
hostnameVerifier { _, _ -> true }
```

**说明**：`checkServerTrusted` 空实现 = 接受任何证书链；`hostnameVerifier` 恒真 =
接受任何主机名。两者叠加后 HTTPS 的**全部**保护都失效，主动 MITM 可以冒充路由器
拿到密码。这是用户显式勾选「忽略证书校验」才生效的**有意取舍**（路由器多用自签名证书），
且注释写明了意图，因此不算疏漏。

**建议**：仅 UI 层面——在开关旁注明「开启后无法防范中间人攻击」，
并在开启时用一次确认对话框，避免用户随手打开后忘记关。

---

### S19 🔴 TOFU 指纹不匹配时返回 `NOT_INCLUDED` 而非 `CHANGED`

**位置**：`data/ssh/SshClient.kt` 的 `TofuHostKeyRepository.check()`
```kotlin
known == null -> { SshHostKeys.remember(hostPort, fingerprint); HostKeyRepository.OK }
fingerprint == known -> HostKeyRepository.OK
else -> HostKeyRepository.NOT_INCLUDED      // ← 应为 CHANGED
```

**影响**：JSch 里 `NOT_INCLUDED` 表示「未知主机」、`CHANGED` 表示「主机密钥已变更」。
语义上报错了，但**当前不构成漏洞**——因为会话配置了 `StrictHostKeyChecking=yes`，
这两种返回码都会被 JSch 拒绝连接。属于「依赖另一处配置兜底」的脆弱写法：
一旦有人把 `StrictHostKeyChecking` 改成 `no`（很常见的手工调试动作），
`NOT_INCLUDED` 会被**静默接受**，MITM 就此成立。

**建议**：改成返回 `HostKeyRepository.CHANGED`，让「密钥变更」这件事在返回值上
就表达清楚，不再依赖 StrictHostKeyChecking 兜底。

---

### S20 🔴 敏感页未启用 `FLAG_SECURE`

**位置**：全局 —— `grep -rn "FLAG_SECURE" app/src` 零命中

**影响**：设备编辑页（含密码输入）、终端页（可能回显敏感输出）、日志页
都可以被截屏与录屏，第三方录屏/投屏应用可捕获。

**建议**：对设备编辑、终端、日志这三个 Activity 的窗口加
`window.setFlags(FLAG_SECURE, FLAG_SECURE)`（或 Compose 侧等价做法）。

---

### S21 🟢 DataStore 残留 `useMock` 死键 —— 已修复

**证据**：真机 dump 的 `preferences_pb` 里有 `useMock`，但
`grep -rni "mock" app/src` **零命中** —— 早期版本留下的键，代码已不再读写。

**影响**：无安全影响，属数据卫生问题（同 S1 的旧单机键，但那次清理只覆盖了
`LEGACY_CONFIG_KEYS` 列表内的键）。

**建议**：在 `purgeLegacyKeys()` 的清理清单里加上它。

---

### 本轮确认「没有问题」的项（供后续审阅参考）

| 检查项 | 结论 |
| --- | --- |
| WebView / JS 桥 | ✅ 完全没用 WebView，无 JS 注入面 |
| 硬编码密钥 | ✅ 无。APK 里 `sk-` 是 SSH 的 FIDO 算法名（`sk-ssh-ed25519@openssh.com`），`secret` 是 UCI 字段名（`auth_secret`），均为误报 |
| Shell 命令注入 | ✅ **当前无可利用点**。47 处命令执行点，可变的都经 `shq()`（`'` → `'\''` 的 POSIX 单引号转义）；`PackageClient` 另有包名白名单 `[a-zA-Z0-9+._:@/-]` 与仓库 URL 的格式 + 元字符双重校验；`FlashClient` 的临时路径来自时间戳，非用户输入。**但转义函数本身重复了 11 处，见 S22**——现在安全，靠的是每处都没写错 |
| Deep link / 组件劫持 | ✅ `MainActivity` 只有 MAIN/LAUNCHER，无自定义 scheme；其余 19 个 Activity 全部 `exported=false` |
| FileProvider | ✅ 只暴露 `cache-path share/`，范围很窄 |
| ubus 会话 id | ✅ 只在内存，不落盘 |
| SSH 主机密钥 | ✅ `StrictHostKeyChecking=yes`（见 S19 的说明） |
| 剪贴板 | ✅ 只有日志页用于复制日志，不涉及密码 |

---

## 二、构建与工程
### S2 🔴 无 Gradle Wrapper，构建不可复现

**位置**：仓库根目录无 `gradlew` / `gradlew.bat` / `gradle/wrapper/`

**影响**：
- CI（`.github/workflows/build.yml:37-42`）必须 `curl` 下载 Gradle 再 `unzip` 到 `/opt`，
  版本靠 workflow 里硬编码的字符串维护，改版本要动 YAML；
- 本地开发者必须自己装 Gradle，README 只能写「若缺少 Wrapper，Android Studio 会提示生成」；
- 任何人 clone 后都无法保证用到与 CI 相同的 Gradle 版本。

**建议**：提交 `gradlew` + `gradlew.bat` + `gradle/wrapper/gradle-wrapper.jar` +
`gradle/wrapper/gradle-wrapper.properties`（锁 `9.8.1`），随后把 CI 里的
「Install Gradle」步骤整个删掉，改用 `./gradlew`。

---

### S5 🔴 零测试

**位置**：`app/src/` 下只有 `main`，无 `test/` 与 `androidTest/`；
`app/build.gradle.kts:41` 声明了 `testInstrumentationRunner` 但没有任何测试依赖。

**影响**：S1 这类改动（加密 + 迁移）没有任何回归保护。凭据迁移逻辑一旦出错，
用户表现为「密码莫名变空、连不上路由器」，且无法在 CI 阶段发现。

**建议**：优先补两块单测（本地 JVM，无需设备）
1. `CredentialCipher`：加解密往返、空串、幂等（重复 encrypt 不二次加密）、
   明文兼容路径、损坏密文返回空串；
2. `SettingsStore` 的迁移：明文 JSON → 密文回写、旧单机键清理、类型保持。

依赖建议：`junit:junit:4.13.2` + `org.robolectric:robolectric`（Keystore 需模拟）
+ `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0`。

---

### S13 🔴 未开启 Gradle 配置缓存

**位置**：`gradle.properties` 有 `org.gradle.caching=true` 与 `org.gradle.parallel=true`，
但无 `org.gradle.configuration-cache`

**说明**：Gradle 9 下配置缓存已稳定，但**默认不开启**，需显式打开。

**建议**：加 `org.gradle.configuration-cache=true`（可先加
`org.gradle.configuration-cache.problems=warn` 观察一轮再收紧）。

---

### S14 🔴 无版本目录 `libs.versions.toml`

**位置**：依赖版本散落在根 `build.gradle.kts` 与 `app/build.gradle.kts`；
`app/build.gradle.kts` 里 14 行含硬编码版本字符串。

**建议**：抽出 `gradle/libs.versions.toml`，插件与依赖统一用 `libs.` 别名引用。
这是 S15 的前置条件。

---

### S15 🔴 无依赖自动更新

**位置**：`.github/` 下只有 `workflows/`，无 `dependabot.yml` 或 `renovate.json`

**影响**：当前版本之所以新，靠的是人工跟进。长期看会重新落后。

**建议**：加 Dependabot（`gradle` + `github-actions` 两个生态），配合 S14 的版本目录
效果最好。

---

## 三、架构与代码质量

### S6 ⏸️ 20 个 Activity，无 Navigation Compose —— 暂缓

**位置**：`MainActivity` + 19 个二级 Activity（About / ThemeSettings / FileManager /
PackageManager / Wireless / Admin / Led / Processes / Routes / Realtime / ChannelAnalysis /
Nftables / Logs / Flash / NetworkInterfaces / Crontab / Startup / System / DeviceEdit）；
`ui/AppRoot.kt` 1055 行，自研 Tab 状态机 + `movableContentOf` 保活。

**影响**
- 返回栈、状态恢复、深链全靠手写，每个页面都要重复 `setupEdgeToEdge()` 与
  `OpenWrtStatusTheme {}` 样板；
- 跨页共享状态只能靠进程级单例（`ThemePrefs`、`SshHostKeys`、`DeviceSnapshot`）。

**暂缓原因（读代码后修正的判断）**
本文档最初建议「单 Activity + Navigation Compose」，但通读 `AppRoot.kt` 后发现现状
比「20 个 Activity 没有导航」复杂得多：

1. `AppRoot` **已经有一套完整的内联渲染机制**，能渲染全部 19 个二级页——横屏右栏、
   以及竖屏旋转过渡都在用它（`secondaryPane` + `movableContentOf` 跨朝向保活）。
   Activity 层存在的唯一理由是**竖屏时拿系统的预测性返回动画**。
2. 现有实现还包含：横屏双栏布局、三处定制预测性返回（右栏右滑 / 竖屏下滑 / 锚定
   FAB 的展开面板）、`layerBackdrop` 实时模糊的 Tab 条。**Navigation Compose 不原生
   支持双栏**，全量迁移等于重写这 1055 行，且动效可能变差。
3. 因此三条路线的代价与收益差别很大，需要先定方向：
   - **(A)** NavHost 只管二级页、Tab 仍自管 —— 架构最正，但要改造双栏与保活；
   - **(B)** 去掉 19 个 Activity 包装层、全部内联 —— 删掉约 1100 行重复代码，风险最低，
     但竖屏失去系统级预测性返回；
   - **(C)** 连 6 个 Tab 一起进 NavHost —— 最标准，风险最高。

**⚠️ 2026-10-11 补充：暂缓理由需要重新评估（Navigation 3）**

官方《架构建议》中【强烈建议】「**使用单 activity 应用。如果您的应用包含多个屏幕，
请使用 [Navigation 3](https://developer.android.google.cn/guide/navigation/navigation-3?hl=zh-cn)
在屏幕以及指向您应用的深层链接之间导航。**」

注意：官方现在推荐的是 **Navigation 3**，不是本文档最初写的「Navigation Compose」。
这推翻了上面第 2 条暂缓理由的前提：

- 上面说「Navigation Compose **不原生支持双栏**」——该判断基于旧版组件。
  **Navigation 3 是 2026 年推出的全新库**，官方定位是「Compose-first、
  返回栈**完全可控**」，其设计目标正是「为应用提供对**返回堆栈、自适应布局**和灵活的应用导航
  的完全控制」。本项目 `AppRoot.kt` 那 1055 行做的恰是「自管返回栈 + 自适应双栏」——
  **Navigation 3 的能力范围与它高度重合**。
- 官方 UI 层文档中「自适应界面」一节也明确：状态容器应「公开可适应不同窗口大小类别的界面状态」，
  配合 `NavigationSuiteScaffold` 在不同导航模式（`NavigationBar` / `NavigationRail` /
  `NavigationDrawer`）间自动切换——这正是横屏双栏场景的官方解法。

**结论**：S6 的暂缓决定**没有必要立即推翻**（改动仍然大），但**必须先读一遍 Navigation 3
文档再定方向**。上面的 (A)/(B)/(C) 三条路线是基于旧版 Navigation Compose 评估的，
用 Navigation 3 重新评估后，**(C) 的可行性可能显著提升**——
这会让 S6 从「需要重写 1055 行且动效变差」变成「迁移到官方支持的能力，动效有官方保证」。

**建议顺序**：先做 S25（抽 Activity 基类）→ 那时 18 个包装层已收敛到 1 处 →
再评估 Navigation 3 → 决定 (A)/(B)/(C)。**S25 是 S6 的前置条件**。

**建议**：先做 S2/S5 等低风险项，S6 待明确方向后再动。

---

### S7 🔴 巨型单文件

| 文件 | 行数 |
| --- | --- |
| `ui/screens/WirelessScreen.kt` | 2409 |
| `ui/screens/FileManagerScreen.kt` | 1842 |
| `ui/screens/AdminScreen.kt` | 1116 |
| `ui/AppRoot.kt` | 1055 |
| `ui/screens/PackageManagerScreen.kt` | 1043 |
| `ui/screens/FlashScreen.kt` | 810 |

**影响**：单文件承载多个 Composable 与大量私有状态，diff 噪音大、Review 困难、
编译增量收益低。

**建议**：按「一个文件一个主 Composable + 若干私有子组件」拆分；
`WirelessScreen.kt` 可先拆出图表、表单、列表三块。

---

### S8 🔴 无 i18n

**位置**：`res/values/strings.xml` 仅 3 行；`app/src/main/java` 下硬编码中文字面量
约 **1552 处**。

**影响**：无法出英文版，也无法覆盖系统语言变化。

**建议**：属于长期债，不必一次改完。可在新增代码时强制走 `stringResource()`，
存量逐步迁移。

---

### S10 🟢 死代码 `LuciRpcClient.kt` —— 已修复（文件删除）

**位置**：`data/remote/LuciRpcClient.kt`（旧 luci-rpc CGI 客户端）

**证据**：全仓库 `grep -rn "LuciRpcClient"` 在自身文件之外**零引用**；
README 也说明该接口在 OpenWrt 19.07+ 已被移除、现用 ubus。

**建议**：直接删除。留着会让后来者误以为还有两条通信路径。

---

### S11 🟢 `UbusRpcClient.clients` 非线程安全 —— 已修复（ConcurrentHashMap）

**位置**：`data/remote/UbusRpcClient.kt:72`
```kotlin
private val clients = mutableMapOf<Pair<Boolean, Boolean>, OkHttpClient>()
```
`client()` 里用 `clients.getOrPut(...)`。所有调用都在 `Dispatchers.IO` 上，
多个协程可并发进入；`mutableMapOf` 是 `LinkedHashMap`，并发 `getOrPut` 可能触发
内部结构损坏或抛 `ConcurrentModificationException`。

**建议**：换成 `ConcurrentHashMap`，或直接用 `computeIfAbsent`。
`OkHttpClient` 重复创建只是浪费，不会出错，所以这是**低概率但非零**的问题。

---

### S12 🔴 主线程 `runBlocking` 读 DataStore

**位置**：`data/local/SettingsStore.kt:388`（`ThemePrefs.ensureLoaded`）
```kotlin
runBlocking { withTimeoutOrNull(400) { load() } }
```
在 `Activity.onCreate` → `setupEdgeToEdge()` 的调用链上同步阻塞主线程最多 400ms。

**说明**：注释解释了动机——保证首帧就是用户所选主题，避免闪白。这是**有意的取舍**，
且用了超时兜底 + 异步补读。风险在冷启动磁盘慢的设备上表现为首帧卡顿。

**建议**：可接受，暂不动。若要优化，改用 `AppCompatDelegate.setDefaultNightMode`
或 `SplashScreen` 的 `keepOnScreenCondition`，把等待挪到系统启动画面期间。

---

## 四、依赖与维护

### S9 🔴 `material-icons-extended` 被上游冻结

**证据**：Compose BOM `2026.09.00` 中，其余构件均为 `1.12.1`，而
`material-icons-extended` 与 `material-icons-core` 停在 **`1.7.8`** 不再更新
（Google 已不再扩充该构件，转向 Material Symbols）。

**影响**：该库打包了全量 Material 图标，对 APK 体积贡献显著，且不会再收到修复。

**建议**：迁到按需的 vector drawable（只导出实际用到的图标）。
先统计代码里用到的 `Icons.*` 清单，再逐个替换，最后移除依赖。

---

## 四之二、横切面：同一逻辑散落在 N 处（本轮新增）

> 本章的问题都不是「某一行写错了」，而是**同一条逻辑被复制到多个文件**。
> 它们单看都不致命，但共同造成两个后果：
> 1. **漏改即漏洞** —— 修一处忘一处，安全修复会静默失效（S22、S23 属此类）；
> 2. **无法统一调整** —— 想改超时策略、错误文案、转义规则，得改十几个文件。
>
> 这一章同时也是「可统一管理清单」——每一项都对应一个可以抽出来的公共模块。
> 项目里已有正面样板可参照：`Edge.kt`（全部 Activity 共用一个 `setupEdgeToEdge()`）、
> `UbusRpcClient.buildEndpoint()`（地址+端口拼接的唯一入口）、
> `ui/components/AppComponents.kt`（通用 UI 组件）。

### 4.2.0 对照 Google 官方架构：这些横切面分别应该归到哪一层

> 本节的所有「统一管理」建议，都不是凭风格偏好得出的，而是逐条对齐
> **Android 官方《应用架构指南》** 与 **《有关 Android 架构的建议》**。
> 先看官方把应用分成哪几层、每层的职责边界在哪，再看本项目的横切面该往哪收。

**官方推荐的分层**（[应用架构指南](https://developer.android.google.cn/topic/architecture?hl=zh-cn)）：

| 层 | 官方职责 | 本项目的对应物 |
| --- | --- | --- |
| **界面层（UI）** | 在屏幕上显示数据；由「界面元素（Composable）+ 状态容器（ViewModel）」构成 | `ui/screens/*`、`ui/components/*`、`AppRoot.kt` |
| **数据层（Data）** | 包含业务逻辑；由「仓库（Repository）+ 数据源（DataSource）」构成；**向应用其余部分公开数据** | `data/repository/OpenWrtRepository.kt`、`data/remote/*Client.kt`、`data/local/SettingsStore.kt`、`data/ssh/*` |
| **网域层（Domain，可选）** | 封装复杂或可复用的业务逻辑，以「用例（UseCase）」为单位 | 项目未设该层（官方说「非必须」，可接受） |

**官方强调的三条硬性原则**（下面每条建议都以此为准绳）：

- **分离关注点** —— 「将所有代码都写在 `Activity` 中是一个常见错误」；
  `Activity` 只应托管界面。
- **单一可信来源（SSOT）** —— 每种数据只有一个所有者，且**只有它能修改该数据**。
- **单向数据流（UDF）** —— 状态向下流、事件向上流；界面只读状态、只发事件。

**⚠️ 官方两条「强烈建议」，本项目都没做到——这是本章最值得先看的部分：**

**其一：【强烈建议】「确保界面层中的组件（如可组合项或 ViewModel）不会直接与数据源交互。」**
官方明确列举数据源包括「数据库、DataStore、SharedPreferences、Firebase API」等。

本项目**16 个 Screen 全部直接 `new` 了数据源 Client**（详见 S30）：

```kotlin
// ui/screens/LogsScreen.kt:118 —— 界面层直连数据源
val client = remember { LogsClient() }
```

**其二：【强烈建议】「使用代码库从数据层公开应用数据」，且「创建代码库，即使它们只包含一个数据源也不例外。」**

本项目**只有一个 `OpenWrtRepository`，且只被 `RouterViewModel` 使用**；
其余 16 个页面的数据全部绕过仓库，由 Screen 直接调用 Client。官方那句
「**即便只有一个数据源也要建 Repository**」在这里被反向印证了——
正因为没建，界面层才直接抓到了数据源。

> 官方对这条还有一句重要的补充说明：「通常，如果存储库只包含单个数据源并且不依赖于其他存储库，
> 开发者会将存储库和数据源的职责合并到存储库中」——
> 也就是说**允许把 Client 改造成 Repository**，不必强加一层空壳转发。
> 这条对本项目的落地方式影响很大，详见 S30 的建议部分。

**归位对照表**（本章每一项横切面，在官方架构里属于哪一层、用什么机制收）

| 编号 | 横切面 | 官方归属层 | 官方推荐机制 |
| --- | --- | --- | --- |
| S22 | Shell 转义函数 | 数据层 | 数据层内部共享工具；官方要求「明确定义各模块责任边界」 |
| S23 | 错误文案映射 | **界面层** | 官方：错误处理属于界面逻辑；配合 S30 由 ViewModel 产出 `UiState.Error` |
| S24 | 超时常量 | 数据层 | 官方：「类型负责其并发政策」——超时是数据层的并发策略，应随数据源定义 |
| S25 | Activity 模板 | **界面层** | 官方【强烈建议】「使用单 activity 应用 …使用 Navigation 3」 |
| S26 | OkHttp 实例 | 数据层 | 官方：数据源应是「应用与数据操作系统之间的桥梁」，集中在一处 |
| S27 | 三态 UI | **界面层** | 官方：`UiState` 数据类/密封类 + `ErrorView` 等可复用可组合项 |
| S28 | Toast 直连 | **界面层** | 官方：界面逻辑应留在界面层，用统一的信息提示控件（snackbar） |
| S29 | 硬编码中文 | **界面层** | 官方：界面字符串入 `strings.xml`；推荐用 `stringResource()` 读取 |
| **S30** | **界面层直连数据源** | **数据层边界** | 官方【强烈建议】用 Repository 公开数据；配合依赖注入（DI） |

### S22 🔴 Shell 单引号转义函数在 11 处重复定义

**位置**（逐个列出，代码完全相同）：

| 文件 | 行 | 形式 |
| --- | --- | --- |
| `data/remote/AdminClient.kt` | 110 | `private fun shq(v)` |
| `data/remote/LedClient.kt` | 116 | `private fun shq(v)` |
| `data/remote/NetworkInterfacesClient.kt` | 326 | `private fun shq(v)` |
| `data/remote/StartupClient.kt` | 96 | `private fun shq(v)` |
| `data/remote/SystemClient.kt` | 361 | `private fun shq(v)` |
| `data/remote/WirelessClient.kt` | 508 | `private fun shq(v)` |
| `data/ssh/FileOperations.kt` | 473 | `private fun quote(path)`（同逻辑，异名） |
| `data/remote/PackageClient.kt` | 62 | 内联展开，未抽函数 |
| `data/remote/AdminClient.kt` | 231 | 内联展开（UCI 值） |
| `data/remote/LedClient.kt` | 232 | 内联展开（UCI 值） |
| `data/remote/NetworkInterfacesClient.kt` | 123、139 | 内联展开（UCI 值） |

**为什么算安全问题而不是代码风格**：这是**唯一**阻止 shell 注入的屏障。
项目的转义规则是 `'` → `'\''`，一旦某个新 Client 漏写这一句、或写错一个引号
（例如只做 `replace("'", "\\'")`，在 POSIX shell 里**并不安全**），
从路由器读回的任意字符串（接口名、LED 名、UCI 值、进程名）就变成可执行命令。
而这类错误**编译期完全看不出来**，只能靠人工审查每一处。

**建议**：抽到单一位置，例如 `data/ssh/ShellQuote.kt`：
```kotlin
internal fun shq(v: String): String = "'" + v.replace("'", "'\\''") + "'"
```
然后 9 处私有定义 + 5 处内联展开全部改为引用它（可顺带加单测，见 S5）。
配合 S11 一起做收益更高——两者都在 `data/remote/`。

**官方依据**：这属于**数据层内部的关注点**。官方《架构建议》：「**明确定义应用中各模块之间的责任界限。
请勿将从网络加载数据的代码分散到代码库中的多个类或软件包中。**」
——转义规则正是「与数据源交互」的底层细节，应随数据层下沉到一处，而不是散在 9 个 Client 里。
它同时对应架构指南里「关注点分离」的要求：转义是**数据层对外的安全不变式**，
不应由每个调用方各自维护。放在 `data/ssh/` 与 `SshExec`/`SshFiles` 同级最贴合该层职责。

---

### S23 🔴 网络错误 → 用户文案的转换逻辑在 13 处复制

**位置**（13 个函数，分别叫 `adminErrText` / `caErrText` / `crontabErrText` /
`flashErrText` / `flashErrTextStatic` / `ledErrText` / `logsErrText` / `nifErrText` /
`fwErrText` / `processesErrText` / `routesErrText` / `startupErrText` / `sysErrText`）：

| 文件 | 行 |
| --- | --- |
| `AdminScreen.kt` | 87 |
| `ChannelAnalysisScreen.kt` | 70 |
| `CrontabScreen.kt` | 63 |
| `FlashScreen.kt` | 81 |
| `LedScreen.kt` | 76 |
| `LogsScreen.kt` | 79 |
| `NetworkInterfacesScreen.kt` | 76 |
| `NftablesScreen.kt` | 94 |
| `ProcessesScreen.kt` | 82 |
| `RoutesScreen.kt` | 82 |
| `StartupScreen.kt` | 75 **和** 327（同文件内重复两次，`flashErrTextStatic`） |
| `SystemScreen.kt` | 73 |

**⚠️ 这里不只是重复，已经出现行为分叉**（真 bug）：

- 多数版本是 3 个分支：`TimeoutCancellation → Cancellation → else`；
- 但 `caErrText` / `crontabErrText` / `nifErrText` 是 4 个分支，多插了一条
  `e is RouterException -> e.message ?: "请稍后重试。"`；
- 而 `adminErrText` / `flashErrText` / `ledErrText` / `logsErrText` / `fwErrText` /
  `processesErrText` / `routesErrText` / `startupErrText` / `sysErrText` **没有**这条。

由于 `RouterException : IOException`，在缺少该分支的版本里它会落到 `else` 分支，
结果**文案恰好相同**（`e.message ?: "请稍后重试。"`）——所以现在看不出差别，
但这纯属巧合。一旦将来给 `RouterException` 加了统一的 `hint` 拼接、
或在 `else` 分支改成「未知错误，请反馈」，13 处就会立刻表现出不一致。

另外 `StartupScreen` 同一文件两个函数内容**逐字相同**，属于纯冗余。

**建议**：抽到 `ui/screens/ErrxText.kt`（或放进已存在的 `ui/Formatters.kt`）：
```kotlin
internal fun routerErrText(e: Exception): String = when {
    e is TimeoutCancellationException -> "路由器连接中断或长时间无响应，请检查网络后重试。"
    e is CancellationException -> "连接已断开，操作已终止。"
    e is RouterException -> e.hint?.let { "${e.message}（$it）" } ?: (e.message ?: "请稍后重试。")
    else -> e.message ?: "请稍后重试。"
}
```
`ChannelAnalysisScreen` 那句「扫描超时」可作参数传入。顺手把 `RouterException.hint`
真正用起来——`UbusRpcClient.kt:38-42` 定义了这个字段，但 13 处 UI 都没读它。

**官方依据**：错误处理属于**界面层职责**，官方《界面层》指南有专门章节
「[在屏幕上显示错误](https://developer.android.google.cn/topic/architecture/ui-layer?hl=zh-cn)」，
要求把错误建模进 `UiState`：

> 「无论正在执行的操作是否正在加载，可能都需要使用托管以下数据的数据类对错误状态进行建模：
> 适合错误上下文的元数据。」

并且官方给出了明确示范——`UiState` 里用 `Message(id, message)` 承载错误、
由 ViewModel 在 `catch` 中转换后写入状态，界面只负责展示：

```kotlin
catch (ioe: IOException) {
    val messages = getMessagesFromThrowable(ioe)   // ← 官方示例：异常→文案的转换
    uiState = uiState.copy(userMessages = messages)
}
```

**所以这件事在官方架构里的正确位置不是「界面层的一个工具函数」，而是 ViewModel 的
`UiState.Error`**。抽 `routerErrText()` 只是权宜之计（改动小、无风险）；
真正的归位要等 S30 把 ViewModel 补齐——那时这 13 处会自然合并到
「各 ViewModel 产出自己的 `UiState`」这一处，而不再需要界面层自己翻译异常。

另外官方还特别提到错误文案涉及 `Context`/`Resources`，
「将界面逻辑保留在界面中，而不是 ViewModel 中，尤其涉及 `Context` 等界面类型时」——
这正好说明**为什么错误文案最终应走 `strings.xml`（S29）**，而不是硬编码在 Kotlin 里。

---

### S24 🟡 超时常量分散在 12 个 Screen，无统一策略

**位置**：20 个 `private const val *_TIMEOUT_MS`，各自命名、各自取值：

| 文件 | 常量 | 值 |
| --- | --- | --- |
| `AdminScreen.kt` | `ADMIN_APPLY` / `ADMIN_OP` / `ADMIN_LOAD` | 100_000 / 45_000 / 30_000 |
| `ChannelAnalysisScreen.kt` | `CA_SCAN` | 90_000 |
| `CrontabScreen.kt` | `CRONTAB` | 20_000 |
| `FlashScreen.kt` | `FLASH_LOAD` / `FLASH_TRANSFER` | 60_000 / 300_000 |
| `LedScreen.kt` | `LED_APPLY` / `LED_LOAD` | 100_000 / 30_000 |
| `LogsScreen.kt` | `LOGS_LOAD` | 45_000 |
| `NetworkInterfacesScreen.kt` | `NIF` | 20_000 |
| `NftablesScreen.kt` | `FW_LOAD` | 60_000 |
| `ProcessesScreen.kt` | `PROCESSES_LOAD` | 20_000 |
| `RealtimeScreen.kt` | `RT_FETCH` / `RT_POLL_INTERVAL` | 12_000 / 2_000 |
| `RoutesScreen.kt` | `ROUTES_LOAD` | 45_000 |
| `StartupScreen.kt` | `STARTUP_LOAD` / `STARTUP_ACTION` | 45_000 / 30_000 |
| `SystemScreen.kt` | `SYS_APPLY` / `SYS_OP` | 100_000 / 30_000 |

**问题**：语义相近的操作取了不同值 ——「读一次列表」有 20s / 30s / 45s / 60s 四种；
「应用配置」有 30s / 100s 两种。`LogsScreen(45s)` 与 `NftablesScreen(60s)` 都是纯读，
却没有理由差 15 秒。用户侧表现为「有的页面等 20 秒报错，有的等 45 秒」。

**建议**：定义一小组语义化档位，例如 `object OpTimeout { LOAD, ACTION, APPLY, TRANSFER, POLL }`，
各 Screen 只声明自己属于哪一档。想统一放宽「首次冷启动超时」时改一处即可。
注意与 `UbusRpcClient` 的 OkHttp 超时（3s/5s/6s 快档、8s/15s/25s 正常档）配合，
两者是**串联**的：Screen 的 `withTimeout` 是外层上限。

**官方依据**：官方《架构指南》的一般最佳实践里有一条**「类型负责其并发政策」**：

> 「如果某种类型正在执行长时间运行的阻塞工作，则**该类型应负责将该计算移至正确的线程**。
> 该类型知道它正在执行的计算类型以及应在哪个线程中运行计算。
> 类型应该具有主线程安全性，这意味着，您可以安全地从主线程调用这些类型而不会阻塞。」

超时与线程策略同属「并发政策」。按官方口径，**「读一次路由器状态要等多久」是数据层的知识**，
不是界面层的知识——界面层不应该知道 `iwinfo scan` 天生比 `cat /proc/mtd` 慢。
因此正确做法是：**把超时下沉到数据层**（每个 Client 的方法自带合理超时，
或由 Repository 统一按操作类型给出），界面层只保留一个兜底的 `withTimeout`。

这同时呼应官方《界面层》的「**线程处理和并发**」一节：

> 「请确保在 ViewModel 中执行的所有工作都具有主线程安全性……
> **数据层和网域层负责将工作移至其他线程。**」

`ui/screens/*` 里 20 个超时常量 + 374 处 `remember` 状态，本质上是**界面层在替数据层
做并发决策**。收敛它们不只是「少写几个常量」，而是把职责还给数据层。

---

### S25 🟡 18 个二级 Activity 是同一份 60 行模板

**位置**：`AdminActivity` / `ChannelAnalysisActivity` / `CrontabActivity` / `FlashActivity` /
`LedActivity` / `LogsActivity` / `NetworkInterfacesActivity` / `NftablesActivity` /
`ProcessesActivity` / `RealtimeActivity` / `RoutesActivity` / `StartupActivity` /
`SystemActivity` / `WirelessActivity`（14 个 57–66 行，结构完全一致）
+ `AboutActivity` / `FileManagerActivity` / `PackageManagerActivity` / `DeviceEditActivity`（差异略大）。

**重复内容**：每个文件都逐字包含
1. `val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)`
2. `if (savedInstanceState != null && orientation == LANDSCAPE) { handOffToInline(); return }`
3. `setupEdgeToEdge()`
4. `setContent { OpenWrtStatusTheme { Box { rememberDeviceConfig(...); XxxScreen(...); ConnectionToastHost() } } }`
5. `private fun handOffToInline()`
6. `companion object { const val EXTRA_DEVICE_ID = "deviceId"; const val EXTRA_OPEN_INLINE = "openInline" }`

**注意 `EXTRA_DEVICE_ID = "deviceId"` 这个字面量在 18 个文件里各写了一遍** ——
名字相同纯靠约定，编译器不保证。若某人把某一处改成 `"device_id"`，
该页会在**运行时静默回落到「当前设备」**（因为 `rememberDeviceConfig` 对
`deviceId == null` 有兜底），而不是编译失败。

**建议**：抽一个基类或复用函数，例如：
```kotlin
abstract class DeviceScreenActivity : ComponentActivity() { /* 上述 1/2/3/5/6 */ }
```
子类只留下 `@Composable fun Content(config: RouterConfig)` 与专属 extra。
这也**直接减小 S6 的迁移面**——若将来选 (B) 路线去掉 Activity 包装层，
需要改动的就是这一个基类，而不是 18 个文件。

**官方依据**：这条直接对应两条官方主张，且官方措辞是**「强烈建议」**：

1. 《架构指南》：「**将所有代码都写在 `Activity` 中是一个常见错误。**
   `Activity` 的主要作用是托管应用的界面。」
   本项目 18 个 Activity 正是在做「托管界面」这一件事——**职责本身没错，
   错在把同一份托管逻辑抄了 18 遍**。
2. 《架构建议》【强烈建议】：「**使用单 activity 应用。**
   如果您的应用包含多个屏幕，请使用 **Navigation 3** 在屏幕以及指向您应用的
   深层链接之间导航。」

官方给出的标准终局是**单 Activity + Navigation 3**：`AppRoot` 那个 1055 行的自研
Tab 状态机本该由 Navigation 3 承担，19 个包装 Activity 则整个消失。
这正是 S6 的 (B)/(C) 路线——**S25 的基类只是过渡**，让今天少写 18 份拷贝、
明天迁移时少改 18 个文件。

> **Navigation 3 是个新变量**：官方 2026 年已正式推出 Navigation 3
> （[developer.android.google.cn/guide/navigation/navigation-3](https://developer.android.google.cn/guide/navigation/navigation-3)），
> 它是 **Compose-first、返回栈完全可控**的新库，与本项目「自研 Tab 状态机 +
> 定制预测性返回」的现状**契合度明显高于旧版 Navigation Compose**。
> S6 当初暂缓的理由之一是「Navigation Compose 不原生支持双栏」——
> 该判断基于旧版组件，**Navigation 3 值得重新评估**（详见 S6 补充说明）。

---

### S26 🟡 `PackageClient` 独立 `OkHttpClient`，绕过统一的超时/信任策略

**位置**：`PackageClient.kt` 自身不建 client，但 `AdminClient.kt:562` 在
`fetchKeyFromUrl()` 里**现场 new 了一个 `OkHttpClient.Builder()`**，
只设 connect/read 超时，**不读用户选的 `allowInsecureTls`**，也不复用
`UbusRpcClient.client()`。

**影响**：
- 该请求走默认 TLS 信任链 —— 若用户为自签证书开启了「信任所有证书」，此处仍会失败，
  表现为「有的地方能连、有的地方报证书错误」；
- 连接池、Dispatcher 各一份，无法统一调参。

**注**：`LuciRpcClient.kt:30` 另有**第三个** client（该文件已是死代码，见 S10），
`AdminClient` 又是**第四个**。目前全项目共 4 处 OkHttp 实例化。

**建议**：把 client 获取收敛到 `UbusRpcClient.client(...)` 一处（或新建
`HttpClients` 对象），所有出网请求统一走它；`trustAllCertificates()` 的开关也随之统一。
与 S17/S18 一起处理最合适——三者都在改同一段网络代码。

**官方依据**：官方《数据层》要求数据源类「是**应用与数据操作系统之间的桥梁**」，
并强调「**请勿将从网络加载数据的代码分散到代码库中的多个类或软件包中**」（《架构建议》）。
4 个 `OkHttpClient` 实例意味着**有 4 处各不相同的 TLS 信任政策与超时政策**——
这正是官方要避免的「职责分散」。此外官方在「处理依赖关系」中【强烈建议】使用
**依赖注入（构造函数注入）**，[并建议小型项目用 Hilt 或手动 DI](https://developer.android.google.cn/topic/architecture/recommendations?hl=zh-cn#dependencies)：

> 「在简单应用中使用 **Hilt** 或**手动依赖项注入**。」

若引入 Hilt（或手动 DI 容器），`OkHttpClient` 应作为 `@Singleton` 提供，
S26 与 S30 会**同时解决**——因为「界面层 new 出 Client」和「Client 里 new 出 OkHttp」
是同一个根因：**没有依赖注入容器**。

---

### S27 🟡 「加载中 / 出错 / 空数据」三态 UI 在 13 个 Screen 各自实现

**位置**：`ui/screens/*.kt` 共 61 处 `isLoading` / `errorText` / `ErrorView` /
`CircularProgress` 相关代码；另有 3 处 `fun setMsg(text, isError)` 私有实现
（`PackageManagerScreen.kt:153`、`SystemScreen.kt:151`、`WirelessScreen.kt:455`，逻辑相同）。
`DashboardScreen.kt:94` 定义了私有的 `ErrorView`，但**其它 12 个 Screen 都没用它**。

**影响**：各页的空态/错误态外观与重试按钮位置不完全一致；
新增页面时要重新写一遍。

**建议**：把 `ErrorView` 提到 `ui/components/AppComponents.kt`（那里已有
`AppCard` / `MetricTile` / `AppTopBar` / `AppDialog` 等），并补一个 `EmptyState`
与 `LoadingState`，三态统一。

**官方依据**：两层依据。

其一，官方《界面层》明确三态应建进 `UiState`：

> 「在 `UiState` 类中表示加载状态的一种简单方法是使用布尔值字段……
> 在界面中显示错误与显示正在执行的操作类似……可能都需要使用托管以下数据的数据类
> 对错误状态进行建模。」

官方甚至给了密封类示范（「您可以将这些状态建模为密封类」：
`SignedOut` / `InProgress` / `Error` / `SignedIn`）。
**本项目已经有正确样板了**——`RouterModels.kt:200` 的 `StatusUiState`
（`Initial` / `Loading` / `Success` / `Error`），`DashboardScreen` 也在正确使用它。
问题在于**只有 Dashboard 一个页面这么做**，其余 12 个页面各写各的。

其二，官方《Compose 界面架构》对可复用可组合项有明确要求：

> 「为了促进分离和重复使用，**每个可组合项都应包含尽可能少的信息**。」
> 「设计**可重用且可组合**的界面组件。」（《架构指南》一般最佳实践）

`ErrorView` 只在 `DashboardScreen.kt:94` 定义为私有函数，正是违反「可重用」的典型。

---

### S28 🟢 6 处直接调用 `Toast.makeText`，绕过 `ConnectionToastHost`

**位置**：`AboutScreen.kt:97`、`FlashScreen.kt:347/349`、`LogsScreen.kt:251/253/301`
（均为 `android.widget.Toast` 全限定名直接调用）。

**影响**：与项目自研的 `ConnectionToastHost`（`ui/components/ConnectionToast.kt`）
风格不一致，且这几处 toast 在横屏右栏内联渲染时不显示（没有对应 Activity 窗口）。

**建议**：低优先级，改用统一组件即可。

**官方依据**：官方《架构指南》一般最佳实践【强调减少对 Android 类的依赖】：

> 「**减少对 Android 类的依赖。** 使您的应用组件成为**唯一依赖于 Android 框架 SDK API
> （例如 `Context` 或 `Toast`）的类**。」

`Toast` 正是官方点名的例子。这 6 处直接调用把 `android.widget.Toast`
引进了界面逻辑深处；而项目自己的 `ConnectionToastHost` 已经是更好的抽象
（可跨横屏内联渲染）。改用它是**同时满足官方这条建议**的做法。

另外官方对「信息提示控件」的推荐用法是在 `UiState` 里放 `userMessages`，
由界面层统一 `snackbar` 呈现——这也指向 S30。

---

### S29 🟢 1227 条中文字面量直接写在 Kotlin 里，无 `strings.xml`

**位置**：仅 `ui/screens/` 下 `grep` 到的中文字符串就有 1227 条，
全部硬编码在源码；`res/values/strings.xml` 只有 `app_name` 一个键。

**说明**：这既是 S8（无 i18n）的量化证据，也意味着**任何文案改动都要改代码**。
考虑到界面文案（尤其错误提示）是安全提示的载体（S17 要加 HTTP 风险警告、
S18 要加证书确认框），先收敛文案会降低后续安全改动的成本。

**建议**：不做全量 i18n，但至少把**安全相关提示**（HTTP 明文警告、TLS 确认、
指纹变更）先集中到 `strings.xml`，用 `stringResource()` 读取，便于统一措辞与多语言扩展。

**官方依据**：官方资源文档要求界面字符串放入 `res/values/strings.xml`，
好处包括「**当程序存在多个相同内容需要修改时不必逐处改代码**」与
「**利于将程序转变为其他语言**」。这与 S22/S23 是同一个道理的表现形式——
**同一段文案也是一份「被复制到多处、需要统一管理」的逻辑**。

补充一点官方对 Compose 的具体做法：官方示例中 `Icon` 的 `contentDescription`
用的是 `localizedString`（见《Compose 界面架构》的 `MyAppTopAppBar` 示例），
即**通过资源读取而非硬编码**。本项目应同样处理，
尤其是「关于页」「设置页」这类含固定说明文本的界面。

---

### S30 🔴 界面层直接实例化数据源 Client（16 处），绕过 Repository

> **这是本轮最重要的发现**，也是官方文档里唯一被本项目**系统性违反**的
> 「强烈建议」条目——它不是 16 个孤立的坏味道，而是一个统一的架构缺口。

**位置**：`ui/screens/` 下 16 个 Screen 各自 `remember { XxxClient() }`：

| Screen | 行 | 直接实例化的数据源 |
| --- | --- | --- |
| `AdminScreen.kt` | 116 | `AdminClient()` |
| `ChannelAnalysisScreen.kt` | 104 | `ChannelAnalysisClient()` |
| `CrontabScreen.kt` | 86 | `CrontabClient()` |
| `FlashScreen.kt` | 120 | `FlashClient()` |
| `LedScreen.kt` | 165 | `LedClient()` |
| `LogsScreen.kt` | 118 | `LogsClient()` |
| `NetworkInterfacesScreen.kt` | 93 | `NetworkInterfacesClient()` |
| `NftablesScreen.kt` | 145、146 | `RoutesClient()` + `NftClient()` |
| `PackageManagerScreen.kt` | 113 | `PackageClient()` |
| `ProcessesScreen.kt` | 103 | `ProcessesClient()` |
| `RealtimeScreen.kt` | 107 | `RealtimeClient()` |
| `RoutesScreen.kt` | 102 | `RoutesClient()` |
| `StartupScreen.kt` | 97 | `StartupClient()` |
| `SystemScreen.kt` | 101 | `SystemClient()` |
| `WirelessScreen.kt` | 329 | `WirelessClient()` |

另有 **37 处界面层直接调用 `SshExec.` / `SshFiles.`**（其中 `FileManagerScreen` 一个文件
就占了大部分），同样属于「界面层直接操作数据源」。

**同时**：全项目**只有一个 ViewModel**（`RouterViewModel`），
且它**只被 `AppRoot` / `DashboardScreen` / `DevicesScreen` / `SettingsScreen` /
`TerminalScreen` 使用**；其余 16 个页面**没有 ViewModel**。
另：那个唯一的 `OpenWrtRepository` 也只被 `RouterViewModel` 用。

**官方依据**：官方《架构建议》中【**强烈建议**】：

> 「**使用代码库从数据层公开应用数据。** 确保界面层中的组件
> （如**可组合项**或 ViewModel）**不会直接与数据源交互**。
> 数据源示例包括：数据库、DataStore、SharedPreferences、Firebase API……」

同页还有一条【**强烈建议**】：

> 「**创建代码库，即使它们只包含一个数据源也不例外。**」

**为什么这是问题**（对照本项目实际）：

- **违反 SSOT**：官方要求每种数据「只有一个所有者，且只有它能修改该数据」。
  现在 `RealtimeClient()` 在 `RealtimeScreen` 里 `remember` 了一份实例，
  `RoutesClient()` 在 `RoutesScreen` 与 `NftablesScreen` **各 remember 了一份**——
  同一个数据源有两个互不相识的实例，任何缓存/连接复用都做不到。
- **违反 UDF 的职责分离**：官方要求「界面层的唯一职责是使用和显示界面状态」。
  现在 Screen 既在显示，又在**发起网络调用、处理异常、做超时决策**——
  所以才有 S23（13 处文案转换）和 S24（20 个超时常量）。
  **S23/S24 是 S30 的症状，不是独立的病**。
- **可测试性归零**：官方说「状态来源是独立的，因此可独立于界面进行测试」。
  现在要测「Logs 页加载失败时的行为」，必须先构造真实的 SSH 会话——
  这也是 S5（零测试）难做的根本原因。

**建议**（按官方口径，分三步，**不要求一次到位**）：

**第 1 步（成本最低，先把实例管起来）**：引入**手动依赖注入容器**
（官方对简单应用认可这条，见《架构指南》「处理依赖关系」）。
把 16 个 Client 收进一个容器，由它提供单例：

```kotlin
object AppContainer {
    val logs: LogsClient by lazy { LogsClient() }
    val routes: RoutesClient by lazy { RoutesClient() }
    // ...
}
```

这一步**不改分层**，只消灭「同一 Client 多个实例」，约 16 行改动。

**第 2 步（官方要求的核心）**：把 Client **升级为 Repository**（或在其上包一层）。
官方对「单数据源怎么办」有明确豁免：

> 「通常，如果存储库只包含单个数据源并且不依赖于其他存储库，
> 开发者会将**存储库和数据源的职责合并到存储库中**。」

即：**不必造一个只有转发作用、没有逻辑的空壳 Repository**。
本项目多数 Client 已含业务逻辑（解析 ubus 返回、拼 UCI 命令、校验固件），
**可以直接把 `LogsClient` 重命名/提升为 `LogsRepository`**，
由 ViewModel 持有、Screen 只读 `UiState`。

**第 3 步（收尾）**：为 16 个页面补 Screen 级 ViewModel，
按官方规范以 `uiState: StateFlow<XxxUiState>` 单一属性暴露状态，
界面用 `collectAsStateWithLifecycle()` 收集（官方【强烈建议】）。
此时 S23 的 13 处文案转换、S24 的 20 个超时常量、S27 的三态 UI
会**在归位过程中自然消失**。

**优先级说明**：S30 是**架构级改动**，工作量远大于 S22–S29，
但它**同时是那 8 项的上游**。因此本文件的推进顺序把它列为
「长期主线」，而把 S22–S29 列为「先做的低成本收敛」——
两者不是竞争关系：**先做 S22–S29 会让 S30 的每一步都更小、更安全**。
若精力有限，**S30 的「第 1 步」可立即执行**（16 行，零风险）。

---

## 五、本次已处理

| 项 | 内容 |
| --- | --- |
| S1 | 地址/端口/用户名/密码明文落盘 → Android Keystore AES-256-GCM 加密（`CredentialCipher.kt` + `SettingsStore.kt`） |
| S3 | 凭据经 Intent 跨 17 个 Activity 传递 → 只传设备 id + 进程内交接（`DeviceHandoff.kt` + 19 个 Activity + `AppRoot.kt`） |
| — | 依赖升级：`datastore-preferences 1.1.1 → 1.2.1`、`jsch 2.28.7 → 2.28.8`、Kotlin `2.4.20 → 2.4.21` |
| — | CI Gradle `9.8.0 → 9.8.1`；README 同步更新 |
| — | 顺带修正 `AppRoot` 里 `PackageManager` 误用 `FileManagerActivity.EXTRA_CONFIG` 的写法 |

---

## 六、建议的推进顺序

> **核心判断**：S22–S29 是**症状**，S30（界面层直连数据源）是**病根**。
> 但两者不冲突——**先做症状收敛，会让 S30 的每一步更小、更可验证**。
> 因此下面按「今天就能做的」→「需要成体系投入的」排列。

### 第 0 步：可立即执行（成本极低，当次即可完成）
0. **S30 第 1 步 —— 引入 `AppContainer` 手动 DI**：把 16 个 Client 收进一个容器，
   消灭「同一 Client 多个实例」（如 `RoutesClient` 被两个 Screen 各自 new）。
   约 16 行，不改分层、零风险。**官方对简单应用认可手动 DI**
1. **S21 / S19 / S20** —— 三处极小改动（1 行 / 3 行 / 4 行）

### 第 1 步：横切面收敛（一次改完，收益最大）
2. **S22 抽出 `shq()` 单一实现**（`data/ssh/ShellQuote.kt`）——
   消除 shell 注入的最大隐患面，可与 S11 同行处理
3. **S23 抽出错误文案** —— 顺带让 `RouterException.hint` 真正生效；
   但这只是权宜之计，**真正的归位在 S30 第 3 步**
4. **S25 抽出二级页 Activity 基类** —— 同时缩小 S6 的迁移面
5. **S24 统一超时档位** —— 与上一步同文件，一起做（官方：「类型负责其并发政策」）

### 第 2 步：安全 UI（都在改网络与编辑页，一次做完）
6. **S17 + S18 + S26** —— 明文 HTTP 提示、TLS 确认框、OkHttp 实例收敛
   （`DeviceEditScreen` 与 `UbusRpcClient` 一起动，避免重复改同一段）

### 第 3 步：工程基础
7. **S2 补 Gradle Wrapper** —— 零风险，是后续所有 CI 验证的前提
8. **S5 补测试** —— S1 加密/迁移、S3 设备交接、S22 转义、S23 文案映射
   （S30 做完后，「测 Screen 错误态」才真正可行——官方：「状态来源可独立于界面测试」）

### 第 4 步：架构归位主线（投入最大，官方「强烈建议」条目）
9. **S30 第 2 步 —— Client 提升/包装为 Repository**：官方允许「单数据源时
   把 Repository 与 DataSource 职责合并」，因此**不必造空壳转发层**
10. **S30 第 3 步 —— 为 16 个页面补 Screen 级 ViewModel**，
    以 `uiState: StateFlow<XxxUiState>` 暴露状态，界面用 `collectAsStateWithLifecycle()`
    → 此刻 S23 / S24 / S27 **会在归位中自然消解**
11. **S25 已铺路 → S6 迁往单 Activity + Navigation 3**
    （注意：官方 2026 年已推出 **Navigation 3**，当初「不原生支持双栏」的暂缓理由
    基于旧版组件，**值得重新评估**）

### 第 5 步：持续优化
12. **S13 / S14 / S15** —— 构建提速 + 版本集中 + 自动跟进
13. **S10 / S11** —— 低风险清理
14. **S9 / S27 / S28 / S29** —— 减包、UI 组件统一、文案入 `strings.xml`
15. **S7 / S8** —— 拆巨型文件与 i18n

---

## 七、对官方文档的引用索引

> 本文件的「统一管理」建议均以以下官方页面为依据，便于逐条复核。

| 官方页面 | 用在哪 |
| --- | --- |
| [应用架构指南](https://developer.android.google.cn/topic/architecture?hl=zh-cn) | 分层（UI/Data/Domain）、分离关注点、SSOT、UDF、「Activity 只应托管界面」 |
| [有关 Android 架构的建议](https://developer.android.google.cn/topic/architecture/recommendations?hl=zh-cn) | 全部「强烈建议/建议」优先级判定；Repository、单 Activity、Navigation 3、DI、测试 |
| [界面层](https://developer.android.google.cn/topic/architecture/ui-layer?hl=zh-cn) | `UiState` 三态、错误建模、`Message(id, message)`、UDF 事件流、线程与并发 |
| [数据层](https://developer.android.google.cn/topic/architecture/data-layer?hl=zh-cn) | Repository/DataSource 职责、「单数据源可合并职责」、数据源是「与数据系统之间的桥梁」 |
| [Compose 界面架构](https://developer.android.google.cn/develop/ui/compose/architecture?hl=zh-cn) | 密封类状态建模、可组合项参数最小化、`localizedString`、事件与状态提升 |
| [Navigation 3](https://developer.android.google.cn/guide/navigation/navigation-3?hl=zh-cn) | 单 Activity 目标架构；S6 重新评估的依据 |
| [使用 Hilt 实现依赖项注入](https://developer.android.google.cn/training/dependency-injection/hilt-android?hl=zh-cn) | S26/S30 的 DI 方案（Hilt 或手动 DI） |

> **注**：官方文档中的建议带有优先级标记（强烈建议 / 建议 / 可选）。
> 本文件标注「违反官方强烈建议」的条目只有两条：
> **界面层直连数据源（S30）** 与 **使用单 Activity（S25/S6 的终局）**。
> 其余条目属于「建议」或「可选」级别，按项目实际情况取舍即可。
