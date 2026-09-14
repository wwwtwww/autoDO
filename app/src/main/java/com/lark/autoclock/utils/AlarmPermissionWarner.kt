package com.lark.autoclock.utils

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lark.autoclock.Constants
import com.lark.autoclock.R

/**
 * 精确闹钟权限巡检与显式告警。
 *
 * 历史教训：Android 14（targetSdk 34）上 SCHEDULE_EXACT_ALARM 可被系统/用户撤销，
 * 旧代码在 canScheduleExactAlarms() == false 时仅 Log.e 静默 return，
 * 导致整条闹钟调度链永久断裂而用户毫无感知。
 * 现统一改为：高优先级通知（点击直达授权页）+ 调度日志双通道告警。
 */
object AlarmPermissionWarner {

    fun hasExactAlarmPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    /**
     * 若精确闹钟权限缺失，发送高优先级告警通知并写调度日志。
     *
     * @param callerTag 调用方标识，用于日志定位是哪个调度环节发现权限缺失
     * @return true = 权限缺失（调用方应中止闹钟下发）
     */
    fun warnIfExactAlarmDenied(context: Context, callerTag: String): Boolean {
        if (hasExactAlarmPermission(context)) return false

        Log.e("AutoClock", "[$callerTag] 精确闹钟权限缺失，闹钟下发中止，已发送告警通知")
        ScheduleLogger.log(context, "❌精确闹钟权限缺失（$callerTag），闹钟下发中止，已发送告警通知")

        NotificationUtil.createAllChannels(context)

        // 直达精确闹钟授权页；极端 ROM 无此页面接收方时降级到应用详情页。
        // 注意：Intent 构造与 PendingIntent.getActivity 均不抛异常，ActivityNotFoundException
        // 发生在用户点击通知后系统端 startActivity 时（进程外、不可捕获），
        // 因此必须用 resolveActivity 前置探活，而非 try-catch。
        val exactAlarmIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        val settingsIntent = if (exactAlarmIntent.resolveActivity(context.packageManager) != null) {
            exactAlarmIntent
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_ID_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.notif_alarm_perm_title))
            .setContentText(context.getString(R.string.notif_alarm_perm_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        notificationManager.notify(Constants.ALARM_PERMISSION_NOTIFICATION_ID, notification)
        return true
    }
}
