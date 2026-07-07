# Accessibility Risk Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce false positive clock confirmations, lower accessibility scan overhead, and ensure the service state resets even when returning home fails.

**Architecture:** Keep the existing `AutoClockAccessibilityService` flow and add a small, plain-Kotlin `ClockSuccessMatcher` predicate. Detection requires a specific success keyword plus clock-context text, scans are throttled, tree text collection reuses one `StringBuilder`, and global HOME failures are logged without blocking state reset.

**Tech Stack:** Android AccessibilityService, Kotlin, JUnit 4, Gradle Android plugin.

---

## File Structure

- Modify: `app/src/main/java/com/lark/autoclock/service/AutoClockAccessibilityService.kt`
  - Owns accessibility event handling, keyword/context matching, scan throttling, node cleanup, and state reset.
- Create: `app/src/main/java/com/lark/autoclock/service/ClockSuccessMatcher.kt`
  - Owns pure text matching rules so JVM tests do not depend on Android service classes.
- Create: `app/src/test/java/com/lark/autoclock/service/AutoClockAccessibilityServiceTest.kt`
  - JVM tests for the pure confirmation predicate.

### Task 1: Add Confirmation Predicate Tests

**Files:**
- Create: `app/src/test/java/com/lark/autoclock/service/AutoClockAccessibilityServiceTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.lark.autoclock.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoClockAccessibilityServiceTest {
    @Test
    fun `chat text with clock phrase is not confirmed`() {
        val text = "张三 我今天已打卡了哈 输入消息 发送 聊天记录"

        assertFalse(AutoClockAccessibilityService.isConfirmedClockSuccessText(text))
    }

    @Test
    fun `clock time alone is not confirmed`() {
        val text = "打卡时间 09:01 已于 今天"

        assertFalse(AutoClockAccessibilityService.isConfirmedClockSuccessText(text))
    }

    @Test
    fun `success text with attendance context is confirmed`() {
        val text = "考勤 上班 打卡成功 打卡范围内 打卡时间 09:01"

        assertTrue(AutoClockAccessibilityService.isConfirmedClockSuccessText(text))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.lark.autoclock.service.AutoClockAccessibilityServiceTest"`

Expected: FAIL because `AutoClockAccessibilityService.isConfirmedClockSuccessText` does not exist.

### Task 2: Implement Conservative Matching and Scan Throttling

**Files:**
- Modify: `app/src/main/java/com/lark/autoclock/service/AutoClockAccessibilityService.kt`

- [ ] **Step 1: Add conservative constants and predicate**

Add these members in the `companion object`:

```kotlin
private const val MIN_SCAN_INTERVAL_MS = 500L

val CLOCK_SUCCESS_KEYWORDS = listOf(
    "打卡成功", "上班·已打卡", "下班·已打卡", "上班已打卡", "下班已打卡"
)

private val CLOCK_CONTEXT_KEYWORDS = listOf(
    "考勤", "打卡", "上班", "下班", "打卡范围", "打卡地点"
)

private val CHAT_INDICATORS = listOf("输入消息", "发送", "消息记录", "聊天记录", "回复")

fun isConfirmedClockSuccessText(allText: String): Boolean {
    val isChatPage = CHAT_INDICATORS.count { allText.contains(it) } >= 2
    if (isChatPage) return false

    val hasSuccessKeyword = CLOCK_SUCCESS_KEYWORDS.any { allText.contains(it) }
    val hasClockContext = CLOCK_CONTEXT_KEYWORDS.any { allText.contains(it) }
    return hasSuccessKeyword && hasClockContext
}
```

- [ ] **Step 2: Add scan timestamp state**

Add this property near `retryCount`:

```kotlin
private var lastScanAt = 0L
```

- [ ] **Step 3: Reset scan timestamp on new command**

In `onStartCommand`, after `retryCount = 0`, add:

```kotlin
lastScanAt = 0L
```

- [ ] **Step 4: Throttle event scanning**

At the start of `onAccessibilityEvent`, after the package-name check, add:

```kotlin
val now = System.currentTimeMillis()
if (now - lastScanAt < MIN_SCAN_INTERVAL_MS) return
lastScanAt = now
```

- [ ] **Step 5: Replace old matching flow**

Replace the chat guard, `findAccessibilityNodeInfosByText` loop, and `allText.contains` loop with:

```kotlin
val matchedText = findFirstSuccessNodeText(rootNode)
val confirmed = isConfirmedClockSuccessText(allText)
if (matchedText != null && confirmed) {
    Log.d(TAG, "=== 检测到可信极速打卡成功标志: '$matchedText' ===")
    val msg = "极速打卡成功！检测到: $matchedText"
    showToast("✅ $msg")
    recordClockResult(confirmed = true, detail = msg)
    sendResultNotification(confirmed = true, detail = matchedText)
    goHomeAndReset()
    return
}

if (matchedText != null) {
    Log.d(TAG, "检测到打卡关键词但缺少考勤上下文，跳过确认: '$matchedText'")
}
```

- [ ] **Step 6: Implement node search cleanup helper**

Add this method before `collectAllText`:

```kotlin
private fun findFirstSuccessNodeText(rootNode: AccessibilityNodeInfo): String? {
    for (keyword in CLOCK_SUCCESS_KEYWORDS) {
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
```

- [ ] **Step 7: Replace recursive string allocation**

Replace `collectAllText` with:

```kotlin
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
```

- [ ] **Step 8: Reset state safely after HOME attempt**

Replace the `handler.postDelayed` body in `goHomeAndReset` with:

```kotlin
try {
    val homePerformed = performGlobalAction(GLOBAL_ACTION_HOME)
    if (!homePerformed) {
        Log.w(TAG, "返回桌面动作未被系统执行")
    }
} catch (e: Exception) {
    Log.e(TAG, "返回桌面动作异常: ${e.message}")
} finally {
    currentState = ClockState.IDLE
    lastScanAt = 0L
    Log.d(TAG, "流程结束，状态已重置")
}
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.lark.autoclock.service.AutoClockAccessibilityServiceTest"`

Expected: PASS with 3 tests passing.

### Task 3: Full Verification

**Files:**
- Verify: `app/src/main/java/com/lark/autoclock/service/AutoClockAccessibilityService.kt`
- Verify: `app/src/test/java/com/lark/autoclock/service/AutoClockAccessibilityServiceTest.kt`

- [ ] **Step 1: Run unit tests**

Run: `./gradlew testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run debug build**

Run: `./gradlew assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Inspect git diff**

Run: `git diff -- app/src/main/java/com/lark/autoclock/service/AutoClockAccessibilityService.kt app/src/test/java/com/lark/autoclock/service/AutoClockAccessibilityServiceTest.kt`

Expected: Diff only contains conservative matching, throttling, resource cleanup, safe reset, and tests.

## Self-Review

- Spec coverage: false positives, scan overhead, node cleanup, and HOME/state reset are all covered.
- Placeholder scan: no TODO/TBD placeholders remain.
- Type consistency: `isConfirmedClockSuccessText`, `findFirstSuccessNodeText`, and `lastScanAt` are consistently named.
