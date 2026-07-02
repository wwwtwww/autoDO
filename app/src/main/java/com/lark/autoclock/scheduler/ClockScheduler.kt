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
    
    // 假设：基准上班时间为 09:00，下班时间为 18:00
    // 真实业务中，这些时间应该从 SharedPreferences 中读取

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

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
        Log.d("AutoClock", "已激活每天凌晨 00:30 的日程调度器")
    }

    /**
     * 为今天计算并设置带有随机防作弊延迟的打卡精准闹钟
     */
    fun scheduleTodayClockActions(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // 上班：07:30 ~ 08:20 随机触发 (50分钟跨度 = 51个分钟偏移)
        val clockInMinuteOffset = Random.nextInt(0, 51)
        val clockInCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 30 + clockInMinuteOffset)
            set(Calendar.SECOND, Random.nextInt(0, 60))
        }

        // 下班：18:00 ~ 18:10 随机触发
        val clockOutMinuteOffset = Random.nextInt(0, 11)
        val clockOutCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 18)
            set(Calendar.MINUTE, clockOutMinuteOffset)
            set(Calendar.SECOND, Random.nextInt(0, 60))
        }

        if (clockInCal.timeInMillis > System.currentTimeMillis()) {
            setExactAlarm(context, alarmManager, 1001, clockInCal.timeInMillis)
            Log.d("AutoClock", "今天上班打卡已随机安排在: ${clockInCal.time}")
        }

        if (clockOutCal.timeInMillis > System.currentTimeMillis()) {
            setExactAlarm(context, alarmManager, 1002, clockOutCal.timeInMillis)
            Log.d("AutoClock", "今天下班打卡已随机安排在: ${clockOutCal.time}")
        }
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
