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
 * 实时消息通知的接入骨架：渠道在应用启动时创建，权限请求与展示 API 预留于此。
 * 目前没有任何功能触发通知；后续的路由器事件推送直接调用 [show] 即可。
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
     * 在 [CHANNEL_STATUS] 渠道上展示一条通知（预留 API，当前无调用方）。
     * [id] 用于覆盖同一条持续更新的消息（如实时状态）。
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
