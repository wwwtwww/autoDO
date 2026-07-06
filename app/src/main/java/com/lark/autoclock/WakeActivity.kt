package com.lark.autoclock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import com.lark.autoclock.service.AutoClockAccessibilityService

class WakeActivity : Activity() {
    private var wakeLock: PowerManager.WakeLock? = null

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
                    Log.e("WakeActivity", "锁屏消除失败")
                }
                override fun onDismissCancelled() {
                    Log.w("WakeActivity", "锁屏消除被取消")
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

        val chainAction = intent.getStringExtra("CHAIN_ACTION")
        Log.d("WakeActivity", "链式动作: $chainAction")

        window.decorView.postDelayed({
            if (chainAction == "ACTION_START_CLOCK_IN") {
                Log.d("WakeActivity", "正在触发飞书打卡流...")
                val serviceIntent = Intent(this, AutoClockAccessibilityService::class.java)
                serviceIntent.action = "ACTION_START_CLOCK_IN"
                startService(serviceIntent)
            }
            // 延迟释放 WakeLock 和关闭 Activity
            window.decorView.postDelayed({
                if (wakeLock?.isHeld == true) wakeLock?.release()
                finish()
            }, 3000)
        }, 2000) // 给系统足够时间完成亮屏和解锁动画
    }

    override fun onDestroy() {
        super.onDestroy()
        window.decorView.removeCallbacks(null)
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {
                Log.e("WakeActivity", "释放 WakeLock 异常: ${e.message}")
            }
        }
    }
}
