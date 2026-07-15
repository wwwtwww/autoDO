package com.lark.autoclock.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.lark.autoclock.utils.LocalScheduleManager

class DailySetupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AutoClock", "触发凌晨 00:30 定时任务：正在判断本地打卡周期与例外规则...")
        
        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                // 本块中所有操作均为同步调用（SharedPreferences 读取 + AlarmManager 注册），
                // 不使用 withTimeout 以防协程被中途取消导致闹钟注册不完整。
                // goAsync() 自身有系统级超时保护（约 10 秒），足够覆盖这些轻量操作。

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
                    else -> {
                        Log.w("AutoClock", "状态未知 (UNKNOWN)，安全降级为工作日，下发打卡闹钟以防漏打！")
                        ClockScheduler.scheduleTodayClockActions(context)
                    }
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
                // 确保所有同步 I/O 操作（AlarmManager 注册、SharedPreferences 读取）已执行完毕后，
                // 再调用 pendingResult.finish() 通知系统 Receiver 工作完成。
                // 避免系统提前降级进程优先级导致后续操作被挂起或回收。
                pendingResult.finish()
                scope.cancel()
            }
        }
    }
}
