package com.lark.autoclock.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("AutoClock", "设备已重启，恢复凌晨调度并触发今日任务检查")
            // 显式恢复凌晨调度闹钟，防止广播未被系统递送时调度链断裂
            ClockScheduler.scheduleDailySetup(context)
            val checkIntent = Intent(context, DailySetupReceiver::class.java)
            context.sendBroadcast(checkIntent)

            // 恢复前台保活服务（如果用户之前已开启）
            val prefs = context.getSharedPreferences(ClockScheduler.PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(ClockScheduler.KEY_KEEPALIVE_ENABLED, false)) {
                val keepAliveIntent = Intent(context, com.lark.autoclock.service.KeepAliveService::class.java)
                androidx.core.content.ContextCompat.startForegroundService(context, keepAliveIntent)
                Log.d("AutoClock", "已恢复前台保活服务")
            }
        }
    }
}
