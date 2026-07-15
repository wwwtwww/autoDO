package com.lark.autoclock.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lark.autoclock.WakeActivity

class ClockActionReceiver : BroadcastReceiver() {
    companion object {
        const val WAKE_NOTIFICATION_ID = 10001
        var staticWakeLock: PowerManager.WakeLock? = null
        fun releaseWakeLock() {
            if (staticWakeLock?.isHeld == true) {
                try { staticWakeLock?.release() } catch (e: Exception) {}
            }
            staticWakeLock = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val clockType = intent.getStringExtra("CLOCK_TYPE") ?: "未知"
        Log.d("AutoClock", "=== ClockActionReceiver.onReceive 已执行！类型: $clockType ===")

        // ======== 第 1 层：最底层的 CPU + 屏幕 WakeLock（确保 CPU 不会在中途睡回去）========
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "autoDO::FullWakeLock"
        )
        wakeLock.acquire(60 * 1000L) // 持有 60 秒兜底
        releaseWakeLock() // 覆盖前先释放旧的 WakeLock，防止短时间内多次触发导致泄漏
        staticWakeLock = wakeLock
        Log.d("AutoClock", "WakeLock 已获取，屏幕应该已被点亮")

        // ======== 第 2 层：发送全屏通知（用于穿透锁屏启动 WakeActivity）========
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
            putExtra("CLOCK_TYPE", clockType)
        }

        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(context, 0, wakeIntent, pendingFlags)

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("autoDO 打卡触发")
            .setContentText("正在强制唤醒屏幕并执行打卡...")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)

        notificationManager.notify(WAKE_NOTIFICATION_ID, notificationBuilder.build())
        Log.d("AutoClock", "全屏通知已发送，ID: $WAKE_NOTIFICATION_ID")

        // ======== 第 3 层：best-effort 直接启动（Android 10+ / 部分 ROM 可能拦截，不能作为成功依据）========
        try {
            val directIntent = Intent(context, WakeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("CHAIN_ACTION", "ACTION_START_CLOCK_IN")
                putExtra("CLOCK_TYPE", clockType)
            }
            context.startActivity(directIntent)
            Log.d("AutoClock", "已尝试直接启动 WakeActivity（best-effort，实际可能被系统拦截）")
        } catch (e: Exception) {
            Log.e("AutoClock", "直接启动 WakeActivity 被拦截或失败，仅依赖全屏通知路径: ${e.message}")
        }
    }
}
