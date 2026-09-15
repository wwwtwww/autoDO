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
import com.lark.autoclock.Constants
import com.lark.autoclock.utils.LocalScheduleManager
import com.lark.autoclock.utils.ScheduleLogger

class DailySetupReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val source = intent.getStringExtra(Constants.EXTRA_ALARM_SOURCE) ?: Constants.ALARM_SOURCE_UNKNOWN
        Log.d("AutoClock", "触发凌晨调度任务（$source）：正在判断本地打卡周期与例外规则...")
        ScheduleLogger.log(context, "凌晨调度链触发（$source）")
        
        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                // 本块中所有操作均为同步调用（SharedPreferences 读取 + AlarmManager 注册），
                // 不使用 withTimeout 以防协程被中途取消导致闹钟注册不完整。
                // goAsync() 自身有系统级超时保护（约 30 秒），足够覆盖这些轻量操作。

                // 主链准时触发：先取消昨天武装的今天 00:45 备链（防 15 分钟后二次唤醒），
                // 随后 scheduleDailySetup 会将主备双链重新武装到明天。顺序不可颠倒！
                if (source == Constants.ALARM_SOURCE_DAILY_SETUP_MAIN) {
                    ClockScheduler.cancelBackupDailySetup(context)
                }

                // 递归注册明天的凌晨任务，实现连续的精确轮巡
                ClockScheduler.scheduleDailySetup(context)

                // 自我修复无障碍服务：如果在长待机中被系统强杀，利用 WRITE_SECURE_SETTINGS 权限将其拉起
                com.lark.autoclock.utils.AccessibilityAutoEnableUtil.autoEnableAccessibilityService(context)

                val status = LocalScheduleManager.getTodayWorkdayStatus(context)

                when (status) {
                    LocalScheduleManager.WorkdayStatus.WORKDAY -> {
                        Log.d("AutoClock", "判定今天是工作日/补班日，开始下发布置精准随机闹钟")
                        ClockScheduler.scheduleTodayClockActions(context)
                        ScheduleLogger.log(context, "今日判定: 工作日 → 已下发正式打卡闹钟")
                    }
                    LocalScheduleManager.WorkdayStatus.RESTDAY -> {
                        Log.d("AutoClock", "判定今天是休息日/节假日，跳过今天的打卡！预先下发最近未来工作日的打卡保底闹钟...")
                        ClockScheduler.scheduleNextWorkdayClockInInAdvance(context)
                        ScheduleLogger.log(context, "今日判定: 休息日 → 已预发最近工作日保底闹钟")
                    }
                    else -> {
                        Log.w("AutoClock", "状态未知 (UNKNOWN)，安全降级为工作日，下发打卡闹钟以防漏打！")
                        ClockScheduler.scheduleTodayClockActions(context)
                        ScheduleLogger.log(context, "今日判定: 未知 → 降级按工作日下发打卡闹钟")
                    }
                }
            } catch (e: Exception) {
                Log.e("AutoClock", "凌晨调度任务异常: ${e.message}", e)
                ScheduleLogger.log(context, "❌凌晨调度任务异常: ${e.message}")

                // Fail-Open 安全降级（宁多打勿漏打）：若崩溃源头就是 getTodayWorkdayStatus
                // （如 SharedPreferences 损坏/解析异常），降级路径不能再调用同一方法（必抛同样异常），
                // 直接按工作日兜底下发打卡闹钟。scheduleTodayClockActions 内含完成状态检查，不会重复打卡。
                try {
                    ClockScheduler.scheduleTodayClockActions(context)
                    ScheduleLogger.log(context, "凌晨调度异常后已按工作日兜底下发打卡闹钟")
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
