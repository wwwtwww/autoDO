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
    // 调度链路可观测化后日志量增长至约 8~12 行/天（含打卡记录），
    // 500/400 行容量可保留约 1~2 个月历史，避免打卡成败记录被调度日志过早冲刷
    internal const val MAX_LINES = 500
    internal const val KEEP_LINES = 400
    private val logLock = Any()

    fun getLogFile(context: Context): File = File(context.filesDir, LOG_FILE_NAME)

    /**
     * 纯函数：日志容量裁剪策略（可单元测试）。
     * 超过 MAX_LINES 才触发裁剪（降频 IO），裁剪后仅保留最近 KEEP_LINES 行。
     */
    fun trimLines(lines: List<String>): List<String> =
        if (lines.size > MAX_LINES) lines.takeLast(KEEP_LINES) else lines

    suspend fun appendLog(context: Context, logLine: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(logLock) {
            try {
                val logFile = getLogFile(context)
                logFile.appendText(logLine)

                // 限制文件行数，保留最近 400 行防止无限膨胀（降频：超过 500 行才裁剪）
                val lines = logFile.readLines()
                val trimmed = trimLines(lines)
                if (trimmed.size != lines.size) {
                    logFile.writeText(trimmed.joinToString("\n") + "\n")
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
