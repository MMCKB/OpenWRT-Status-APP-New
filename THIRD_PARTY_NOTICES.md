# 第三方组件与许可声明（Third-Party Notices）

本应用使用了以下开源组件。除特别说明外，相应许可文本全文位于 [`licenses/`](./licenses/) 目录，
再分发本应用（含以 APK 形式）时应一并提供本文件与对应许可文本。

---

## 一、随源码复制并修改的代码

### Kyant0/AndroidLiquidGlass（Backdrop）— Apache License 2.0

- 来源：<https://github.com/Kyant0/AndroidLiquidGlass>（tag `1.0.6`）
- 用途：底部悬浮 Tab 的液态玻璃效果（blur / vibrancy / lens / 高光 / 阴影）
- 修改声明：本项目复制了其 catalog（demo）模块的 4 个文件并做了适配——包名调整、
  尺寸参数改为固定宽度紧凑胶囊、点击回调改为可空以避免录制层拦截触摸。
  涉及文件（均保留来源与许可注释）：
  - `app/src/main/java/com/mmckb/openwrtstatus/ui/glass/DampedDragAnimation.kt`
  - `app/src/main/java/com/mmckb/openwrtstatus/ui/glass/DragGestureInspector.kt`
  - `app/src/main/java/com/mmckb/openwrtstatus/ui/glass/InteractiveHighlight.kt`
  - `app/src/main/java/com/mmckb/openwrtstatus/ui/glass/LiquidTab.kt`
- 许可文本：[licenses/apache-2.0.txt](./licenses/apache-2.0.txt)

> 说明：这些文件在上游位于 catalog（示例应用）模块，未随 `io.github.kyant0:backdrop` 构件发布，
> 因此无法以依赖方式引入，只能随源码复制并声明修改——这正是 Apache-2.0 第 4 条所要求的形式。

## 二、二进制依赖

### JSch（`com.github.mwiede:jsch:0.2.22`）— BSD-3-Clause

- 来源：<https://github.com/mwiede/jsch>（tag `jsch-0.2.22`）
- 用途：SSH 远程终端（PTY Shell）、读取 `/tmp/dhcp.leases` 获取 DHCP 租约
- 版权声明（BSD-3-Clause 要求二进制再分发时保留）：
  **Copyright (c) 2002-2015 Atsuhiko Yamanaka, JCraft,Inc. All rights reserved.**
- 许可文本全文：[licenses/bsd-3-clause.txt](./licenses/bsd-3-clause.txt)

### OkHttp（`com.squareup.okhttp3:okhttp:4.12.0`）— Apache License 2.0

- 来源：<https://github.com/square/okhttp>
- 用途：rpcd ubus JSON-RPC 的 HTTP 通信

### Jetpack Compose / AndroidX — Apache License 2.0

- 组件：`androidx.compose:compose-bom:2026.02.00`（ui / ui-graphics / material3 /
  material-icons-extended）、`androidx.activity:activity-compose:1.9.0`、
  `androidx.core:core-ktx:1.13.1`、`androidx.lifecycle:lifecycle-*:2.7.0`
- 来源：<https://developer.android.com/jetpack>

### Kotlin 及官方库 — Apache License 2.0

- 组件：`org.jetbrains.kotlin:*`（Kotlin 2.3.10）、`org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`、
  `org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3`

### Kyant0 Shapes（`io.github.kyant0:shapes:1.2.0`）— Apache License 2.0

- 来源：<https://github.com/Kyant0/AndroidLiquidGlass>
- 用途：`Capsule()` 等形状

## 三、义务摘要（非法律意见）

- **Apache-2.0**：随分发物附许可文本副本、保留版权与归属声明；对本项目复制并修改的
  Kyant0 代码，修改已在上述文件头中声明。
- **BSD-3-Clause**：源码与二进制形式的再分发均须保留 JSch 的版权声明与免责条款。
- 本文件仅为本项目的合规性说明，不构成法律意见。
