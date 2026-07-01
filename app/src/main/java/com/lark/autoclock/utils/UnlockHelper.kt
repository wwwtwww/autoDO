package com.lark.autoclock.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.os.PowerManager
import android.util.Log

object UnlockHelper {

    private const val TAG = "UnlockHelper"
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 唤醒屏幕
     */
    fun wakeUpScreen(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) {
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "LarkAutoClock::WakeLock"
            )
            wakeLock?.acquire(3 * 60 * 1000L) // 锁定 3 分钟
            Log.d(TAG, "Screen woken up")
        } else {
            Log.d(TAG, "Screen is already on")
        }
    }

    /**
     * 释放屏幕唤醒锁
     */
    fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLock = null
            Log.d(TAG, "WakeLock released")
        }
    }

    /**
     * 判断设备是否处于锁屏状态
     */
    fun isDeviceLocked(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return keyguardManager.isKeyguardLocked
    }

    /**
     * 模拟向上滑动解锁（适用于免密/滑动解锁的情况）
     */
    fun swipeToUnlock(service: AccessibilityService) {
        val displayMetrics = service.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // 从屏幕底部 80% 处滑动到顶部 20% 处
        val startX = screenWidth / 2f
        val startY = screenHeight * 0.8f
        val endX = screenWidth / 2f
        val endY = screenHeight * 0.2f

        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        val gestureBuilder = GestureDescription.Builder()
        // 手势执行时间设为 300 毫秒，较自然地模拟人手滑动
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, 300)
        gestureBuilder.addStroke(strokeDescription)

        val result = service.dispatchGesture(
            gestureBuilder.build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Swipe gesture completed successfully")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Swipe gesture cancelled")
                }
            },
            null
        )
        Log.d(TAG, "Dispatch swipe gesture result: $result")
    }
}
