package com.lark.autoclock.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调度链路可观测日志。
 *
 * 将闹钟「下发 / 触发 / 自愈 / 权限异常」事件以 [调度] 前缀写入 clock_log.txt，
 * 与打卡结果日志同文件展示，使「闹钟根本没触发」与「触发但打卡失败」可明确区分。
 *
 * 历史教训：此前日志仅由打卡执行路径写入，闹钟链断裂时完全无迹可查
 * （周末调度链断裂导致周一漏卡时，日志里连一条线索都没有）。
 */
object ScheduleLogger {
    private const val TAG = "AutoClock"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 追加一条调度事件日志。可在任意线程调用，文件写由内部 IO 协程串行完成
     * （落盘复用 LogUtil 的全局互斥锁与容量裁剪）。
     */
    fun log(context: Context, message: String) {
        Log.d(TAG, "调度事件: $message")
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$timeStr] [调度] $message\n"
        scope.launch {
            LogUtil.appendLog(context.applicationContext, line)
        }
    }
}
