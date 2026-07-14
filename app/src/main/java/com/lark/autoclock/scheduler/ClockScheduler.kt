package com.lark.autoclock.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar
import kotlin.random.Random

object ClockScheduler {
    
    // 真实业务中，这些时间应该从 SharedPreferences 中读取
    private const val PREFS_NAME = "AutoClockPrefs"

    /**
     * 打卡闹钟的决策结果
     */
    enum class ClockAction {
        /** 正常下发精准闹钟 */
        SCHEDULE,
        /** 时序滞后，需立即补打卡 */
        COMPENSATE,
        /** 已超过补偿截止时间，跳过 */
        SKIP
    }

    /**
     * 纯函数：判定上班打卡应执行的动作。
     * @param scheduledTimeMillis 随机计算出的上班打卡闹钟时间
     * @param currentTimeMillis  当前真实系统时间
     * @return SCHEDULE=正常下发, COMPENSATE=立即补卡, SKIP=跳过
     *
     * 补偿截止线：当天 11:30。超过此时间认为补打上班卡已无意义。
     */
    fun resolveClockInAction(scheduledTimeMillis: Long, currentTimeMillis: Long): ClockAction {
        if (scheduledTimeMillis > currentTimeMillis) return ClockAction.SCHEDULE
        val limitCal = Calendar.getInstance().apply {
            timeInMillis = currentTimeMillis
            set(Calendar.HOUR_OF_DAY, 11)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return if (currentTimeMillis < limitCal.timeInMillis) ClockAction.COMPENSATE else ClockAction.SKIP
    }

    /**
     * 纯函数：判定下班打卡应执行的动作。
     * @param scheduledTimeMillis 随机计算出的下班打卡闹钟时间
     * @param currentTimeMillis  当前真实系统时间
     * @return SCHEDULE=正常下发, COMPENSATE=立即补卡, SKIP=跳过
     *
     * 补偿截止线：当天 22:00。超过此时间认为补打下班卡已无意义。
     */
    fun resolveClockOutAction(scheduledTimeMillis: Long, currentTimeMillis: Long): ClockAction {
        if (scheduledTimeMillis > currentTimeMillis) return ClockAction.SCHEDULE
        val limitCal = Calendar.getInstance().apply {
            timeInMillis = currentTimeMillis
            set(Calendar.HOUR_OF_DAY, 22)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return if (currentTimeMillis < limitCal.timeInMillis) ClockAction.COMPENSATE else ClockAction.SKIP
    }

    /**
     * 激活每天凌晨 00:30 的日程调度器
     */
    fun scheduleDailySetup(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DailySetupReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
        }
        
        // 如果今天已经过了凌晨 00:30，则安排在明天凌晨
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        }
        Log.d("AutoClock", "已激活每天凌晨 00:30 的日程调度器")
    }

    /**
     * 为今天计算并设置带有随机防作弊延迟的打卡精准闹钟
     */
    fun scheduleTodayClockActions(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 读取上班配置，默认 07:30 ~ 08:20
        val (mStartHour, mStartMin, mEndHour, mEndMin) = try {
            val s = (prefs.getString("morning_start", "07:30") ?: "07:30").split(":")
            val e = (prefs.getString("morning_end", "08:20") ?: "08:20").split(":")
            listOf(s[0].toInt(), s[1].toInt(), e[0].toInt(), e[1].toInt())
        } catch (ex: Exception) {
            Log.e("AutoClock", "解析上午时间配置失败，回退默认 07:30~08:20: ${ex.message}")
            listOf(7, 30, 8, 20)
        }

        // 计算上班随机偏移区间 (分钟)
        val mStartTotalMins = mStartHour * 60 + mStartMin
        val mEndTotalMins = mEndHour * 60 + mEndMin
        val mDiff = (mEndTotalMins - mStartTotalMins).coerceAtLeast(0)
        val clockInMinuteOffset = if (mDiff > 0) Random.nextInt(0, mDiff + 1) else 0

        val clockInCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, mStartHour)
            set(Calendar.MINUTE, mStartMin + clockInMinuteOffset)
            set(Calendar.SECOND, Random.nextInt(0, 60))
        }

        // 读取下班配置，默认 18:00 ~ 18:10
        val (aStartHour, aStartMin, aEndHour, aEndMin) = try {
            val s = (prefs.getString("afternoon_start", "18:00") ?: "18:00").split(":")
            val e = (prefs.getString("afternoon_end", "18:10") ?: "18:10").split(":")
            listOf(s[0].toInt(), s[1].toInt(), e[0].toInt(), e[1].toInt())
        } catch (ex: Exception) {
            Log.e("AutoClock", "解析下午时间配置失败，回退默认 18:00~18:10: ${ex.message}")
            listOf(18, 0, 18, 10)
        }

        val aStartTotalMins = aStartHour * 60 + aStartMin
        val aEndTotalMins = aEndHour * 60 + aEndMin
        val aDiff = (aEndTotalMins - aStartTotalMins).coerceAtLeast(0)
        val clockOutMinuteOffset = if (aDiff > 0) Random.nextInt(0, aDiff + 1) else 0

        val clockOutCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, aStartHour)
            set(Calendar.MINUTE, aStartMin + clockOutMinuteOffset)
            set(Calendar.SECOND, Random.nextInt(0, 60))
        }

        when (resolveClockInAction(clockInCal.timeInMillis, System.currentTimeMillis())) {
            ClockAction.SCHEDULE -> {
                setExactAlarm(context, alarmManager, 1001, clockInCal.timeInMillis)
                Log.d("AutoClock", "今天上班打卡已随机安排在: ${clockInCal.time}")
            }
            ClockAction.COMPENSATE -> {
                Log.w("AutoClock", "上班打卡随机时间已过，但在11:30之前，触发即时补打卡流程")
                triggerImmediateClock(context)
            }
            ClockAction.SKIP -> {
                Log.w("AutoClock", "上班打卡随机时间已过且超过11:30补偿截止线，跳过")
            }
        }

        when (resolveClockOutAction(clockOutCal.timeInMillis, System.currentTimeMillis())) {
            ClockAction.SCHEDULE -> {
                setExactAlarm(context, alarmManager, 1002, clockOutCal.timeInMillis)
                Log.d("AutoClock", "今天下班打卡已随机安排在: ${clockOutCal.time}")
            }
            ClockAction.COMPENSATE -> {
                Log.w("AutoClock", "下班打卡随机时间已过，但在22:00之前，触发即时补打卡流程")
                triggerImmediateClock(context)
            }
            ClockAction.SKIP -> {
                Log.w("AutoClock", "下班打卡随机时间已过且超过22:00补偿截止线，跳过")
            }
        }
    }

    private fun triggerImmediateClock(context: Context) {
        val intent = Intent(context, ClockActionReceiver::class.java)
        context.sendBroadcast(intent)
    }

    private fun setExactAlarm(context: Context, alarmManager: AlarmManager, requestCode: Int, timeInMillis: Long) {
        val intent = Intent(context, ClockActionReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.e("AutoClock", "未获得精确闹钟权限，无法设置定时任务！")
                // 这里可以通过广播或其他方式通知 UI，但由于是在后台/接收器中调用，仅做 log
                return
            }
        }

        // 申请并使用允许在 Doze 模式下唤醒设备的极高精度闹钟
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeInMillis, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, timeInMillis, pendingIntent)
        }
    }
}
