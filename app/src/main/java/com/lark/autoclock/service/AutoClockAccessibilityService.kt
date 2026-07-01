package com.lark.autoclock.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lark.autoclock.utils.UnlockHelper

class AutoClockAccessibilityService : AccessibilityService() {

    enum class ClockState {
        IDLE, FIND_WORKBENCH, FIND_CLOCK_APP, FIND_CLOCK_BUTTON, DONE
    }

    private var currentState = ClockState.IDLE
    private val handler = Handler(Looper.getMainLooper())
    private val FEISHU_PACKAGE_NAME = "com.ss.android.lark"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_TEST_UNLOCK" -> {
                val chainAction = intent.getStringExtra("CHAIN_ACTION")
                Log.d("AutoClock", "收到解锁指令，是否存在链式后续动作：$chainAction")
                
                if (UnlockHelper.isDeviceLocked(this)) {
                    Log.d("AutoClock", "设备已锁定，执行滑动解锁...")
                    Handler(Looper.getMainLooper()).postDelayed({
                        UnlockHelper.swipeToUnlock(this)
                        
                        // 若存在链式动作（例如真实打卡任务），延迟 2.5 秒后唤起飞书，给足桌面解锁动画的时间
                        if (chainAction == "ACTION_START_CLOCK_IN") {
                            Handler(Looper.getMainLooper()).postDelayed({
                                currentState = ClockState.FIND_WORKBENCH
                                launchFeishu()
                            }, 2500)
                        }
                    }, 1000)
                } else {
                    Log.d("AutoClock", "设备未锁定，直接执行链式动作")
                    if (chainAction == "ACTION_START_CLOCK_IN") {
                        currentState = ClockState.FIND_WORKBENCH
                        launchFeishu()
                    }
                }
            }
            "ACTION_START_CLOCK_IN" -> {
                // （备用兼容）用于单独调试打开飞书功能
                Log.d("AutoClock", "独立触发飞书启动流程")
                currentState = ClockState.FIND_WORKBENCH
                launchFeishu()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun launchFeishu() {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(FEISHU_PACKAGE_NAME)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(launchIntent)
                Log.d("AutoClock", "成功拉起飞书，切换至 FIND_WORKBENCH")
            } else {
                Log.e("AutoClock", "未找到飞书包: $FEISHU_PACKAGE_NAME")
                currentState = ClockState.IDLE
            }
        } catch (e: Exception) {
            Log.e("AutoClock", "拉起异常: ${e.message}")
            currentState = ClockState.IDLE
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (currentState == ClockState.IDLE || event == null) return
        val rootNode = rootInActiveWindow ?: return

        when (currentState) {
            ClockState.FIND_WORKBENCH -> {
                for (node in rootNode.findAccessibilityNodeInfosByText("工作台")) {
                    if (clickNode(node)) {
                        currentState = ClockState.FIND_CLOCK_APP
                        return
                    }
                }
            }
            ClockState.FIND_CLOCK_APP -> {
                for (node in rootNode.findAccessibilityNodeInfosByText("打卡")) {
                    if (clickNode(node)) {
                        currentState = ClockState.FIND_CLOCK_BUTTON
                        return
                    }
                }
            }
            ClockState.FIND_CLOCK_BUTTON -> {
                val targets = rootNode.findAccessibilityNodeInfosByText("上班打卡") + 
                              rootNode.findAccessibilityNodeInfosByText("下班打卡")
                for (node in targets) {
                    if (clickNode(node)) {
                        Log.d("AutoClock", "打卡大成功！")
                        currentState = ClockState.DONE
                        handler.postDelayed({
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            UnlockHelper.releaseWakeLock() // 最后释放亮屏锁，彻底休眠
                            currentState = ClockState.IDLE
                        }, 5000)
                        return
                    }
                }
            }
            ClockState.DONE -> {}
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
