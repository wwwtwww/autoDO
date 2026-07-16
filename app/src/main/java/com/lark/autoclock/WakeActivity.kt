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
import com.lark.autoclock.service.AutoClockAccessibilityService

class WakeActivity : Activity() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var keyguardDismissFailed = false
    private var isReceiverRegistered = false

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
                    keyguardDismissFailed = true
                    Log.e("WakeActivity", "锁屏消除失败，跳过自动打卡")
                }
                override fun onDismissCancelled() {
                    keyguardDismissFailed = true
                    Log.w("WakeActivity", "锁屏消除被取消，跳过自动打卡")
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
        wakeLock?.acquire(30 * 1000L)

        val chainAction = intent.getStringExtra(Constants.EXTRA_CHAIN_ACTION)
        Log.d("WakeActivity", "链式动作: $chainAction")

        mainHandler.postDelayed({
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguardDismissFailed || (keyguardManager.isKeyguardLocked && keyguardManager.isKeyguardSecure)) {
                Log.w("WakeActivity", "仍处于安全锁屏或解锁失败，终止本次自动打卡")
                releaseLocksAndFinish()
                return@postDelayed
            }

            if (chainAction == Constants.ACTION_START_CLOCK_IN) {
                val clockType = intent.getStringExtra(Constants.EXTRA_CLOCK_TYPE) ?: Constants.CLOCK_TYPE_UNKNOWN
                Log.d("WakeActivity", "正在触发飞书打卡流... 类型: $clockType")
                val service = AutoClockAccessibilityService.instance
                if (service != null) {
                    service.startClockIn(clockType)

                    // 注册广播监听服务结束
                    val filter = IntentFilter(Constants.ACTION_CLOCK_FINISHED)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        registerReceiver(finishReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        registerReceiver(finishReceiver, filter)
                    }
                    isReceiverRegistered = true
                } else {
                    Log.e("WakeActivity", "无障碍服务未连接（可能未在系统设置中开启），打卡流程无法执行")
                    releaseLocksAndFinish()
                    return@postDelayed
                }

                // 延迟释放 WakeLock 和关闭 Activity（兜底超时放宽到 20 秒）
                mainHandler.postDelayed({
                    Log.w("WakeActivity", "等待打卡广播超时 (20s)，触发兜底释放")
                    releaseLocksAndFinish()
                }, 20000)
            } else {
                releaseLocksAndFinish()
            }
        }, 2000) // 给系统足够时间完成亮屏和解锁动画
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

        val chainAction = intent.getStringExtra(Constants.EXTRA_CHAIN_ACTION)
        if (chainAction == Constants.ACTION_START_CLOCK_IN) {
            val clockType = intent.getStringExtra(Constants.EXTRA_CLOCK_TYPE) ?: Constants.CLOCK_TYPE_UNKNOWN
            val service = AutoClockAccessibilityService.instance
            if (service != null) {
                service.startClockIn(clockType)
                if (!isReceiverRegistered) {
                    val filter = IntentFilter(Constants.ACTION_CLOCK_FINISHED)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        registerReceiver(finishReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        registerReceiver(finishReceiver, filter)
                    }
                    isReceiverRegistered = true
                }
            } else {
                Log.e("WakeActivity", "无障碍服务未连接（可能未在系统设置中开启），打卡流程无法执行")
                releaseLocksAndFinish()
                return
            }

            mainHandler.postDelayed({
                Log.w("WakeActivity", "等待打卡广播超时 (20s)，触发兜底释放")
                releaseLocksAndFinish()
            }, 20000)
        } else {
            releaseLocksAndFinish()
        }
    }
}
