package com.lark.autoclock.scheduler

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lark.autoclock.Constants
import com.lark.autoclock.utils.AccessibilityAutoEnableUtil
import com.lark.autoclock.utils.AlarmPermissionWarner
import com.lark.autoclock.utils.LocalScheduleManager
import com.lark.autoclock.utils.ScheduleLogger
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * WorkManager 每日健康检查（异构兜底调度器）。
 *
 * 与 AlarmManager 互为异构冗余：本任务由系统 JobScheduler 框架驱动，
 * 即便凌晨主备双链闹钟都被 OEM 省电策略吞掉，本任务仍有机会在白天运行，
 * 重新武装调度链并按当前时间补齐打卡闹钟（错过随机时段会自动走 COMPENSATE 即时补打）。
 *
 * 定位说明：本任务不追求精准（Doze 下允许被系统推迟），只保证「每天至少获得一次修复机会」。
 */
class AlarmHealthCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            ScheduleLogger.log(applicationContext, "WorkManager 每日健康检查触发")

            // 1. 精确闹钟权限巡检：缺失则发高优先级告警通知（后续下发由系统决定豁免级别）
            AlarmPermissionWarner.warnIfExactAlarmDenied(applicationContext, "AlarmHealthCheckWorker")

            // 2. 重新武装凌晨主备调度链（幂等）
            ClockScheduler.scheduleDailySetup(applicationContext)

            // 3. 按今天状态补齐打卡闹钟；错过随机时段会自动进入补偿/即时补打流程
            when (LocalScheduleManager.getTodayWorkdayStatus(applicationContext)) {
                LocalScheduleManager.WorkdayStatus.WORKDAY ->
                    ClockScheduler.scheduleTodayClockActions(applicationContext)
                LocalScheduleManager.WorkdayStatus.RESTDAY ->
                    ClockScheduler.scheduleNextWorkdayClockInInAdvance(applicationContext)
                else ->
                    ClockScheduler.scheduleTodayClockActions(applicationContext)
            }

            // 4. 无障碍服务自愈（仅在已授予 WRITE_SECURE_SETTINGS 时尝试）
            if (AccessibilityAutoEnableUtil.hasWriteSecureSettingsPermission(applicationContext)) {
                AccessibilityAutoEnableUtil.autoEnableAccessibilityService(applicationContext)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("AutoClock", "每日健康检查任务异常: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        /**
         * 注册每日健康检查（KEEP 策略下重复调用为幂等 no-op）。
         * 首次运行对齐到下一个 04:00，避开 00:30/00:45 调度链与早晚打卡时段。
         */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<AlarmHealthCheckWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(computeDelayToNext(4, 0), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                Constants.WORK_NAME_ALARM_HEALTH_CHECK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        private fun computeDelayToNext(hour: Int, minute: Int): Long {
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (target.timeInMillis <= System.currentTimeMillis()) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis - System.currentTimeMillis()
        }
    }
}
