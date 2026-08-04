package com.lark.autoclock.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lark.autoclock.Constants

/**
 * 前台保活服务（第二层保活选项）。
 *
 * 适用场景：部分极其严格的 Android 13+ 定制 ROM（如 HarmonyOS、深度定制 ColorOS）
 * 在长时间 Doze 模式下可能挂起无障碍服务进程。开启本服务后，系统会因前台通知的存在
 * 而维持较高的进程优先级，降低被挂起/墓碑化的风险。
 *
 * 同时内置定时无障碍健康检测：每 15 分钟检查无障碍服务是否已被系统断连，
 * 若断连则发送高优先级告警通知提醒用户重新开启。
 *
 * 用户可在 App 主界面手动开关此服务。
 */
class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "keepalive_channel"
        const val NOTIFICATION_ID = 10002
        private const val TAG = "KeepAliveService"
    }

    private val handler = Handler(Looper.getMainLooper())

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            checkAccessibilityHealth()
            handler.postDelayed(this, Constants.HEALTH_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        handler.postDelayed(healthCheckRunnable, Constants.HEALTH_CHECK_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.d(TAG, "服务被系统重启 (START_STICKY)，恢复前台通知")
        }
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

    /**
     * 检查无障碍服务健康状态：
     * 若系统设置中已启用但 instance 为 null，说明服务被系统断连，发送告警通知。
     */
    private fun checkAccessibilityHealth() {
        val enabledInSettings = isAccessibilityEnabledInSettings()
        val instanceAlive = AutoClockAccessibilityService.instance != null
        if (enabledInSettings && !instanceAlive) {
            Log.w(TAG, "健康检测：无障碍在系统设置中已启用但 instance 为 null，尝试自动修复...")
            
            val autoHealed = com.lark.autoclock.utils.AccessibilityAutoEnableUtil.autoEnableAccessibilityService(this)
            
            if (autoHealed) {
                Log.i(TAG, "健康检测：已通过 ADB 权限成功拉起无障碍服务")
            } else {
                Log.w(TAG, "健康检测：无障碍自动拉起失败或未授权，发送断连告警通知")
                sendAccessibilityAlertNotification()
            }
        }
    }

    /**
     * 检查系统设置中是否已启用本应用的无障碍服务
     */
    private fun isAccessibilityEnabledInSettings(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val serviceName = packageName + "/" + AutoClockAccessibilityService::class.java.name
        return enabledServices?.split(":")?.any { it.trim() == serviceName } == true
    }

    /**
     * 发送高优先级告警通知，提醒用户无障碍服务已断连
     */
    private fun sendAccessibilityAlertNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val alertIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        } else {
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, alertIntent, pendingFlags
        )

        val alertNotification = NotificationCompat.Builder(this, Constants.CHANNEL_ID_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("无障碍服务已断连")
            .setContentText("autoDO 无法自动打卡，点击重新开启无障碍服务")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(Constants.ALERT_NOTIFICATION_ID, alertNotification)
        Log.d(TAG, "已发送无障碍断连告警通知")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val keepAliveChannel = NotificationChannel(
                CHANNEL_ID,
                "保活通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "前台服务常驻通知，防止备用机深度休眠导致打卡遗漏"
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                Constants.CHANNEL_ID_ALERT,
                "无障碍断连告警",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "无障碍服务被系统断连时的高优先级告警"
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(keepAliveChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(healthCheckRunnable)
        super.onDestroy()
        Log.d(TAG, "前台保活服务已停止")
    }
}
