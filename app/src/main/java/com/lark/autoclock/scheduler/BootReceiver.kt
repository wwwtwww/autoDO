package com.lark.autoclock.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("AutoClock", "设备已重启，重新调度打卡任务")
            ClockScheduler.scheduleDailySetup(context)
            // 顺便触发一次今日任务检查
            val checkIntent = Intent(context, DailySetupReceiver::class.java)
            context.sendBroadcast(checkIntent)
        }
    }
}
