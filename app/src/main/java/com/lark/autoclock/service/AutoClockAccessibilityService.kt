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
     * 状态机：
     * IDLE         -> 空闲，不响应任何事件
     * FIND_JIAQIN  -> 在桌面寻找"假勤"快捷方式
     * FIND_CLOCK   -> 进入假勤页面后，寻找"打卡"入口
     * FIND_BUTTON  -> 进入打卡页面后，寻找"上班打卡"或"下班打卡"按钮
     * DONE         -> 打卡完成，等待回到桌面
     */
    enum class ClockState {
        IDLE, FIND_JIAQIN, FIND_CLOCK, FIND_BUTTON, DONE
    }

    private var currentState = ClockState.IDLE
    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0
    private val MAX_RETRY = 30

    companion object {
        const val FEISHU_PACKAGE_NAME = "com.ss.android.lark"
        const val TAG = "AutoClock"
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_START_CLOCK_IN" -> {
                Log.d(TAG, "=== 收到打卡指令，启动桌面假勤快捷方式 ===")
                retryCount = 0
                currentState = ClockState.FIND_JIAQIN

                showToast("正在回到桌面，尝试寻找'假勤'快捷方式...")
                performGlobalAction(GLOBAL_ACTION_HOME)

                // 主动轮询扫描桌面：从 1.5 秒后开始，每隔 1.5 秒扫描一次，最多 5 轮
                scheduleActiveJiaqinScan(attemptNumber = 1, maxAttempts = 5)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 主动轮询扫描桌面上的"假勤"快捷方式。
     * 不依赖 onAccessibilityEvent 回调，因为桌面已在前台时可能不会触发新事件。
     */
    private fun scheduleActiveJiaqinScan(attemptNumber: Int, maxAttempts: Int) {
        handler.postDelayed({
            if (currentState != ClockState.FIND_JIAQIN) return@postDelayed // 已被事件回调找到了

            Log.d(TAG, "主动扫描桌面第 $attemptNumber 轮...")
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                // 打印当前窗口的包名，帮助调试
                Log.d(TAG, "当前窗口包名: ${rootNode.packageName}")

                // 策略1: 使用系统 API 搜索（同时搜索 text 和 contentDescription）
                val found = tryFindAndClickJiaqin(rootNode)
                if (found) return@postDelayed

                // 策略2: 深度递归遍历节点树搜索
                val foundDeep = deepSearchAndClickJiaqin(rootNode)
                if (foundDeep) return@postDelayed

                // 都没找到，最后一轮输出节点树帮助调试
                if (attemptNumber == maxAttempts) {
                    Log.w(TAG, "=== 最后一轮仍未找到，输出桌面节点树用于调试 ===")
                    dumpNodeTree(rootNode, depth = 0)
                }
            } else {
                Log.w(TAG, "主动扫描: rootInActiveWindow 为 null")
            }

            if (attemptNumber < maxAttempts) {
                scheduleActiveJiaqinScan(attemptNumber + 1, maxAttempts)
            } else {
                // 所有轮次扫描完毕仍未找到，触发回退
                if (currentState == ClockState.FIND_JIAQIN) {
                    Log.w(TAG, "桌面未找到'假勤'快捷方式，回退为启动飞书主页")
                    showToast("未找到桌面'假勤'快捷方式，尝试直接拉起飞书...")
                    launchFeishuFallback()
                }
            }
        }, 1500)
    }

    /**
     * 策略1：通过 findAccessibilityNodeInfosByText 搜索"假勤"
     */
    private fun tryFindAndClickJiaqin(rootNode: AccessibilityNodeInfo): Boolean {
        val nodes = rootNode.findAccessibilityNodeInfosByText("假勤")
        Log.d(TAG, "findByText('假勤') 找到 ${nodes.size} 个节点")
        for (node in nodes) {
            Log.d(TAG, "  候选节点: text='${node.text}', desc='${node.contentDescription}', class=${node.className}, clickable=${node.isClickable}")
            if (clickNode(node)) {
                Log.d(TAG, "成功点击桌面上的'假勤'快捷方式！")
                showToast("已点击'假勤'，等待飞书打开...")
                currentState = ClockState.FIND_CLOCK
                retryCount = 0
                return true
            }
        }
        return false
    }

    /**
     * 策略2：深度递归遍历整个节点树，检查 text 和 contentDescription 是否包含"假勤"
     */
    private fun deepSearchAndClickJiaqin(rootNode: AccessibilityNodeInfo): Boolean {
        val matchingNodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodesByKeyword(rootNode, "假勤", matchingNodes)
        Log.d(TAG, "深度递归搜索找到 ${matchingNodes.size} 个匹配节点")
        for (node in matchingNodes) {
            Log.d(TAG, "  深度匹配: text='${node.text}', desc='${node.contentDescription}', class=${node.className}")
            if (clickNode(node)) {
                Log.d(TAG, "通过深度搜索成功点击'假勤'！")
                showToast("已点击'假勤'，等待飞书打开...")
                currentState = ClockState.FIND_CLOCK
                retryCount = 0
                return true
            }
        }
        return false
    }

    /**
     * 递归收集节点树中 text 或 contentDescription 包含关键词的所有节点
     */
    private fun collectNodesByKeyword(
        node: AccessibilityNodeInfo,
        keyword: String,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.contains(keyword) || desc.contains(keyword)) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodesByKeyword(child, keyword, result)
        }
    }

    /**
     * 调试用：打印节点树结构（最多 3 层深度）
     */
    private fun dumpNodeTree(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 3) return
        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.isNotEmpty() || desc.isNotEmpty()) {
            Log.d(TAG, "${indent}[${node.className}] text='$text' desc='$desc' clickable=${node.isClickable}")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNodeTree(child, depth + 1)
        }
    }

    /**
     * 回退方案：直接启动飞书主应用
     */
    private fun launchFeishuFallback() {
        currentState = ClockState.FIND_CLOCK
        retryCount = 0
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(FEISHU_PACKAGE_NAME)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(launchIntent)
                Log.d(TAG, "成功拉起飞书主应用")
                showToast("已拉起飞书，正在扫描页面...")
            } else {
                Log.e(TAG, "未找到飞书包: $FEISHU_PACKAGE_NAME")
                showToast("未找到飞书 App，打卡中止")
                currentState = ClockState.IDLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "拉起飞书异常: ${e.message}")
            showToast("启动飞书异常: ${e.message}")
            currentState = ClockState.IDLE
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (currentState == ClockState.IDLE || currentState == ClockState.DONE || event == null) return

        // FIND_JIAQIN 阶段：不过滤包名（需要接收 Launcher 事件）
        // FIND_CLOCK / FIND_BUTTON 阶段：只处理飞书事件，降低开销
        if (currentState == ClockState.FIND_CLOCK || currentState == ClockState.FIND_BUTTON) {
            if (event.packageName?.toString() != FEISHU_PACKAGE_NAME) return
        }

        val rootNode = rootInActiveWindow ?: return

        retryCount++
        if (retryCount > MAX_RETRY) {
            Log.e(TAG, "超过最大重试次数，自动中止并回到桌面")
            showToast("打卡流程超时，未找到对应按钮！")
            currentState = ClockState.IDLE
            retryCount = 0
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        when (currentState) {
            ClockState.FIND_JIAQIN -> {
                // 事件驱动的扫描（作为主动轮询的补充）
                if (tryFindAndClickJiaqin(rootNode) || deepSearchAndClickJiaqin(rootNode)) {
                    return
                }
            }
            ClockState.FIND_CLOCK -> {
                // 在假勤页面中寻找"打卡"入口
                val nodes = rootNode.findAccessibilityNodeInfosByText("打卡")
                for (node in nodes) {
                    val nodeText = node.text?.toString() ?: ""
                    if (nodeText.contains("上班") || nodeText.contains("下班")) continue

                    if (clickNode(node)) {
                        Log.d(TAG, "成功点击'打卡'入口，进入打卡页面")
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
                        Log.d(TAG, "=== 打卡大成功！===")
                        showToast("打卡大成功！")
                        currentState = ClockState.DONE
                        retryCount = 0
                        handler.postDelayed({
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            currentState = ClockState.IDLE
                            Log.d(TAG, "已返回桌面，流程结束")
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
