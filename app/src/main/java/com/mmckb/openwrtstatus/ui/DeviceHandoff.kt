package com.mmckb.openwrtstatus.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mmckb.openwrtstatus.data.local.SettingsStore
import com.mmckb.openwrtstatus.data.model.RouterConfig

/**
 * 活动设备的内存快照，由 [RouterViewModel] 在设备列表 / 活动设备变化时发布。
 *
 * 二级页优先用它取配置：内存快照与主界面同源，不存在「刚改完设备、DataStore 还没写完
 * 就被打开二级页」的竞态；快照缺失（进程刚重建）时退回 DataStore 读取。
 */
object DeviceSnapshot {

    data class Value(val devices: List<RouterConfig>, val activeId: String)

    @Volatile
    private var value: Value? = null

    fun publish(devices: List<RouterConfig>, activeId: String) {
        value = Value(devices, activeId)
    }

    fun get(): Value? = value
}

/** 二级页需要的设备上下文：目标设备本身，以及用于查重名的完整设备列表。 */
data class DeviceContext(
    val config: RouterConfig,
    val devices: List<RouterConfig>
)

/**
 * 按设备 id 读出设备上下文。
 *
 * 读取顺序：优先 [DeviceSnapshot]（与主界面同源、无竞态），快照缺失时退回 DataStore
 * （进程被杀后重建的场景——Intent 里的 id 仍在，因此仍能取到正确配置）。
 *
 * 非组合环境（如旋转交接要先拿到数据再 `finish()`）请直接调用本函数；组合环境用
 * [rememberDeviceContext]。
 *
 * @param deviceId 目标设备 id；为 null 表示「新增设备」。
 * @param fallbackToActive id 未命中时是否回落到活动设备。编辑新增页应传 false，
 *   否则会误把当前活动设备的内容填进新建表单。
 */
suspend fun loadDeviceContext(
    context: Context,
    deviceId: String?,
    fallbackToActive: Boolean = true
): DeviceContext {
    val snapshot = DeviceSnapshot.get()
    val devices: List<RouterConfig>
    val activeId: String
    if (snapshot != null) {
        devices = snapshot.devices
        activeId = snapshot.activeId
    } else {
        val store = SettingsStore(context.applicationContext)
        devices = store.loadDevices()
        activeId = store.loadActiveId(devices)
    }

    val matched = devices.firstOrNull { it.id == deviceId }
    val config = when {
        matched != null -> matched
        // 新增设备：不回落到活动设备，给一张空白表单。
        !fallbackToActive -> RouterConfig(id = deviceId.orEmpty())
        else -> devices.firstOrNull { it.id == activeId }
            ?: devices.firstOrNull()
            ?: RouterConfig()
    }
    return DeviceContext(config = config, devices = devices)
}

/**
 * 二级页按设备 id 读取设备上下文（组合版本）。
 *
 * 此前二级页通过 Intent 的 Serializable extra 收发 [RouterConfig]——里面装着明文地址、
 * 用户名与密码，每次进入二级页都会走一次 Binder 事务，把凭据短暂留在 system_server
 * 的 Intent 记录里。现在只传设备 id，页面自己取配置：凭据不再出现在任何 Intent。
 *
 * 读取在 [LaunchedEffect] 内进行（suspend + IO 调度），不阻塞首帧。
 *
 * @return 读取完成前返回 null，调用方应先渲染占位（首帧通常仅一帧空白）。
 */
@Composable
fun rememberDeviceContext(
    deviceId: String?,
    fallbackToActive: Boolean = true
): DeviceContext? {
    val context = LocalContext.current
    var state by remember(deviceId, fallbackToActive) { mutableStateOf<DeviceContext?>(null) }

    LaunchedEffect(deviceId, fallbackToActive) {
        state = loadDeviceContext(context, deviceId, fallbackToActive)
    }
    return state
}

/**
 * [rememberDeviceContext] 的便捷版本：只关心目标设备本身。
 */
@Composable
fun rememberDeviceConfig(
    deviceId: String?,
    fallbackToActive: Boolean = true
): RouterConfig? = rememberDeviceContext(deviceId, fallbackToActive)?.config

/**
 * 设备编辑页的回向结果。
 *
 * 保存后的 [RouterConfig] 里同样带着明文凭据，因此不经
 * [android.app.Activity.setResult] 返回——那部分数据由 system_server 持有。改由进程内
 * 持有者交接：编辑页写入后 `finish()`，主界面在 launcher 回调里取走。Intent 只承载
 * 不含凭据的标记位（`EXTRA_OPEN_INLINE`）。
 *
 * 线程约定：写入发生在编辑页主线程的 Compose 回调，读取发生在主界面主线程的
 * ActivityResult 回调，二者同线程；[consumeOutcome] 取走即清空，避免下次启动重复消费。
 */
object DeviceEditResult {

    /** 一次编辑操作的产出：保存或删除二选一。 */
    data class Outcome(
        val saved: RouterConfig? = null,
        val savedIsNew: Boolean = false,
        val deletedId: String? = null
    )

    /** 旋转到横屏时交回主界面继续内联编辑的设备，以及它是不是新建。 */
    data class Handoff(val device: RouterConfig, val isNew: Boolean)

    @Volatile
    private var outcome: Outcome? = null

    @Volatile
    private var handoff: Handoff? = null

    fun putSaved(device: RouterConfig, isNew: Boolean) {
        outcome = Outcome(saved = device, savedIsNew = isNew)
    }

    fun putDeleted(id: String) {
        outcome = Outcome(deletedId = id)
    }

    fun consumeOutcome(): Outcome? = outcome.also { outcome = null }

    fun putHandoff(device: RouterConfig, isNew: Boolean) {
        handoff = Handoff(device, isNew)
    }

    fun consumeHandoff(): Handoff? = handoff.also { handoff = null }
}
