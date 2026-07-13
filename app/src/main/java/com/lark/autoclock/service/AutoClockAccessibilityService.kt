package com.lark.autoclock.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoClockAccessibilityService : AccessibilityService() {

    /**
     * 简化后的状态机（配合极速打卡）：
     * IDLE          -> 空闲，不响应任何事件
     * WAIT_CONFIRM  -> 已拉起飞书，等待检测打卡结果
     * DONE          -> 打卡完成
     */
    enum class ClockState {
        IDLE, WAIT_CONFIRM, DONE
    }

    private var currentState = ClockState.IDLE
    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private var lastScanAt = 0L
    private val MAX_RETRY = 30 // 约 30 秒超时

    companion object {
        const val FEISHU_PACKAGE_NAME = "com.ss.android.lark"
        const val TAG = "AutoClock"
        const val NOTIFICATION_CHANNEL_ID = "autoclock_result"
        private const val MIN_SCAN_INTERVAL_MS = 500L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_START_CLOCK_IN" -> {
                Log.d(TAG, "=== 收到打卡指令，直接拉起飞书（极速打卡模式）===")
                // 必须先移除之前的挂起任务（防止重入时旧的 timeout 触发）
                handler.removeCallbacksAndMessages(null)
                retryCount = 0
                lastScanAt = 0L
                currentState = ClockState.WAIT_CONFIRM

                launchFeishu()

                // 超时机制：15 秒后如果还没确认到打卡结果
                handler.postDelayed({
                    if (currentState == ClockState.WAIT_CONFIRM) {
                        Log.w(TAG, "等待 15 秒未检测到明确的打卡确认文字")
                        // 虽然没检测到确认文字，但极速打卡大概率已经成功
                        val msg = "飞书已启动，极速打卡应已触发（未检测到明确确认文字）"
                        recordClockResult(confirmed = false, detail = msg)
                        goHomeAndReset()
                    }
                }, 15000)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 直接启动飞书
     */
    private fun launchFeishu() {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(FEISHU_PACKAGE_NAME)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(launchIntent)
                Log.d(TAG, "成功拉起飞书")
            } else {
                Log.e(TAG, "未找到飞书包: $FEISHU_PACKAGE_NAME")
                recordClockResult(confirmed = false, detail = "未找到飞书 App")
                lastScanAt = 0L
                currentState = ClockState.IDLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "拉起飞书异常: ${e.message}")
            recordClockResult(confirmed = false, detail = "启动飞书异常: ${e.message}")
            lastScanAt = 0L
            currentState = ClockState.IDLE
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (currentState != ClockState.WAIT_CONFIRM || event == null) return

        // 只关注飞书的事件
        if (event.packageName?.toString() != FEISHU_PACKAGE_NAME) return

        val now = System.currentTimeMillis()
        if (now - lastScanAt < MIN_SCAN_INTERVAL_MS) return
        lastScanAt = now

        val rootNode = rootInActiveWindow ?: return

        retryCount++
        if (retryCount > MAX_RETRY) return // 超时由 handler 处理

        // 收集当前界面所有文字（用于后续多重判定）
        val allText = collectAllText(rootNode)

        val matchedText = findFirstSuccessNodeText(rootNode)
        val confirmed = ClockSuccessMatcher.isConfirmedClockSuccessText(allText)
        if (matchedText != null && confirmed) {
            Log.d(TAG, "=== 检测到可信极速打卡成功标志: '$matchedText' ===")
            val msg = "极速打卡成功！检测到: $matchedText"
            recordClockResult(confirmed = true, detail = msg)
            handler.removeCallbacksAndMessages(null) // 主动取消 15 秒超时检测
            goHomeAndReset()
            return
        }

        if (matchedText != null) {
            Log.d(TAG, "检测到打卡关键词但缺少考勤上下文，跳过确认: '$matchedText'")
        }
    }

    private fun findFirstSuccessNodeText(rootNode: AccessibilityNodeInfo): String? {
        for (keyword in ClockSuccessMatcher.successKeywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            try {
                val matchedText = nodes.firstOrNull()?.text?.toString() ?: keyword
                if (nodes.isNotEmpty()) return matchedText
            } finally {
                nodes.forEach { it.recycle() }
            }
        }
        return null
    }

    /**
     * 递归收集节点树中所有可见文字
     */
    private fun collectAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        appendAllText(node, sb)
        return sb.toString()
    }

    private fun appendAllText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.isNotEmpty()) sb.append(text).append(' ')
        if (desc.isNotEmpty()) sb.append(desc).append(' ')

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                appendAllText(child, sb)
            } finally {
                child.recycle()
            }
        }
    }

    /**
     * 返回桌面并重置状态
     */
    private fun goHomeAndReset() {
        currentState = ClockState.DONE
        retryCount = 0
        handler.postDelayed({
            try {
                val homePerformed = performGlobalAction(GLOBAL_ACTION_HOME)
                if (!homePerformed) {
                    Log.w(TAG, "返回桌面动作未被系统执行")
                }
            } catch (e: Exception) {
                Log.e(TAG, "返回桌面动作异常: ${e.message}")
            } finally {
                // 打卡结束归位，精准清除打卡唤醒通知
                try {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(com.lark.autoclock.scheduler.ClockActionReceiver.WAKE_NOTIFICATION_ID)
                } catch (e: Exception) {
                    Log.e(TAG, "服务中清除唤醒通知异常: ${e.message}")
                }

                currentState = ClockState.IDLE
                lastScanAt = 0L
                Log.d(TAG, "流程结束，状态已重置")
            }
        }, 3000)
    }

    /**
     * 将打卡结果写入本地日志文件，方便事后查看
     * 日志路径: /data/data/com.lark.autoclock/files/clock_log.txt
     */
    private fun recordClockResult(confirmed: Boolean, detail: String) {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val timestamp = sdf.format(Date())
            val status = if (confirmed) "✅ 已确认" else "⚠️ 未确认"
            val logLine = "[$timestamp] $status | $detail\n"

            val logFile = File(applicationContext.filesDir, "clock_log.txt")
            logFile.appendText(logLine)
            
            // 限制文件行数，保留最近 200 行防止无限膨胀
            val lines = logFile.readLines()
            if (lines.size > 200) {
                logFile.writeText(lines.takeLast(200).joinToString("\n") + "\n")
            }
            
            Log.d(TAG, "打卡日志已写入: $logLine")
        } catch (e: Exception) {
            Log.e(TAG, "写入打卡日志失败: ${e.message}")
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        currentState = ClockState.IDLE
        Log.d(TAG, "服务已销毁，已清理所有挂起的 Handler 回调")
    }
}
