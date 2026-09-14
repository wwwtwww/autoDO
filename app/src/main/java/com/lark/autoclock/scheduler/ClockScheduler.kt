package com.lark.autoclock.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import com.lark.autoclock.Constants
import com.lark.autoclock.utils.AlarmPermissionWarner
import com.lark.autoclock.utils.ScheduleLogger

object ClockScheduler {
    
    // 共享 SharedPreferences 常量，供 BootReceiver / MainActivity 等组件统一引用
    const val PREFS_NAME = "AutoClockPrefs"
    const val KEY_KEEPALIVE_ENABLED = "keepalive_enabled"
    // 打卡完成状态位（按日期隔离，次日自动失效），用于防重复打卡
    const val KEY_COMPLETED_CLOCK_IN_DATE = "completed_clock_in_date"
    const val KEY_COMPLETED_CLOCK_OUT_DATE = "completed_clock_out_date"

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
     * 记录某班次今天已确认打卡成功（防重复打卡状态位，按 yyyy-MM-dd 日期隔离，次日自动失效）。
     * 仅在无障碍服务检测到明确成功文字后调用；「⚠️未确认」不打标记，以免阻断未确认重试。
     */
    fun markClockCompleted(context: Context, clockType: String) {
        val key = when (clockType) {
            Constants.CLOCK_TYPE_CLOCK_IN -> KEY_COMPLETED_CLOCK_IN_DATE
            Constants.CLOCK_TYPE_CLOCK_OUT -> KEY_COMPLETED_CLOCK_OUT_DATE
            else -> return // 测试卡等非正式类型不记录
        }
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(key, dateStr).apply()
    }

    /**
     * 判断某班次今天是否已确认打卡成功。
     * 用于拦截重复排班/补偿：WorkManager 健康检查、开机广播等白天乱序入口调用
     * scheduleTodayClockActions 时，已完成班次不再走 COMPENSATE 补打，防止二次打卡。
     */
    fun isClockAlreadyCompletedToday(context: Context, clockType: String): Boolean {
        val key = when (clockType) {
            Constants.CLOCK_TYPE_CLOCK_IN -> KEY_COMPLETED_CLOCK_IN_DATE
            Constants.CLOCK_TYPE_CLOCK_OUT -> KEY_COMPLETED_CLOCK_OUT_DATE
            else -> return false
        }
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, "") == dateStr
    }

    /**
     * 激活每天凌晨的日程调度器（主链 00:30 + 备链 00:45 双链互备）。
     *
     * 历史教训：单链 00:30 闹钟一旦被 Doze/OEM 省电策略吞掉，整条调度链静默断裂
     * （曾导致周末断链后周一漏卡）。备链与主链严格配对（主链时刻 +15 分钟），
     * 仅当主链未触发时才接管；主链成功执行后会取消当次备链（见 cancelBackupDailySetup），
     * 避免健康夜晚备链二次唤醒、重复随机排班。
     */
    fun scheduleDailySetup(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (AlarmPermissionWarner.warnIfExactAlarmDenied(context, "scheduleDailySetup")) return

        val mainAt = nextDailySetupTime()
        // 备链与主链配对：永远是「下一次主链 +15 分钟」，而非「今天 00:45」
        val backupAt = computeBackupSetupTime(mainAt)

        setDailySetupAlarm(context, alarmManager, Constants.REQUEST_CODE_DAILY_SETUP_MAIN, mainAt, Constants.ALARM_SOURCE_DAILY_SETUP_MAIN)
        setDailySetupAlarm(context, alarmManager, Constants.REQUEST_CODE_DAILY_SETUP_BACKUP, backupAt, Constants.ALARM_SOURCE_DAILY_SETUP_BACKUP)
        ScheduleLogger.log(context, "凌晨调度链已武装: 主链 ${formatTime(mainAt)} / 备链 ${formatTime(backupAt)}")
    }

    /**
     * 取消待触发的凌晨备链闹钟（requestCode 5000）。
     * 主链准时执行成功后、且必须在重新武装之前调用（先取消、后武装），
     * 防止昨天武装的今天备链在 15 分钟后二次唤醒并重复随机排班。
     * 注意：PendingIntent 匹配只看 component+requestCode，不带 extras 也能命中已武装的备链。
     */
    fun cancelBackupDailySetup(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DailySetupReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, Constants.REQUEST_CODE_DAILY_SETUP_BACKUP, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let {
            alarmManager.cancel(it)
            Log.d("AutoClock", "主链已成功执行，已取消今天的备链闹钟（防二次唤醒）")
        }
    }

    /** 计算下一个凌晨 00:30 调度时刻（基于当前系统时间） */
    private fun nextDailySetupTime(): Long = computeNextDailySetupTime(System.currentTimeMillis())

    /**
     * 纯函数：计算给定时刻之后的下一个凌晨 00:30 时间戳。
     * 边界语义：恰好等于 00:30:00.000 也顺延到明天（闹钟已到期，重排毫无意义）。
     */
    fun computeNextDailySetupTime(now: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    /** 纯函数：备链时刻 = 主链时刻 + 固定偏移（保证主备严格配对到同一天） */
    fun computeBackupSetupTime(mainAt: Long): Long =
        mainAt + Constants.BACKUP_DAILY_SETUP_OFFSET_MINUTES * 60_000L

    private fun setDailySetupAlarm(context: Context, alarmManager: AlarmManager, requestCode: Int, triggerAt: Long, source: String) {
        val intent = Intent(context, DailySetupReceiver::class.java).apply {
            putExtra(Constants.EXTRA_ALARM_SOURCE, source)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val showIntent = Intent(context, com.lark.autoclock.MainActivity::class.java)
            val showPendingIntent = PendingIntent.getActivity(
                context, requestCode, showIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showPendingIntent), pendingIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun formatTime(timeInMillis: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timeInMillis))

    /**
     * 当今天为休息日/周末时，预先计算并下发最近未来工作日的上班+下班打卡保底闹钟。
     * 防止手机在周末长时间（54+小时）熄屏休眠导致系统冻结进程或错过凌晨调度。
     */
    fun scheduleNextWorkdayClockInInAdvance(context: Context) {
        scheduleNextWorkdayAlarms(context, includeClockIn = true, includeClockOut = true)
    }

    /**
     * 滚动保底的覆盖范围决策（纯函数，可单元测试）。
     * 关键不变量：上班卡闭环时 includeClockOut 必须为 false——
     * 今天的下班闹钟（requestCode 1002）尚未触发，绝不能被提前覆盖。
     */
    enum class FallbackScope(val includeClockIn: Boolean, val includeClockOut: Boolean) {
        /** 仅补下一工作日上班保底（上班卡闭环后） */
        CLOCK_IN_ONLY(true, false),
        /** 补下一工作日上班+下班保底（下班卡闭环后） */
        BOTH(true, true)
    }

    /** 纯函数：根据打卡类型判定滚动保底覆盖范围；非正式类型（测试等）返回 null 表示不布防 */
    fun resolveRollingFallbackScope(clockType: String): FallbackScope? = when (clockType) {
        Constants.CLOCK_TYPE_CLOCK_IN -> FallbackScope.CLOCK_IN_ONLY
        Constants.CLOCK_TYPE_CLOCK_OUT -> FallbackScope.BOTH
        else -> null
    }

    /**
     * 滚动保底：每次打卡流程闭环后（无论已确认/未确认/拉起失败）立即为「下一个工作日」下发保底闹钟，
     * 将无闹钟保护的空窗期从最长约 63 小时（周五傍晚→周一凌晨）压缩到 24 小时以内。
     *
     * 注意 requestCode 占用语义（1001=上班 / 1002=下班）：
     * - 上班卡闭环后：今天的下班闹钟（1002）尚未触发，绝不能覆盖，只补下一工作日的上班保底；
     * - 下班卡闭环后：今天两个闹钟均已触发，可安全补下一工作日的上班+下班保底。
     */
    fun scheduleRollingFallbackAfterClock(context: Context, clockType: String) {
        val scope = resolveRollingFallbackScope(clockType) ?: return // 测试卡等非正式类型不触发保底
        scheduleNextWorkdayAlarms(context, scope.includeClockIn, scope.includeClockOut)
    }

    private fun scheduleNextWorkdayAlarms(context: Context, includeClockIn: Boolean, includeClockOut: Boolean) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (AlarmPermissionWarner.warnIfExactAlarmDenied(context, "scheduleNextWorkdayAlarms")) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val (mStartHour, mStartMin, mEndHour, mEndMin) = try {
            val s = (prefs.getString("morning_start", Constants.DEFAULT_MORNING_START) ?: Constants.DEFAULT_MORNING_START).split(":")
            val e = (prefs.getString("morning_end", Constants.DEFAULT_MORNING_END) ?: Constants.DEFAULT_MORNING_END).split(":")
            listOf(s[0].toInt(), s[1].toInt(), e[0].toInt(), e[1].toInt())
        } catch (ex: Exception) {
            listOf(7, 30, 8, 20)
        }

        val mStartTotalMins = mStartHour * 60 + mStartMin
        val mEndTotalMins = mEndHour * 60 + mEndMin
        val mDiff = (mEndTotalMins - mStartTotalMins).coerceAtLeast(0)

        // 读取下班配置，默认 18:00 ~ 18:10
        val (aStartHour, aStartMin, aEndHour, aEndMin) = try {
            val s = (prefs.getString("afternoon_start", Constants.DEFAULT_AFTERNOON_START) ?: Constants.DEFAULT_AFTERNOON_START).split(":")
            val e = (prefs.getString("afternoon_end", Constants.DEFAULT_AFTERNOON_END) ?: Constants.DEFAULT_AFTERNOON_END).split(":")
            listOf(s[0].toInt(), s[1].toInt(), e[0].toInt(), e[1].toInt())
        } catch (ex: Exception) {
            listOf(18, 0, 18, 10)
        }

        val aStartTotalMins = aStartHour * 60 + aStartMin
        val aEndTotalMins = aEndHour * 60 + aEndMin
        val aDiff = (aEndTotalMins - aStartTotalMins).coerceAtLeast(0)

        val calendar = Calendar.getInstance()
        // 从明天开始搜索未来最多 7 天
        for (i in 1..7) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            val status = com.lark.autoclock.utils.LocalScheduleManager.getWorkdayStatusForCalendar(context, calendar)
            if (status == com.lark.autoclock.utils.LocalScheduleManager.WorkdayStatus.WORKDAY) {
                if (includeClockIn) {
                    // 上班打卡保底闹钟（使用 clone 避免修改循环中的 calendar）
                    val clockInMinuteOffset = if (mDiff > 0) Random.nextInt(0, mDiff + 1) else 0
                    val clockInCal = (calendar.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, mStartHour)
                        set(Calendar.MINUTE, mStartMin + clockInMinuteOffset)
                        set(Calendar.SECOND, Random.nextInt(0, 60))
                        set(Calendar.MILLISECOND, 0)
                    }
                    setExactAlarm(context, alarmManager, 1001, clockInCal.timeInMillis, Constants.CLOCK_TYPE_CLOCK_IN, Constants.ALARM_SOURCE_FALLBACK)
                    Log.d("AutoClock", "【滚动保底】已预先下发最近工作日上班打卡保底闹钟: ${clockInCal.time}")
                }

                if (includeClockOut) {
                    // 下班打卡保底闹钟
                    val clockOutMinuteOffset = if (aDiff > 0) Random.nextInt(0, aDiff + 1) else 0
                    val clockOutCal = (calendar.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, aStartHour)
                        set(Calendar.MINUTE, aStartMin + clockOutMinuteOffset)
                        set(Calendar.SECOND, Random.nextInt(0, 60))
                        set(Calendar.MILLISECOND, 0)
                    }
                    setExactAlarm(context, alarmManager, 1002, clockOutCal.timeInMillis, Constants.CLOCK_TYPE_CLOCK_OUT, Constants.ALARM_SOURCE_FALLBACK)
                    Log.d("AutoClock", "【滚动保底】已预先下发最近工作日下班打卡保底闹钟: ${clockOutCal.time}")
                }

                break
            }
        }
    }

    /**
     * 为今天计算并设置带有随机防作弊延迟的打卡精准闹钟
     */
    fun scheduleTodayClockActions(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (AlarmPermissionWarner.warnIfExactAlarmDenied(context, "scheduleTodayClockActions")) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 读取上班配置，默认 07:30 ~ 08:20
        val (mStartHour, mStartMin, mEndHour, mEndMin) = try {
            val s = (prefs.getString("morning_start", Constants.DEFAULT_MORNING_START) ?: Constants.DEFAULT_MORNING_START).split(":")
            val e = (prefs.getString("morning_end", Constants.DEFAULT_MORNING_END) ?: Constants.DEFAULT_MORNING_END).split(":")
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
            set(Calendar.MILLISECOND, 0)
        }

        // 读取下班配置，默认 18:00 ~ 18:10
        val (aStartHour, aStartMin, aEndHour, aEndMin) = try {
            val s = (prefs.getString("afternoon_start", Constants.DEFAULT_AFTERNOON_START) ?: Constants.DEFAULT_AFTERNOON_START).split(":")
            val e = (prefs.getString("afternoon_end", Constants.DEFAULT_AFTERNOON_END) ?: Constants.DEFAULT_AFTERNOON_END).split(":")
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
            set(Calendar.MILLISECOND, 0)
        }

        if (isClockAlreadyCompletedToday(context, Constants.CLOCK_TYPE_CLOCK_IN)) {
            Log.d("AutoClock", "今日上班卡已确认完成，跳过排班/补偿")
            ScheduleLogger.log(context, "今日上班卡已确认完成，跳过排班/补偿（防重复打卡）")
        } else when (resolveClockInAction(clockInCal.timeInMillis, System.currentTimeMillis())) {
            ClockAction.SCHEDULE -> {
                setExactAlarm(context, alarmManager, 1001, clockInCal.timeInMillis, Constants.CLOCK_TYPE_CLOCK_IN, Constants.ALARM_SOURCE_OFFICIAL)
                Log.d("AutoClock", "今天上班打卡已随机安排在: ${clockInCal.time}")
            }
            ClockAction.COMPENSATE -> {
                Log.w("AutoClock", "上班打卡随机时间已过，但在11:30之前，触发即时补打卡流程")
                triggerImmediateClock(context, Constants.CLOCK_TYPE_CLOCK_IN)
            }
            ClockAction.SKIP -> {
                Log.w("AutoClock", "上班打卡随机时间已过且超过11:30补偿截止线，跳过")
            }
        }

        if (isClockAlreadyCompletedToday(context, Constants.CLOCK_TYPE_CLOCK_OUT)) {
            Log.d("AutoClock", "今日下班卡已确认完成，跳过排班/补偿")
            ScheduleLogger.log(context, "今日下班卡已确认完成，跳过排班/补偿（防重复打卡）")
        } else when (resolveClockOutAction(clockOutCal.timeInMillis, System.currentTimeMillis())) {
            ClockAction.SCHEDULE -> {
                setExactAlarm(context, alarmManager, 1002, clockOutCal.timeInMillis, Constants.CLOCK_TYPE_CLOCK_OUT, Constants.ALARM_SOURCE_OFFICIAL)
                Log.d("AutoClock", "今天下班打卡已随机安排在: ${clockOutCal.time}")
            }
            ClockAction.COMPENSATE -> {
                Log.w("AutoClock", "下班打卡随机时间已过，但在22:00之前，触发即时补打卡流程")
                triggerImmediateClock(context, Constants.CLOCK_TYPE_CLOCK_OUT)
            }
            ClockAction.SKIP -> {
                Log.w("AutoClock", "下班打卡随机时间已过且超过22:00补偿截止线，跳过")
            }
        }
    }

    private fun triggerImmediateClock(context: Context, clockType: String) {
        val intent = Intent(context, ClockActionReceiver::class.java)
        intent.putExtra(Constants.EXTRA_CLOCK_TYPE, clockType)
        intent.putExtra(Constants.EXTRA_ALARM_SOURCE, Constants.ALARM_SOURCE_IMMEDIATE)
        context.sendBroadcast(intent)
    }

    /**
     * 调度延迟全量重试：当 3 次即时重试全部失败后，通过 AlarmManager 在 DELAYED_RETRY_INTERVAL_MS (60s)
     * 后重新触发整个打卡流程（ClockActionReceiver → WakeActivity → AccessibilityService）。
     * 使用 setAlarmClock 确保 Doze 穿透，与正常打卡闹钟同等优先级。
     *
     * @param currentRetryCount 当前已执行的延迟重试次数 (0-based)
     */
    fun scheduleDelayedClockInRetry(context: Context, clockType: String, currentRetryCount: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextRetryCount = currentRetryCount + 1
        val requestCode = 2000 + currentRetryCount  // 使用专属 requestCode 区间，避免与正常闹钟冲突

        val intent = Intent(context, ClockActionReceiver::class.java).apply {
            putExtra(Constants.EXTRA_CLOCK_TYPE, clockType)
            putExtra(Constants.EXTRA_DELAYED_RETRY_COUNT, nextRetryCount)
            putExtra(Constants.EXTRA_ALARM_SOURCE, Constants.ALARM_SOURCE_DELAYED_RETRY)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAt = System.currentTimeMillis() + Constants.DELAYED_RETRY_INTERVAL_MS

        if (AlarmPermissionWarner.warnIfExactAlarmDenied(context, "scheduleDelayedClockInRetry")) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val showIntent = Intent(context, com.lark.autoclock.MainActivity::class.java)
            val showPendingIntent = PendingIntent.getActivity(
                context, requestCode, showIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showPendingIntent), pendingIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        Log.d("AutoClock", "已调度延迟重试 #${nextRetryCount}/${Constants.DELAYED_RETRY_COUNT}: ${clockType}, " +
                "将在 ${Constants.DELAYED_RETRY_INTERVAL_MS / 1000}s 后触发")
    }

    /**
     * 判定是否应当进行未确认打卡自动重试：
     * 1. 仅对正式打卡类型（上班、下班）触发自动重试，调试测试类型（如"测试"）不自动排班；
     * 2. 当前已执行重试次数未达到上限 (MAX_UNCONFIRMED_RETRY_COUNT)。
     */
    fun shouldScheduleUnconfirmedRetry(
        clockType: String,
        currentRetryCount: Int,
        maxRetry: Int = Constants.MAX_UNCONFIRMED_RETRY_COUNT
    ): Boolean {
        val isNormalClock = clockType == Constants.CLOCK_TYPE_CLOCK_IN || clockType == Constants.CLOCK_TYPE_CLOCK_OUT
        return isNormalClock && currentRetryCount < maxRetry
    }

    /**
     * 调度极速打卡未确认时的延迟重试：
     * 飞书拉起后在 45s 内未检测到打卡成功确认文字时，
     * 通过 AlarmManager 在 UNCONFIRMED_RETRY_INTERVAL_MS (3分钟) 后
     * 重新触发整个打卡流程（ClockActionReceiver → WakeActivity → AccessibilityService）。
     * 使用 setAlarmClock 确保 Doze 深度休眠穿透。
     *
     * @param nextRetryCount 下一次重试计数 (1-based)
     */
    fun scheduleUnconfirmedClockInRetry(context: Context, clockType: String, nextRetryCount: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val requestCode = 3000 + nextRetryCount  // 使用专属 requestCode 区间 (3001+)，避免冲突

        val intent = Intent(context, ClockActionReceiver::class.java).apply {
            putExtra(Constants.EXTRA_CLOCK_TYPE, clockType)
            putExtra(Constants.EXTRA_UNCONFIRMED_RETRY_COUNT, nextRetryCount)
            putExtra(Constants.EXTRA_ALARM_SOURCE, Constants.ALARM_SOURCE_UNCONFIRMED_RETRY)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAt = System.currentTimeMillis() + Constants.UNCONFIRMED_RETRY_INTERVAL_MS

        if (AlarmPermissionWarner.warnIfExactAlarmDenied(context, "scheduleUnconfirmedClockInRetry")) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val showIntent = Intent(context, com.lark.autoclock.MainActivity::class.java)
            val showPendingIntent = PendingIntent.getActivity(
                context, requestCode, showIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showPendingIntent), pendingIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        Log.d("AutoClock", "已调度未确认自动重试 #${nextRetryCount}/${Constants.MAX_UNCONFIRMED_RETRY_COUNT}: ${clockType}, " +
                "将在 ${Constants.UNCONFIRMED_RETRY_INTERVAL_MS / 1000}s 后触发")
    }

    private fun setExactAlarm(context: Context, alarmManager: AlarmManager, requestCode: Int, timeInMillis: Long, clockType: String, source: String) {
        val intent = Intent(context, ClockActionReceiver::class.java)
        intent.putExtra(Constants.EXTRA_CLOCK_TYPE, clockType)
        intent.putExtra(Constants.EXTRA_ALARM_SOURCE, source)
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (AlarmPermissionWarner.warnIfExactAlarmDenied(context, "setExactAlarm")) return

        // 使用 setAlarmClock 替代 setExactAndAllowWhileIdle！
        // setAlarmClock 属于系统级 AlarmClock 视图，具备最高级别的硬件闹钟唤醒优先级，
        // 在 realme UI / ColorOS / MIUI 深度 Doze 休眠模式下能够强行穿透并唤醒设备。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val showIntent = Intent(context, com.lark.autoclock.MainActivity::class.java)
            val showPendingIntent = PendingIntent.getActivity(
                context, requestCode, showIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmClockInfo = AlarmManager.AlarmClockInfo(timeInMillis, showPendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeInMillis, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, timeInMillis, pendingIntent)
        }
        Log.d("AutoClock", "已通过 setAlarmClock 成功下发高优先级闹钟: $clockType @ $timeInMillis")
        ScheduleLogger.log(context, "已下发${source}闹钟: $clockType @ ${formatTime(timeInMillis)}")
    }
}
