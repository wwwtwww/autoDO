package com.lark.autoclock.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.lark.autoclock.utils.LocalScheduleManager

class DailySetupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AutoClock", "触发凌晨 00:30 定时任务：正在判断本地打卡周期与例外规则...")
        
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 递归注册明天的凌晨任务，实现连续的精确轮巡
                ClockScheduler.scheduleDailySetup(context)
                
                val status = LocalScheduleManager.getTodayWorkdayStatus(context)
                
                when (status) {
                    LocalScheduleManager.WorkdayStatus.WORKDAY -> {
                        Log.d("AutoClock", "判定今天是工作日/补班日，开始下发布置精准随机闹钟")
                        ClockScheduler.scheduleTodayClockActions(context)
                    }
                    LocalScheduleManager.WorkdayStatus.RESTDAY -> {
                        Log.d("AutoClock", "判定今天是休息日/节假日，跳过今天的打卡！")
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.e("AutoClock", "凌晨调度任务异常: ${e.message}", e)
                
                // 安全降级：如果判断逻辑本身崩溃，尽量兜底
                try {
                    val status = LocalScheduleManager.getTodayWorkdayStatus(context)
                    if (status == LocalScheduleManager.WorkdayStatus.WORKDAY) {
                        ClockScheduler.scheduleTodayClockActions(context)
                    }
                } catch (fallbackEx: Exception) {
                    Log.e("AutoClock", "降级补发打卡闹钟也失败: ${fallbackEx.message}", fallbackEx)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
