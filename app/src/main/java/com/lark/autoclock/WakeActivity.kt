package com.lark.autoclock

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.lark.autoclock.R
import com.lark.autoclock.service.AutoClockAccessibilityService
import com.lark.autoclock.utils.NotificationUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WakeActivity : Activity() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isReceiverRegistered = false
    // 绑定 Activity 生命周期的 IO 协程作用域，用于异步化文件操作
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d("WakeActivity", "收到打卡完成广播，准备释放 WakeLock 并结束 Activity")
            releaseLocksAndFinish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("WakeActivity", "=== WakeActivity.onCreate 已执行！===")

        // ---------- 双重亮屏保障 ----------
        // 方式 A：使用 Android 8.1+ 新 API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    Log.d("WakeActivity", "锁屏已成功消除")
                }
                override fun onDismissError() {
                    Log.w("WakeActivity", "锁屏消除回调返回 error (非致命，免密/滑动锁屏将由 WindowManager Flags 自动穿透)")
                }
                override fun onDismissCancelled() {
                    Log.w("WakeActivity", "锁屏消除回调被取消 (非致命，处于 ColorOS 睡眠刚唤醒状态，系统底层 Flags 将继续解锁)")
                }
            })
        }
        // 方式 B：同时使用旧版 WindowManager Flags（兼容低版本 + 部分 ColorOS 仅认旧 Flag）
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )

        // 方式 C：再加一层 WakeLock（如果 ClockActionReceiver 的 WakeLock 被释放太快）
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "autoDO::WakeActivityLock"
        )
        wakeLock?.acquire(Constants.WAKELOCK_ACQUIRE_DURATION)

        val chainAction = intent.getStringExtra(Constants.EXTRA_CHAIN_ACTION)
        Log.d("WakeActivity", "链式动作: $chainAction")

        mainHandler.postDelayed({
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            // 仅当开启了有密码/图案的安全锁屏 (isKeyguardSecure == true) 且仍处于锁屏时才终止。
            // 对于滑动解锁/免密锁屏 (isKeyguardSecure == false)，WindowManager Flags 能成功自动穿透，绝中途强行退出！
            if (keyguardManager.isKeyguardLocked && keyguardManager.isKeyguardSecure) {
                Log.w("WakeActivity", "检测到处于安全密码锁屏，无密码辅助无法自动穿透，终止本次自动打卡")
                releaseLocksAndFinish()
                return@postDelayed
            }

            if (chainAction == Constants.ACTION_START_CLOCK_IN) {
                val clockType = intent.getStringExtra(Constants.EXTRA_CLOCK_TYPE) ?: Constants.CLOCK_TYPE_UNKNOWN
                Log.d("WakeActivity", "正在触发飞书打卡流... 类型: $clockType")
                // 再次尝试请求系统解锁（针对 ColorOS / realme UI 从极深 Doze 刚亮屏后的补唤）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && keyguardManager.isKeyguardLocked) {
                    try {
                        keyguardManager.requestDismissKeyguard(this, null)
                    } catch (e: Exception) {
                        Log.w("WakeActivity", "二次 requestDismissKeyguard 异常: ${e.message}")
                    }
                }
                // 兜底超时从首次调用开始计时，不受重试影响
                scheduleFallbackTimeout()
                tryStartClockInWithRetry(clockType)
            } else {
                releaseLocksAndFinish()
            }
        }, 2000) // 给系统足够时间完成亮屏和解锁动画
    }

    /**
     * 带重试的打卡启动：当无障碍 instance 为 null 时，每 3 秒重试一次，最多 3 次。
     * 覆盖系统异步重绑无障碍服务的场景。全部重试失败后记录日志并释放资源。
     */
    private fun tryStartClockInWithRetry(clockType: String, attempt: Int = 0) {
        val service = AutoClockAccessibilityService.instance
        if (service != null) {
            service.startClockIn(clockType)
            registerFinishReceiverIfNeeded()
        } else if (attempt < Constants.ACCESSIBILITY_RETRY_COUNT) {
            Log.w("WakeActivity", "无障碍服务未连接，尝试自动修复并进行第 ${attempt + 1}/${Constants.ACCESSIBILITY_RETRY_COUNT} 次重试...")
            
            // 如果无障碍未连接，且已授权了 WRITE_SECURE_SETTINGS，尝试自动把它拉起来
            com.lark.autoclock.utils.AccessibilityAutoEnableUtil.autoEnableAccessibilityService(this)

            mainHandler.postDelayed({
                tryStartClockInWithRetry(clockType, attempt + 1)
            }, Constants.ACCESSIBILITY_RETRY_INTERVAL_MS)
        } else {
            Log.e("WakeActivity", "无障碍服务经过 ${Constants.ACCESSIBILITY_RETRY_COUNT} 次重试仍未连接，打卡失败")
            recordAccessibilityFailure(clockType)
            sendAccessibilityAlertNotification(clockType)
            releaseLocksAndFinish()
        }
    }

    /**
     * 注册打卡完成广播监听器（如未注册）
     */
    private fun registerFinishReceiverIfNeeded() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter(Constants.ACTION_CLOCK_FINISHED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(finishReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(finishReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    /**
     * 调度兜底超时释放
     */
    private fun scheduleFallbackTimeout() {
        mainHandler.postDelayed({
            Log.w("WakeActivity", "等待打卡广播超时 (${Constants.TIMEOUT_WAKE_ACTIVITY_FALLBACK/1000}s)，触发兜底释放")
            releaseLocksAndFinish()
        }, Constants.TIMEOUT_WAKE_ACTIVITY_FALLBACK)
    }

    /**
     * 将无障碍断连导致的打卡失败写入 clock_log.txt，与正常打卡日志格式一致
     */
    private fun recordAccessibilityFailure(clockType: String) {
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val logLine = "[$timeStr] [$clockType] ❌无障碍断连 - 无障碍服务未连接，打卡未执行\n"

        ioScope.launch {
            try {
                val logFile = File(filesDir, "clock_log.txt")
                logFile.appendText(logLine)

                // 限制文件行数，保留最近 200 行防止无限膨胀
                val lines = logFile.readLines()
                if (lines.size > 250) {
                    logFile.writeText(lines.takeLast(200).joinToString("\n") + "\n")
                }
            } catch (e: Exception) {
                Log.e("WakeActivity", "写入失败日志异常: ${e.message}")
            }
        }
    }

    /**
     * 发送高优先级告警通知，提醒用户无障碍服务已断连导致打卡失败
     */
    private fun sendAccessibilityAlertNotification(clockType: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 通过共享工具幂等创建所有通知渠道
        NotificationUtil.createAllChannels(this)

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
            .setContentTitle(getString(R.string.notif_alert_title))
            .setContentText(getString(R.string.notif_alert_text, clockType))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(Constants.ALERT_NOTIFICATION_ID, alertNotification)
        Log.d("WakeActivity", "已发送打卡失败无障碍断连告警通知")
    }

    private fun releaseLocksAndFinish() {
        if (isFinishing) return
        com.lark.autoclock.scheduler.ClockActionReceiver.releaseWakeLock()
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {
                Log.e("WakeActivity", "释放 WakeLock 异常: ${e.message}")
            }
        }
        
        // 精准清除打卡唤醒通知，防止常驻通知栏
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(com.lark.autoclock.scheduler.ClockActionReceiver.WAKE_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e("WakeActivity", "清除唤醒通知失败: ${e.message}")
        }
        
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel() // 取消所有挂起的 IO 协程，防止 Activity 销毁后写入
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(finishReceiver)
            } catch (e: Exception) {
                Log.e("WakeActivity", "反注册广播异常: ${e.message}")
            }
            isReceiverRegistered = false
        }
        mainHandler.removeCallbacksAndMessages(null)
        com.lark.autoclock.scheduler.ClockActionReceiver.releaseWakeLock()
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {
                Log.e("WakeActivity", "释放 WakeLock 异常: ${e.message}")
            }
        }
        // 兜底清除唤醒通知，防止系统强杀时 releaseLocksAndFinish 未执行完毕导致通知残留
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(com.lark.autoclock.scheduler.ClockActionReceiver.WAKE_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e("WakeActivity", "onDestroy 清除唤醒通知异常: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isFinishing) return

        Log.d("WakeActivity", "onNewIntent: 收到新 Intent，重置并重新触发打卡流程")
        mainHandler.removeCallbacksAndMessages(null)

        // 反注册旧的 finishReceiver，防止上一个流程的残留广播中断新流程
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(finishReceiver)
            } catch (e: Exception) {
                Log.e("WakeActivity", "onNewIntent 反注册旧广播异常: ${e.message}")
            }
            isReceiverRegistered = false
        }

        val chainAction = intent.getStringExtra(Constants.EXTRA_CHAIN_ACTION)
        if (chainAction == Constants.ACTION_START_CLOCK_IN) {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguardManager.isKeyguardLocked && keyguardManager.isKeyguardSecure) {
                Log.w("WakeActivity", "onNewIntent: 仍处于安全密码锁屏，终止本次自动打卡")
                releaseLocksAndFinish()
                return
            }
            // 延迟 1 秒等待屏幕亮起后再启动打卡（与 onCreate 中的 2s 延迟同理）
            mainHandler.postDelayed({
                if (isFinishing) return@postDelayed
                val clockType = intent.getStringExtra(Constants.EXTRA_CLOCK_TYPE) ?: Constants.CLOCK_TYPE_UNKNOWN
                scheduleFallbackTimeout()
                tryStartClockInWithRetry(clockType)
            }, 1000)
        } else {
            releaseLocksAndFinish()
        }
    }
}
