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
 * 通知基础设施 + 连接状态通知：
 * - 渠道重要性不得为 IMPORTANCE_MIN（Live Updates 硬性要求之一，保留兼容）；
 * - 清单声明 POST_NOTIFICATIONS / POST_PROMOTED_NOTIFICATIONS。
 *
 * 连接状态通知带动画：「已连接」使用系统计秒器实时走时显示连接时长，
 * 「未连接」为红色警示；状态切换时系统自动以过渡效果替换同 ID 通知。
 */
object AppNotifier {

    const val CHANNEL_STATUS = "realtime_status"
    const val ID_CONN_STATUS = 3001
    private const val PERMISSION_REQUEST_CODE = 1001

    /** 应用启动时调用：创建通知渠道（API 26+ 必需，重复创建无副作用）。 */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_STATUS,
                "连接状态",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "路由器连接与断开通知"
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

    /** Android 13+ 的运行时通知权限请求（设置页开关开启时调用）。 */
    fun requestPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33 && !permissionGranted(activity)) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST_CODE)
        }
    }

    /** 系统是否愿意把通知提升为 Live Update（Android 16+，含用户开关）。 */
    fun canPostPromoted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 36 &&
            (context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() == true)

    /**
     * 「路由器已连接」实时动态（Live Update）：ongoing + MetricStyle，
     * 连接时长用 TimeDifference 计秒器实时走时（动画），请求系统提升展示。
     */
    fun notifyConnected(context: Context, connectedSinceMillis: Long) {
        if (!permissionGranted(context)) return
        val style = NotificationCompat.MetricStyle().addMetric(
            NotificationCompat.Metric(
                NotificationCompat.Metric.TimeDifference.forStopwatch(
                    connectedSinceMillis,
                    NotificationCompat.Metric.TimeDifference.FORMAT_CHRONOMETER
                ),
                "连接时长"
            )
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(com.mmckb.openwrtstatus.R.drawable.ic_launcher_foreground)
            .setContentTitle("路由器已连接")
            .setContentText("连接正常")
            .setStyle(style)
            .setColor(0xFF4CAF50.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setRequestPromotedOngoing(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(ID_CONN_STATUS, notification)
    }

    /** 「路由器未连接」实时动态：红色强调，持续展示直至恢复连接（同 ID 替换）。 */
    fun notifyDisconnected(context: Context) {
        if (!permissionGranted(context)) return
        val style = NotificationCompat.MetricStyle().addMetric(
            NotificationCompat.Metric(
                NotificationCompat.Metric.FixedText("离线"),
                "状态"
            )
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(com.mmckb.openwrtstatus.R.drawable.ic_launcher_foreground)
            .setContentTitle("路由器未连接")
            .setContentText("无法访问路由器，请检查网络")
            .setStyle(style)
            .setColor(0xFFF44336.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setRequestPromotedOngoing(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(ID_CONN_STATUS, notification)
    }

    /** 撤回连接状态通知（关闭通知开关时调用）。 */
    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java)?.cancel(id)
    }
}
