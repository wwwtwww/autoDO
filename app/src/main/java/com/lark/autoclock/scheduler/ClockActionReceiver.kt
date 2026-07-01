package com.lark.autoclock.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lark.autoclock.WakeActivity

class ClockActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AutoClock", "到达真实打卡触发时间！尝试利用全屏通知唤醒屏幕并执行操作。")

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "autoclock_channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "打卡唤醒通知", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "用于在后台点亮屏幕并触发打卡"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val wakeIntent = Intent(context, WakeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("CHAIN_ACTION", "ACTION_START_CLOCK_IN")
        }

        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(context, 0, wakeIntent, pendingFlags)

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("LarkAutoClock 打卡触发")
            .setContentText("正在强制唤醒屏幕并执行打卡...")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // 这是突破 Android 10+ 后台启动 Activity 限制的终极必杀技：全屏 Intent
            .setFullScreenIntent(fullScreenPendingIntent, true)

        notificationManager.notify(1001, notificationBuilder.build())
    }
}
