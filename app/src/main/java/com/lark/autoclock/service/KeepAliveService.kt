package com.lark.autoclock.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 前台保活服务（第二层保活选项）。
 *
 * 适用场景：部分极其严格的 Android 13+ 定制 ROM（如 HarmonyOS、深度定制 ColorOS）
 * 在长时间 Doze 模式下可能挂起无障碍服务进程。开启本服务后，系统会因前台通知的存在
 * 而维持较高的进程优先级，降低被挂起/墓碑化的风险。
 *
 * 用户可在 App 主界面手动开关此服务。
 */
class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "keepalive_channel"
        const val NOTIFICATION_ID = 10002
        private const val TAG = "KeepAliveService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("autoDO 守护进程")
            .setContentText("保活运行中，确保定时打卡不遗漏")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Log.d(TAG, "前台保活服务已启动")
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "保活通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "前台服务常驻通知，防止备用机深度休眠导致打卡遗漏"
                setShowBadge(false)
            }
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "前台保活服务已停止")
    }
}
