# OpenWrt Status App — 问题清单（Dev 分支）

> 仓库：`https://github.com/MMCKB/OpenWRT-Status-APP-New` ｜ 分支：**Dev**
> 整理时间：2026-10-10
> 配套文档：《OpenWrt-Status-App-Dev-仓库分析与技术栈升级评估.md》（版本升级评估）
>
> **状态图例**：🔴 未修复 ｜ 🟡 部分缓解 ｜ 🟢 已修复

---

## 摘要

| 编号 | 级别 | 问题 | 状态 |
| --- | --- | --- | --- |
| S1 | 高 | 路由器/SSH 密码明文存 DataStore | 🟢 已修复 |
| S2 | 高 | 无 Gradle Wrapper，构建不可复现 | 🔴 |
| S3 | 中 | 解密后的凭据经 Intent 跨 17 个 Activity 传递 | 🔴 |
| S4 | 中 | 明文 HTTP + 信任用户证书 + 可选全信任 TLS | 🟡 设计取舍 |
| S5 | 中 | 零测试 | 🔴 |
| S6 | 中 | 20 个 Activity，无 Navigation Compose | 🔴 |
| S7 | 中 | 巨型单文件（最大 2409 行） | 🔴 |
| S8 | 中 | 无 i18n，约 1552 处硬编码中文 | 🔴 |
| S9 | 中 | `material-icons-extended` 被上游冻结 | 🔴 |
| S10 | 低 | 死代码 `LuciRpcClient.kt` | 🔴 |
| S11 | 低 | `UbusRpcClient.clients` 非线程安全 | 🔴 |
| S12 | 低 | 主线程 `runBlocking` 读 DataStore | 🔴 |
| S13 | 低 | 未开启 Gradle 配置缓存 | 🔴 |
| S14 | 低 | 无版本目录 `libs.versions.toml` | 🔴 |
| S15 | 低 | 无依赖自动更新（Dependabot/Renovate） | 🔴 |

---

## 一、安全与隐私

### S1 🟢 路由器凭据明文落盘 —— 已修复

**原问题**
`SettingsStore.toJson()` 把 `password` / `sshPassword` 以明文写进 `devices_json` 这个
DataStore 字符串。root 设备、`adb backup`、或任何能读到
`files/datastore/openwrt_status_prefs.preferences_pb` 的途径都能直读密码。

**修复方式**（新增 `data/local/CredentialCipher.kt`，改造 `SettingsStore.kt`）
- 密钥：Android Keystore 内生成 **AES-256-GCM**，别名 `openwrt_status_credentials_v1`，
  密钥材料不可导出；有 TEE 的设备上落在安全硬件里。
- 存储格式：`enc:v1:<base64(iv ‖ ciphertext ‖ gcmTag)>`，96 位 IV + 128 位认证标签。
- **只加密敏感字段**，地址/端口/用户名保持明文以便排错。
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

---

### S3 🔴 解密后的凭据经 Intent 跨 17 个 Activity 传递

**位置**：`RouterConfig` 实现 `java.io.Serializable`；`DeviceEditActivity` 等 17 处
`intent.getSerializableExtra(EXTRA_CONFIG) as? RouterConfig`

**现象**：每次进入二级页（无线、日志、路由表、防火墙…），**已解密的明文密码**
都会作为 Serializable extra 走一次 Binder 事务，短暂驻留在 system_server 的
Intent 记录里。

**风险评估**：所有二级 Activity 均为 `android:exported="false"`，**不存在跨应用泄露**。
属于「不必要的暴露面」而非可被利用的漏洞。S1 修复后，磁盘侧已安全，这里成为
仅存的明文驻留点。

**建议**：改为只传 `deviceId`，由各页面自行从 ViewModel / 仓库取配置；
或传一个剔除密码的 `RouterConfig` 副本，密码按需从 Keystore 解出。
改动面较大（17 个 Activity + 对应 Screen），建议与 S6（迁 Navigation）合并做。

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

### S6 🔴 20 个 Activity，无 Navigation Compose

**位置**：`MainActivity` + 19 个二级 Activity（About / ThemeSettings / FileManager /
PackageManager / Wireless / Admin / Led / Processes / Routes / Realtime / ChannelAnalysis /
Nftables / Logs / Flash / NetworkInterfaces / Crontab / Startup / System / DeviceEdit）；
`ui/AppRoot.kt` 1055 行，自研 Tab 状态机 + `movableContentOf` 保活。

**影响**
- 返回栈、状态恢复、深链全靠手写，每个页面都要重复 `setupEdgeToEdge()` 与
  `OpenWrtStatusTheme {}` 样板；
- 跨页共享状态只能靠进程级单例（`ThemePrefs`、`SshHostKeys`）；
- 也是 S3 的成因之一——正因为跨 Activity，才需要用 Intent 传配置。

**建议**：单 Activity + `androidx.navigation:navigation-compose`。
这是**架构级重构**，工作量最大，建议排在 S1/S2/S5 之后。

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

### S10 🔴 死代码 `LuciRpcClient.kt`

**位置**：`data/remote/LuciRpcClient.kt`（旧 luci-rpc CGI 客户端）

**证据**：全仓库 `grep -rn "LuciRpcClient"` 在自身文件之外**零引用**；
README 也说明该接口在 OpenWrt 19.07+ 已被移除、现用 ubus。

**建议**：直接删除。留着会让后来者误以为还有两条通信路径。

---

### S11 🔴 `UbusRpcClient.clients` 非线程安全

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

## 五、本次已处理

| 项 | 内容 |
| --- | --- |
| S1 | 凭据明文落盘 → Android Keystore AES-256-GCM 加密（`CredentialCipher.kt` + `SettingsStore.kt`） |
| — | 依赖升级：`datastore-preferences 1.1.1 → 1.2.1`、`jsch 2.28.7 → 2.28.8`、Kotlin `2.4.20 → 2.4.21` |
| — | CI Gradle `9.8.0 → 9.8.1`；README 同步更新 |

---

## 六、建议的推进顺序

1. **S2 补 Gradle Wrapper** —— 零风险，且是后续所有验证的前提（CI 可删掉手装步骤）
2. **S5 补测试** —— 为刚做完的 S1 加密与迁移逻辑加回归保护，性价比最高
3. **S13 / S14 / S15** —— 构建提速 + 版本集中管理 + 自动跟进
4. **S10 / S11** —— 两个低风险代码清理，顺手就做
5. **S9 迁出 material-icons-extended** —— 减包
6. **S3 消除 Intent 传密码** —— 建议与 S6 合并
7. **S6 / S7 / S8** —— 架构级重构与 i18n，长期投入
