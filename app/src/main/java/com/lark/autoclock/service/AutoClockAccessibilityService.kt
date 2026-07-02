package com.lark.autoclock.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class AutoClockAccessibilityService : AccessibilityService() {

    /**
     * 简化后的状态机：
     * IDLE         -> 空闲，不响应任何事件
     * FIND_CLOCK   -> 进入假勤页面后，寻找"打卡"入口
     * FIND_BUTTON  -> 进入打卡页面后，寻找"上班打卡"或"下班打卡"按钮
     * DONE         -> 打卡完成，等待回到桌面
     */
    enum class ClockState {
        IDLE, FIND_CLOCK, FIND_BUTTON, DONE
    }

    private var currentState = ClockState.IDLE
    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private val MAX_RETRY = 30 // 最多重试 30 次（约 30 秒）

    companion object {
        // 飞书的包名
        const val FEISHU_PACKAGE_NAME = "com.ss.android.lark"
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_START_CLOCK_IN" -> {
                Log.d("AutoClock", "=== 收到打卡指令，启动桌面假勤快捷方式 ===")
                retryCount = 0
                currentState = ClockState.FIND_CLOCK
                launchJiaqinShortcut()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 通过模拟点击桌面上的"假勤"快捷图标来直接进入假勤页面。
     * 原理：先回到桌面，然后通过无障碍服务扫描桌面上文字为"假勤"的节点并点击。
     */
    private fun launchJiaqinShortcut() {
        showToast("正在回到桌面，尝试寻找'假勤'快捷方式...")
        // 先回到桌面
        performGlobalAction(GLOBAL_ACTION_HOME)
        
        // 等 1.5 秒让桌面完全加载，然后开始扫描
        handler.postDelayed({
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                val nodes = rootNode.findAccessibilityNodeInfosByText("假勤")
                for (node in nodes) {
                    if (clickNode(node)) {
                        Log.d("AutoClock", "成功点击桌面上的'假勤'快捷方式！")
                        showToast("已点击'假勤'，等待飞书打开...")
                        currentState = ClockState.FIND_CLOCK
                        return@postDelayed
                    }
                }
            }
            // 如果桌面上没找到"假勤"，尝试回退方案：直接通过飞书包名启动
            Log.w("AutoClock", "桌面未找到'假勤'快捷方式，回退为启动飞书主页")
            showToast("未找到桌面'假勤'快捷方式，尝试直接拉起飞书主应用...")
            launchFeishuFallback()
        }, 1500)
    }

    /**
     * 回退方案：直接启动飞书主应用
     */
    private fun launchFeishuFallback() {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(FEISHU_PACKAGE_NAME)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(launchIntent)
                Log.d("AutoClock", "成功拉起飞书主应用")
                showToast("已拉起飞书，正在扫描页面...")
            } else {
                Log.e("AutoClock", "未找到飞书包: $FEISHU_PACKAGE_NAME")
                showToast("未找到飞书 App，打卡中止")
                currentState = ClockState.IDLE
            }
        } catch (e: Exception) {
            Log.e("AutoClock", "拉起飞书异常: ${e.message}")
            showToast("启动飞书异常: ${e.message}")
            currentState = ClockState.IDLE
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (currentState == ClockState.IDLE || currentState == ClockState.DONE || event == null) return
        val rootNode = rootInActiveWindow ?: return

        retryCount++
        if (retryCount > MAX_RETRY) {
            Log.e("AutoClock", "超过最大重试次数，自动中止并回到桌面")
            showToast("打卡流程超时，未找到对应按钮！")
            currentState = ClockState.IDLE
            retryCount = 0
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        when (currentState) {
            ClockState.FIND_CLOCK -> {
                // 在假勤页面中寻找"打卡"入口
                val nodes = rootNode.findAccessibilityNodeInfosByText("打卡")
                for (node in nodes) {
                    // 避免误点"上班打卡"或"下班打卡"（那是下一步的目标）
                    val nodeText = node.text?.toString() ?: ""
                    if (nodeText.contains("上班") || nodeText.contains("下班")) continue
                    
                    if (clickNode(node)) {
                        Log.d("AutoClock", "成功点击'打卡'入口，进入打卡页面")
                        showToast("正在打开打卡页面...")
                        currentState = ClockState.FIND_BUTTON
                        retryCount = 0
                        return
                    }
                }
            }
            ClockState.FIND_BUTTON -> {
                // 在打卡页面中寻找"上班打卡"或"下班打卡"按钮
                val targets = rootNode.findAccessibilityNodeInfosByText("上班打卡") +
                              rootNode.findAccessibilityNodeInfosByText("下班打卡")
                for (node in targets) {
                    if (clickNode(node)) {
                        Log.d("AutoClock", "=== 打卡大成功！===")
                        showToast("打卡大成功！")
                        currentState = ClockState.DONE
                        retryCount = 0
                        handler.postDelayed({
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            currentState = ClockState.IDLE
                            Log.d("AutoClock", "已返回桌面，流程结束")
                        }, 5000)
                        return
                    }
                }
            }
            else -> {}
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var currentNode: AccessibilityNodeInfo? = node
        while (currentNode != null) {
            if (currentNode.isClickable) {
                currentNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            currentNode = currentNode.parent
        }
        return false
    }

    override fun onInterrupt() {}
}
