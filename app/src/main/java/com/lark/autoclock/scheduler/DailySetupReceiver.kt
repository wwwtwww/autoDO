package com.lark.autoclock.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lark.autoclock.utils.HolidayHelper

class DailySetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AutoClock", "触发凌晨 00:30 定时任务：正在判断节假日...")
        
        val pendingResult = goAsync()
        // 开启子线程执行网络请求
        Thread {
            try {
                val isWorkday = HolidayHelper.isTodayWorkday()
                if (isWorkday) {
                    Log.d("AutoClock", "API反馈今天是工作日，开始下发布置精准随机闹钟")
                    ClockScheduler.scheduleTodayClockActions(context)
                } else {
                    Log.d("AutoClock", "API反馈今天是休息日/节假日，跳过今天的打卡！")
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
