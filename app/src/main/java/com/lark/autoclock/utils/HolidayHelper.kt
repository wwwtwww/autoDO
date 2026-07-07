package com.lark.autoclock.utils

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HolidayHelper {

    /**
     * 检查今天是否是工作日
     * type = 0 (工作日), type = 3 (调休工作日) 返回 true
     * type = 1 (周末), type = 2 (节假日) 返回 false
     */
    fun isTodayWorkday(): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        // timor.tech 节假日 API
        val urlString = "https://timor.tech/api/holiday/info/$today"
        var connection: java.net.HttpURLConnection? = null
        
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000  // 缩短至 3 秒，为广播生命周期留足余量
            connection.readTimeout = 3000

            if (connection.responseCode == 200) {
                val inputStream = connection.inputStream
                val response = inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)

                if (jsonObject.getInt("code") == 0) {
                    val type = jsonObject.getJSONObject("type").getInt("type")
                    // type: 0 工作日, 1 休息日, 2 节假日, 3 调休工作日
                    return type == 0 || type == 3
                }
            }
            // 请求失败，走安全降级：默认当作工作日（宁可多打卡，不可漏打卡）
            true
        } catch (e: Exception) {
            e.printStackTrace()
            // 网络异常，走安全降级：默认当作工作日（防旷工设计）
            Log.w("HolidayHelper", "节假日 API 请求失败，安全降级为工作日: ${e.message}")
            true
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 纯周末判定（仅在需要时作为参考，不再作为 API 失败的退化方案）
     */
    private fun isFallbackWorkday(): Boolean {
        val calendar = java.util.Calendar.getInstance()
        val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)
        return dayOfWeek != java.util.Calendar.SATURDAY && dayOfWeek != java.util.Calendar.SUNDAY
    }
}
