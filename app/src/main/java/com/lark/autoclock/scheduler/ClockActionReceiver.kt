package com.lark.autoclock.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lark.autoclock.service.AutoClockAccessibilityService
import com.lark.autoclock.utils.UnlockHelper

class ClockActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AutoClock", "到达真实打卡触发时间！立即唤醒屏幕并执行操作。")
        
        // 1. 强制点亮屏幕
        UnlockHelper.wakeUpScreen(context)
        
        // 2. 将动作下发给无障碍服务（链式执行：解锁屏幕 -> 进入飞书 -> 模拟点击）
        val serviceIntent = Intent(context, AutoClockAccessibilityService::class.java).apply {
            action = "ACTION_TEST_UNLOCK"
            putExtra("CHAIN_ACTION", "ACTION_START_CLOCK_IN")
        }
        context.startService(serviceIntent)
    }
}
