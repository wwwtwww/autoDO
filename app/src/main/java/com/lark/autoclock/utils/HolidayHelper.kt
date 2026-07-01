package com.lark.autoclock.utils

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
        val urlString = "https://timor.tech/api/holiday/info/$today"
        
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            if (connection.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()
                
                val json = JSONObject(response)
                if (json.getInt("code") == 0) {
                    val type = json.getJSONObject("type").getInt("type")
                    return type == 0 || type == 3
                }
            }
            // 如果网络失败或 API 服务故障，默认回退为普通的周一到周五工作日
            isFallbackWorkday()
        } catch (e: Exception) {
            e.printStackTrace()
            isFallbackWorkday()
        }
    }

    private fun isFallbackWorkday(): Boolean {
        val calendar = java.util.Calendar.getInstance()
        val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)
        return dayOfWeek != java.util.Calendar.SATURDAY && dayOfWeek != java.util.Calendar.SUNDAY
    }
}
