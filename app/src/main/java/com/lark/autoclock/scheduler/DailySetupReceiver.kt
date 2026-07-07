package com.lark.autoclock.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.lark.autoclock.utils.HolidayHelper

class DailySetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AutoClock", "触发凌晨 00:30 定时任务：正在判断节假日...")
        
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 递归注册明天的凌晨任务，实现连续的精确轮巡
                ClockScheduler.scheduleDailySetup(context)
                
                when (HolidayHelper.getTodayWorkdayStatus()) {
                    HolidayHelper.WorkdayStatus.WORKDAY -> {
                        Log.d("AutoClock", "API反馈今天是工作日，开始下发布置精准随机闹钟")
                        ClockScheduler.scheduleTodayClockActions(context)
                    }
                    HolidayHelper.WorkdayStatus.RESTDAY -> {
                        Log.d("AutoClock", "API反馈今天是休息日/节假日，跳过今天的打卡！")
                    }
                    HolidayHelper.WorkdayStatus.UNKNOWN -> {
                        Log.w("AutoClock", "无法确认节假日状态，安全降级为工作日并下发打卡闹钟")
                        ClockScheduler.scheduleTodayClockActions(context)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
