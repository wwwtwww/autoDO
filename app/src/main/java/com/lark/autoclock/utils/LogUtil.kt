package com.lark.autoclock.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 打卡日志统一管理工具类
 *
 * 集中管理 clock_log.txt 的追加、容量裁剪与清空，
 * 通过全局互斥锁保证跨组件（无障碍服务 / WakeActivity / MainActivity）并发读写的线程安全。
 */
object LogUtil {
    private const val TAG = "LogUtil"
    private const val LOG_FILE_NAME = "clock_log.txt"
    private const val MAX_LINES = 250
    private const val KEEP_LINES = 200
    private val logLock = Any()

    fun getLogFile(context: Context): File = File(context.filesDir, LOG_FILE_NAME)

    suspend fun appendLog(context: Context, logLine: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(logLock) {
            try {
                val logFile = getLogFile(context)
                logFile.appendText(logLine)

                // 限制文件行数，保留最近 200 行防止无限膨胀（降频：超过 250 行才裁剪）
                val lines = logFile.readLines()
                if (lines.size > MAX_LINES) {
                    logFile.writeText(lines.takeLast(KEEP_LINES).joinToString("\n") + "\n")
                }

                Log.d(TAG, "打卡日志已写入磁盘")
                true
            } catch (e: Exception) {
                Log.e(TAG, "写入打卡日志失败: ${e.message}")
                false
            }
        }
    }

    suspend fun clearLog(context: Context): Boolean = withContext(Dispatchers.IO) {
        synchronized(logLock) {
            try {
                getLogFile(context).delete()
            } catch (e: Exception) {
                Log.e(TAG, "清空打卡日志失败: ${e.message}")
                false
            }
        }
    }
}
