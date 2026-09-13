package com.mmckb.openwrtstatus.notify

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 实时消息通知的接入骨架，按 Android Live Updates 官方要求实现：
 * - 渠道重要性不得为 IMPORTANCE_MIN；
 * - Live Update 通知必须 ongoing、使用标准样式（这里用 ProgressStyle）、
 *   必须设置 contentTitle、通过 setRequestPromotedOngoing 请求系统提升；
 * - 清单声明 POST_PROMOTED_NOTIFICATIONS 非运行时权限。
 *
 * 目前没有任何功能触发通知；后续的路由器事件推送调用 [showLiveUpdate] 或 [show] 即可。
 */
object AppNotifier {

    const val CHANNEL_STATUS = "realtime_status"
    private const val PERMISSION_REQUEST_CODE = 1001

    /** 应用启动时调用：创建通知渠道（API 26+ 必需，重复创建无副作用）。 */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_STATUS,
                "实时消息",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "路由器实时状态与消息通知"
            }
            manager.createNotificationChannel(channel)
        }
    }

    /** 通知权限是否已授予（API 33 以下默认视为已授予）。 */
    fun permissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    /** Android 13+ 的运行时通知权限请求（当前未接入任何 UI，功能开启时调用）。 */
    fun requestPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33 && !permissionGranted(activity)) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST_CODE)
        }
    }

    /**
     * 系统是否愿意把该应用的通知提升为 Live Update（Android 16+，含用户开关）。
     * 不满足时 [showLiveUpdate] 会自动降级为普通 ongoing 通知。
     */
    fun canPostPromoted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 36 &&
            (context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() == true)

    /**
     * Live Update 通知（预留 API，当前无调用方）。
     * 满足官方硬性条件：ongoing、ProgressStyle 标准样式、contentTitle、请求提升。
     * [progress] 传 -1 表示不定进度；Android 16 以下自动降级为普通通知。
     */
    fun showLiveUpdate(
        context: Context,
        id: Int,
        title: String,
        text: String,
        progress: Int = -1
    ) {
        if (!permissionGranted(context)) return
        val builder = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setRequestPromotedOngoing(true)
            .setStyle(
                NotificationCompat.ProgressStyle()
                    .setProgress(if (progress >= 0) progress else 0)
                    .setProgressIndeterminate(progress < 0)
            )
        context.getSystemService(NotificationManager::class.java)?.notify(id, builder.build())
    }

    /**
     * 普通通知（预留 API，当前无调用方）；也可作为 Live Update 在旧系统上的降级路径。
     * [id] 用于覆盖同一条持续更新的消息。
     */
    fun show(context: Context, id: Int, title: String, text: String) {
        if (!permissionGranted(context)) return
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            NotificationCompat.Builder(context, CHANNEL_STATUS)
        } else {
            @Suppress("DEPRECATION")
            NotificationCompat.Builder(context)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }
}
