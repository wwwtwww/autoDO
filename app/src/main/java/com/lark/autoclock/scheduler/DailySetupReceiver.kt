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
                
                var status = HolidayHelper.getTodayWorkdayStatus()
                if (status == HolidayHelper.WorkdayStatus.UNKNOWN) {
                    val calendar = java.util.Calendar.getInstance()
                    val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)
                    val isWeekend = dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY
                    status = if (isWeekend) {
                        Log.w("AutoClock", "无法确认节假日状态（网络异常），周末默认降级为休息日")
                        HolidayHelper.WorkdayStatus.RESTDAY
                    } else {
                        Log.w("AutoClock", "无法确认节假日状态（网络异常），工作日默认降级为上班日并下发闹钟")
                        HolidayHelper.WorkdayStatus.WORKDAY
                    }
                }
                
                when (status) {
                    HolidayHelper.WorkdayStatus.WORKDAY -> {
                        Log.d("AutoClock", "判定今天是工作日，开始下发布置精准随机闹钟")
                        ClockScheduler.scheduleTodayClockActions(context)
                    }
                    HolidayHelper.WorkdayStatus.RESTDAY -> {
                        Log.d("AutoClock", "判定今天是休息日/节假日，跳过今天的打卡！")
                    }
                    else -> {}
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
