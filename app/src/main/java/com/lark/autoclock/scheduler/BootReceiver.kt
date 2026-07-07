package com.lark.autoclock.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("AutoClock", "设备已重启，触发今日任务检查并恢复明日调度")
            val checkIntent = Intent(context, DailySetupReceiver::class.java)
            context.sendBroadcast(checkIntent)
        }
    }
}
