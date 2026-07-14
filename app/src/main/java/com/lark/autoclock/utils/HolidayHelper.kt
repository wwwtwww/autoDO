package com.lark.autoclock.utils

import android.util.Log
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
            // 由于 Android SDK 的 org.json.JSONObject 在本地 JVM 单元测试中会抛出 "Stub!" 异常，
            // 且测试类路径问题导致额外的 json 依赖很难覆盖 android.jar，
            // 这里针对 Timor API 非常固定的格式，采用基于 Regex 的轻量级解析。
            
            // 1. 检查 code 是否为 0
            val codeMatch = Regex("\"code\"\\s*:\\s*(-?\\d+)").find(response)
            if (codeMatch?.groupValues?.get(1) != "0") return WorkdayStatus.UNKNOWN

            // 2. 提取 "type" 对象内容 (例如 "type":{"type":0,"name":"周一"})
            val typeObjMatch = Regex("\"type\"\\s*:\\s*\\{([^}]+)\\}").find(response)
            val typeObjStr = typeObjMatch?.groupValues?.get(1) ?: return WorkdayStatus.UNKNOWN

            // 3. 在 type 对象内部读取 "type" 的整型值
            val typeMatch = Regex("\"type\"\\s*:\\s*(\\d+)").find(typeObjStr)
            val typeVal = typeMatch?.groupValues?.get(1)?.toIntOrNull() ?: return WorkdayStatus.UNKNOWN

            when (typeVal) {
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
