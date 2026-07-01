package com.lark.autoclock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.lark.autoclock.service.AutoClockAccessibilityService

class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("WakeActivity", "正在通过 Activity 唤醒屏幕并请求解锁...")

        // 使用 Android 官方 API 亮屏并解锁（适用于无密码或滑动解锁）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        val chainAction = intent.getStringExtra("CHAIN_ACTION")
        
        // 延时 1 秒，等待系统解锁动画完成，然后启动后续动作并关闭透明 Activity
        window.decorView.postDelayed({
            if (chainAction == "ACTION_START_CLOCK_IN") {
                val serviceIntent = Intent(this, AutoClockAccessibilityService::class.java)
                serviceIntent.action = "ACTION_START_CLOCK_IN"
                startService(serviceIntent)
            }
            finish()
        }, 1500)
    }
}
