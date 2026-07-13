package com.lark.autoclock.utils

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HolidayHelper {

    enum class WorkdayStatus {
        WORKDAY, RESTDAY, UNKNOWN
    }

    fun parseWorkdayStatus(response: String): WorkdayStatus {
        return try {
            val jsonObject = JSONObject(response)
            if (jsonObject.getInt("code") != 0) return WorkdayStatus.UNKNOWN

            when (jsonObject.getJSONObject("type").getInt("type")) {
                0, 3 -> WorkdayStatus.WORKDAY
                1, 2 -> WorkdayStatus.RESTDAY
                else -> WorkdayStatus.UNKNOWN
            }
        } catch (e: Exception) {
            WorkdayStatus.UNKNOWN
        }
    }

    /**
     * 检查今天是否是工作日
     * type = 0 (工作日), type = 3 (调休工作日) 返回 true
     * type = 1 (周末), type = 2 (节假日) 返回 false
     */
    @Deprecated(
        message = "UNKNOWN 状态会被隐式判定为工作日，绕过智能降级逻辑。请改用 getTodayWorkdayStatus() + resolveStatusOnNetworkFailure() 组合。",
        replaceWith = ReplaceWith("getTodayWorkdayStatus()")
    )
    fun isTodayWorkday(): Boolean {
        return getTodayWorkdayStatus() != WorkdayStatus.RESTDAY
    }

    fun getTodayWorkdayStatus(): WorkdayStatus {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val urlString = "https://timor.tech/api/holiday/info/$today"
        var connection: HttpURLConnection? = null

        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000  // 缩短至 3 秒，为广播生命周期留足余量
            connection.readTimeout = 3000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                parseWorkdayStatus(response)
            } else {
                Log.w("HolidayHelper", "节假日 API HTTP ${connection.responseCode}，状态未知")
                WorkdayStatus.UNKNOWN
            }
        } catch (e: Exception) {
            Log.w("HolidayHelper", "节假日 API 请求失败，状态未知: ${e.message}")
            WorkdayStatus.UNKNOWN
        } finally {
            connection?.disconnect()
        }
    }

    fun resolveStatusOnNetworkFailure(dayOfWeek: Int): WorkdayStatus {
        val isWeekend = dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY
        return if (isWeekend) WorkdayStatus.RESTDAY else WorkdayStatus.WORKDAY
    }
}
