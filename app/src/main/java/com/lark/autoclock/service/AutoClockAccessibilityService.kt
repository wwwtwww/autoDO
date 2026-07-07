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
    private val MAX_RETRY = 30 // 约 30 秒超时

    companion object {
        const val FEISHU_PACKAGE_NAME = "com.ss.android.lark"
        const val TAG = "AutoClock"
        const val NOTIFICATION_CHANNEL_ID = "autoclock_result"

        // 极速打卡成功后，飞书界面上可能出现的关键词
        val CLOCK_SUCCESS_KEYWORDS = listOf(
            "打卡成功", "已打卡", "上班·已打卡", "下班·已打卡",
            "更新打卡", "已于", "打卡时间"
        )
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_START_CLOCK_IN" -> {
                Log.d(TAG, "=== 收到打卡指令，直接拉起飞书（极速打卡模式）===")
                // 必须先移除之前的挂起任务（防止重入时旧的 timeout 触发）
                handler.removeCallbacksAndMessages(null)
                retryCount = 0
                currentState = ClockState.WAIT_CONFIRM

                showToast("正在启动飞书，等待极速打卡...")
                launchFeishu()

                // 超时机制：15 秒后如果还没确认到打卡结果
                handler.postDelayed({
                    if (currentState == ClockState.WAIT_CONFIRM) {
                        Log.w(TAG, "等待 15 秒未检测到明确的打卡确认文字")
                        // 虽然没检测到确认文字，但极速打卡大概率已经成功
                        val msg = "飞书已启动，极速打卡应已触发（未检测到明确确认文字）"
                        showToast(msg)
                        recordClockResult(confirmed = false, detail = msg)
                        sendResultNotification(confirmed = false)
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
                showToast("飞书已启动，正在等待极速打卡确认...")
            } else {
                Log.e(TAG, "未找到飞书包: $FEISHU_PACKAGE_NAME")
                showToast("未找到飞书 App，打卡中止")
                recordClockResult(confirmed = false, detail = "未找到飞书 App")
                sendResultNotification(confirmed = false, detail = "未找到飞书 App")
                currentState = ClockState.IDLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "拉起飞书异常: ${e.message}")
            showToast("启动飞书异常: ${e.message}")
            recordClockResult(confirmed = false, detail = "启动飞书异常: ${e.message}")
            sendResultNotification(confirmed = false, detail = "启动飞书异常")
            currentState = ClockState.IDLE
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (currentState != ClockState.WAIT_CONFIRM || event == null) return

        // 只关注飞书的事件
        if (event.packageName?.toString() != FEISHU_PACKAGE_NAME) return

        val rootNode = rootInActiveWindow ?: return

        retryCount++
        if (retryCount > MAX_RETRY) return // 超时由 handler 处理

        // 收集当前界面所有文字（用于后续多重判定）
        val allText = collectAllText(rootNode)

        // === 防误判守卫 ===
        // 如果界面上同时出现了聊天特征关键词（输入框、发送按钮、消息列表），
        // 说明用户当前停留在飞书聊天页面而非打卡页面，跳过本次检测。
        val chatIndicators = listOf("输入消息", "发送", "消息记录", "聊天记录", "回复")
        val isChatPage = chatIndicators.count { allText.contains(it) } >= 2
        if (isChatPage) {
            Log.d(TAG, "检测到聊天页面特征，跳过本轮关键词扫描以避免误判")
            return
        }

        // 扫描飞书界面上是否出现了打卡成功的关键词
        for (keyword in CLOCK_SUCCESS_KEYWORDS) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            if (nodes.isNotEmpty()) {
                val matchedText = nodes.firstOrNull()?.text?.toString() ?: keyword
                Log.d(TAG, "=== 检测到极速打卡成功标志: '$matchedText' ===")
                val msg = "极速打卡成功！检测到: $matchedText"
                showToast("✅ $msg")
                recordClockResult(confirmed = true, detail = msg)
                sendResultNotification(confirmed = true, detail = matchedText)
                goHomeAndReset()
                return
            }
        }

        // 同时尝试深度搜索
        for (keyword in CLOCK_SUCCESS_KEYWORDS) {
            if (allText.contains(keyword)) {
                Log.d(TAG, "=== 深度搜索检测到极速打卡成功标志: '$keyword' ===")
                val msg = "极速打卡成功！检测到: $keyword"
                showToast("✅ $msg")
                recordClockResult(confirmed = true, detail = msg)
                sendResultNotification(confirmed = true, detail = keyword)
                goHomeAndReset()
                return
            }
        }
    }

    /**
     * 递归收集节点树中所有可见文字
     */
    private fun collectAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.isNotEmpty()) sb.append(text).append(" ")
        if (desc.isNotEmpty()) sb.append(desc).append(" ")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(collectAllText(child))
        }
        return sb.toString()
    }

    /**
     * 返回桌面并重置状态
     */
    private fun goHomeAndReset() {
        currentState = ClockState.DONE
        retryCount = 0
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
            currentState = ClockState.IDLE
            Log.d(TAG, "已返回桌面，流程结束")
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
            Log.d(TAG, "打卡日志已写入: $logLine")
        } catch (e: Exception) {
            Log.e(TAG, "写入打卡日志失败: ${e.message}")
        }
    }

    /**
     * 发送一条常驻通知，显示打卡结果
     */
    private fun sendResultNotification(confirmed: Boolean, detail: String = "") {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "打卡结果通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "显示自动打卡的执行结果"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timeStr = sdf.format(Date())
        val title = if (confirmed) "✅ 打卡成功" else "⚠️ 打卡状态待确认"
        val content = if (confirmed) {
            "[$timeStr] 极速打卡已确认成功 | $detail"
        } else {
            "[$timeStr] 飞书已启动，请手动确认打卡结果"
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationId = System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, notification)
    }



    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        currentState = ClockState.IDLE
        Log.d(TAG, "服务已销毁，已清理所有挂起的 Handler 回调")
    }
}
