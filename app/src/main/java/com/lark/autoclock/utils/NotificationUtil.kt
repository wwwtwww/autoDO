package com.lark.autoclock.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.lark.autoclock.Constants
import com.lark.autoclock.R

/**
 * 通知渠道统一管理工具。
 *
 * 将所有 NotificationChannel 的创建逻辑集中于此，避免 WakeActivity、
 * ClockActionReceiver、KeepAliveService 三处各自重复创建。
 * createNotificationChannel 是幂等操作，多次调用不会出错。
 */
object NotificationUtil {

    /**
     * 创建应用所需的全部通知渠道（幂等）。
     * 应在 Activity/Service/Receiver 初始化时调用。
     */
    fun createAllChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 打卡唤醒通知渠道（全屏 Intent 穿透锁屏）
        val wakeChannel = NotificationChannel(
            Constants.CHANNEL_ID_WAKE,
            context.getString(R.string.channel_name_wake),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_desc_wake)
        }

        // 无障碍断连告警渠道（高优先级提醒）
        val alertChannel = NotificationChannel(
            Constants.CHANNEL_ID_ALERT,
            context.getString(R.string.channel_name_alert),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_desc_alert)
        }

        // 保活通知渠道（低优先级常驻）
        val keepAliveChannel = NotificationChannel(
            KeepAliveChannelHelper.CHANNEL_ID,
            context.getString(R.string.channel_name_keepalive),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_desc_keepalive)
            setShowBadge(false)
        }

        notificationManager.createNotificationChannel(wakeChannel)
        notificationManager.createNotificationChannel(alertChannel)
        notificationManager.createNotificationChannel(keepAliveChannel)
    }
}

/**
 * KeepAliveService 的保活通知渠道 ID 常量辅助。
 * 独立存放以避免 NotificationUtil 反向依赖 KeepAliveService。
 */
object KeepAliveChannelHelper {
    const val CHANNEL_ID = "keepalive_channel"
}
