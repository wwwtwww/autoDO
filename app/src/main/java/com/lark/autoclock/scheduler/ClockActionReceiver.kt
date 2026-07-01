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
        
        // 1. 强制点亮屏幕并消除滑动锁
        val wakeIntent = Intent(context, com.lark.autoclock.WakeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("CHAIN_ACTION", "ACTION_START_CLOCK_IN")
        }
        context.startActivity(wakeIntent)
    }
}
