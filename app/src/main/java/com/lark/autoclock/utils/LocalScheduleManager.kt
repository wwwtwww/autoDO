package com.lark.autoclock.utils

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object LocalScheduleManager {
    private const val PREFS_NAME = "AutoClockPrefs"
    private const val KEY_EXCEPTIONS = "schedule_exceptions"
    private val exceptionLock = Any()

    enum class WorkdayStatus {
        WORKDAY, RESTDAY, UNKNOWN
    }

    /**
     * 判断今天是否需要打卡（基于本地基础周期 + 例外日期配置）
     */
    fun getTodayWorkdayStatus(context: Context): WorkdayStatus {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. 检查是否在例外列表中
        val exceptionsStr = prefs.getString(KEY_EXCEPTIONS, "{}") ?: "{}"
        try {
            val exceptionsObj = JSONObject(exceptionsStr)
            if (exceptionsObj.has(todayStr)) {
                val status = exceptionsObj.getString(todayStr)
                Log.d("AutoClock", "今天 ($todayStr) 匹配到例外规则: $status")
                return if (status == "WORK") WorkdayStatus.WORKDAY else WorkdayStatus.RESTDAY
            }
        } catch (e: Exception) {
            Log.e("AutoClock", "解析例外配置失败: ${e.message}")
        }

        // 2. 检查基础周期
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        
        val isWorkday = when (dayOfWeek) {
            Calendar.MONDAY -> prefs.getBoolean("cycle_mon", true)
            Calendar.TUESDAY -> prefs.getBoolean("cycle_tue", true)
            Calendar.WEDNESDAY -> prefs.getBoolean("cycle_wed", true)
            Calendar.THURSDAY -> prefs.getBoolean("cycle_thu", true)
            Calendar.FRIDAY -> prefs.getBoolean("cycle_fri", true)
            Calendar.SATURDAY -> prefs.getBoolean("cycle_sat", false)
            Calendar.SUNDAY -> prefs.getBoolean("cycle_sun", false)
            else -> false
        }

        Log.d("AutoClock", "今天 ($todayStr, 星期${dayOfWeek - 1 ?: 7}) 基础周期判定: ${if (isWorkday) "上班" else "休息"}")
        return if (isWorkday) WorkdayStatus.WORKDAY else WorkdayStatus.RESTDAY
    }

    /**
     * 添加或更新例外日期
     * @param dateStr 格式 "yyyy-MM-dd"
     * @param isWorkday true 为强制上班，false 为强制休息
     */
    fun addException(context: Context, dateStr: String, isWorkday: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(exceptionLock) {
            val exceptionsStr = prefs.getString(KEY_EXCEPTIONS, "{}") ?: "{}"
            try {
                val exceptionsObj = JSONObject(exceptionsStr)
                exceptionsObj.put(dateStr, if (isWorkday) "WORK" else "REST")
                prefs.edit().putString(KEY_EXCEPTIONS, exceptionsObj.toString()).apply()
            } catch (e: Exception) {
                Log.e("AutoClock", "保存例外配置失败: ${e.message}")
            }
        }
    }

    /**
     * 移除例外日期
     */
    fun removeException(context: Context, dateStr: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(exceptionLock) {
            val exceptionsStr = prefs.getString(KEY_EXCEPTIONS, "{}") ?: "{}"
            try {
                val exceptionsObj = JSONObject(exceptionsStr)
                exceptionsObj.remove(dateStr)
                prefs.edit().putString(KEY_EXCEPTIONS, exceptionsObj.toString()).apply()
            } catch (e: Exception) {
                Log.e("AutoClock", "移除例外配置失败: ${e.message}")
            }
        }
    }

    /**
     * 获取所有例外日期（用于 UI 展示）
     * 返回 Map<"yyyy-MM-dd", "WORK"/"REST">
     */
    fun getAllExceptions(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val exceptionsStr = prefs.getString(KEY_EXCEPTIONS, "{}") ?: "{}"
        val result = mutableMapOf<String, String>()
        try {
            val exceptionsObj = JSONObject(exceptionsStr)
            val keys = exceptionsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = exceptionsObj.getString(key)
            }
        } catch (e: Exception) {
            Log.e("AutoClock", "读取例外配置失败: ${e.message}")
        }
        return result.toSortedMap()
    }
}
