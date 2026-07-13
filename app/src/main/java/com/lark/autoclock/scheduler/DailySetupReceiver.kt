package com.lark.autoclock.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.lark.autoclock.utils.HolidayHelper
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat

class DailySetupReceiver : BroadcastReceiver() {

    companion object {
        private const val FALLBACK_NOTIFICATION_ID = 10002
    }

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
                    status = HolidayHelper.resolveStatusOnNetworkFailure(dayOfWeek)
                    if (status == HolidayHelper.WorkdayStatus.RESTDAY) {
                        Log.w("AutoClock", "无法确认节假日状态（网络异常），周末默认降级为休息日")
                        sendFallbackNotification(context, "周末默认休息（API异常）")
                    } else {
                        Log.w("AutoClock", "无法确认节假日状态（网络异常），工作日默认降级为上班日并下发闹钟")
                        sendFallbackNotification(context, "工作日默认打卡（API异常）")
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
            } catch (e: Exception) {
                Log.e("AutoClock", "凌晨调度任务异常: ${e.message}", e)
                // 安全降级：即使发生异常也尝试下发打卡闹钟，宁可误打不可漏打
                try {
                    ClockScheduler.scheduleTodayClockActions(context)
                    sendFallbackNotification(context, "调度异常已降级补发打卡闹钟")
                } catch (fallbackEx: Exception) {
                    Log.e("AutoClock", "降级补发打卡闹钟也失败: ${fallbackEx.message}", fallbackEx)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun sendFallbackNotification(context: Context, content: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "autoclock_fallback"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "打卡降级通知", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("autoDO 节假日API异常")
            .setContentText(content)
            .setAutoCancel(true)
            .build()

        // 使用固定 ID，新通知覆盖旧通知，避免连续断网时通知栏堆积
        notificationManager.notify(FALLBACK_NOTIFICATION_ID, notification)
    }
}
