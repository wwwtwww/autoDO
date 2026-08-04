# 代码审查报告 — autoDO 全量审查 (2026-08-04)

> 审查范围：全量代码（15 个源文件、3 个测试文件、5 个资源文件及构建配置）
> 审查维度：完整性 (completeness) / 正确性 (correctness) / 副作用 (impact)

---

## Critical Issues (MUST FIX)

### 1. AndroidManifest.xml 中 `WRITE_SECURE_SETTINGS` 权限重复声明

**位置**: [AndroidManifest.xml#L13-L20](../../app/src/main/AndroidManifest.xml)

**Problem**: 第 13 行和第 20 行都声明了 `android.permission.WRITE_SECURE_SETTINGS`，属于重复声明。Android 构建系统虽然不会报错，但会产生 lint 警告，且表明开发者可能对权限块做了复制粘贴而未清理。

**Fix**: 删除第 19-20 行的重复注释及重复的 `WRITE_SECURE_SETTINGS` 声明：

```diff
-    <!-- 允许通过adb shell pm grant授权来自动开启无障碍服务 -->
-    <uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" />
```

---

### 2. Kotlin 通知代码中大量硬编码用户可见字符串

**位置**:
- [WakeActivity.kt#L193-L214](../../app/src/main/java/com/lark/autoclock/WakeActivity.kt)
- [ClockActionReceiver.kt#L65-L88](../../app/src/main/java/com/lark/autoclock/scheduler/ClockActionReceiver.kt)
- [KeepAliveService.kt#L59-L150](../../app/src/main/java/com/lark/autoclock/service/KeepAliveService.kt)

**Problem**: 三个文件中直接硬编码了通知渠道名称、通知标题和通知正文（如 `"无障碍断连告警"`、`"autoDO 打卡触发"`、`"autoDO 守护进程"` 等），违反了项目的「字符串资源提取规范」——所有界面文本必须提取至 `strings.xml` 集中管理。通知文本属于用户可见文本，应同样遵守此规范。

**Fix** (方向性建议): 在 `strings.xml` 中新增以下条目并在代码中引用：

```xml
<!-- 通知渠道 -->
<string name="channel_name_alert">无障碍断连告警</string>
<string name="channel_desc_alert">无障碍服务被系统断连时的高优先级告警</string>
<string name="channel_name_wake">打卡唤醒通知</string>
<string name="channel_desc_wake">用于在后台点亮屏幕并触发打卡</string>
<string name="channel_name_keepalive">保活通知</string>
<string name="channel_desc_keepalive">前台服务常驻通知，防止备用机深度休眠导致打卡遗漏</string>

<!-- 通知文案 -->
<string name="notif_alert_title">autoDO 自动打卡失败</string>
<string name="notif_alert_text">[%1$s] 无障碍服务未连接，请重新开启无障碍</string>
<string name="notif_wake_title">autoDO 打卡触发</string>
<string name="notif_wake_text">正在强制唤醒屏幕并执行打卡...</string>
<string name="notif_keepalive_title">autoDO 守护进程</string>
<string name="notif_keepalive_text">保活运行中，确保定时打卡不遗漏</string>
<string name="notif_disconnect_title">无障碍服务已断连</string>
<string name="notif_disconnect_text">autoDO 无法自动打卡，点击重新开启无障碍服务</string>
```

然后在 Kotlin 代码中使用 `context.getString(R.string.xxx)` 替换所有硬编码字符串。

---

## Warnings (SHOULD FIX)

### 3. WakeActivity.onNewIntent 与 finishReceiver 存在竞态条件

**位置**: [WakeActivity.kt#L275-L296](../../app/src/main/java/com/lark/autoclock/WakeActivity.kt)

**Problem**: 当 `WakeActivity`（`singleInstance` 启动模式）正在执行一次打卡流程时收到新 Intent，`onNewIntent` 调用 `mainHandler.removeCallbacksAndMessages(null)` 清除了所有待执行的回调，但 **未重新注册或清理旧的 `finishReceiver`**。如果上一次打卡流程的 `goHomeAndReset()` 恰好在此时发送 `ACTION_CLOCK_FINISHED` 广播，已注册的旧 `finishReceiver` 会调用 `releaseLocksAndFinish()`，在新流程还没启动完成时就销毁 Activity。

**Fix**: 在 `onNewIntent` 中，在 `removeCallbacksAndMessages(null)` 之后、启动新流程之前，先反注册旧的 `finishReceiver` 并重置标志：

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (isFinishing) return

    Log.d("WakeActivity", "onNewIntent: 收到新 Intent，重置并重新触发打卡流程")
    mainHandler.removeCallbacksAndMessages(null)

    // 反注册旧的 finishReceiver，防止上一个流程的残留广播中断新流程
    if (isReceiverRegistered) {
        try {
            unregisterReceiver(finishReceiver)
        } catch (e: Exception) {
            Log.e("WakeActivity", "onNewIntent 反注册旧广播异常: ${e.message}")
        }
        isReceiverRegistered = false
    }

    // ... 后续逻辑不变 ...
}
```

---

### 4. TIMEOUT_WAKE_ACTIVITY_FALLBACK 在最差重试场景下余量不足

**位置**: [Constants.kt#L28](../../app/src/main/java/com/lark/autoclock/Constants.kt), [WakeActivity.kt#L101-L159](../../app/src/main/java/com/lark/autoclock/WakeActivity.kt)

**Problem**: 兜底超时 `TIMEOUT_WAKE_ACTIVITY_FALLBACK = 55000L` (55s) 从 t=2s 的 `postDelayed` 块内开始计时，在 t=57s 触发。如果无障碍服务在第 3 次重试时才连接（t=8s），则 `startClockIn` 的 45s 扫描超时在 t=53s 触发，`goHomeAndReset` 的 3s 延迟在 t=56s 发送完成广播。此时兜底超时与完成广播之间仅有 **1 秒余量**。任何主线程调度延迟都会导致兜底超时先于完成广播触发，提前销毁 Activity。

**时间线分析**（重试路径为 t=2s→5s→8s 三次尝试，服务在 t=8s 连接）:

| 事件 | 正常路径 (t=) | 最差重试路径 (t=) |
|---|---|---|
| `scheduleFallbackTimeout()` 调用 | 2s | 2s |
| 服务连接 / `startClockIn` 调用 | 2s | 8s |
| 无障碍扫描超时 (`TIMEOUT_ACCESSIBILITY_SCAN`) | 47s | 53s |
| `goHomeAndReset` 3s 延迟后发送完成广播 | 50s | 56s |
| **兜底超时触发** (`TIMEOUT_WAKE_ACTIVITY_FALLBACK`) | **57s** | **57s** |
| 安全余量 | 7s | **1s ⚠️** |

**Fix**: 将 `TIMEOUT_WAKE_ACTIVITY_FALLBACK` 提升至至少 65000L，以覆盖最差路径 (2s 初始延迟 + 6s 重试 + 45s 扫描 + 3s 归位 = 56s，再加上安全余量)：

```kotlin
const val TIMEOUT_WAKE_ACTIVITY_FALLBACK = 65000L  // WakeActivity 兜底销毁超时 (65s)
```

---

### 5. scheduleNextWorkdayClockInInAdvance 仅预下发上班打卡，缺少下班保底

**位置**: [ClockScheduler.kt#L121-L155](../../app/src/main/java/com/lark/autoclock/scheduler/ClockScheduler.kt)

**Problem**: 周末保底逻辑只预下发下一个工作日的 **上班** 打卡闹钟 (request code 1001)，未预下发 **下班** 打卡闹钟 (request code 1002)。如果那个工作日的凌晨 00:30 `DailySetupReceiver` 因 Doze 被延迟或冻结，下班闹钟将不会被设置，导致漏打下班卡。

**Fix**: 在 `scheduleNextWorkdayClockInInAdvance` 中，找到下一个工作日后同时预下发上班和下班闹钟：

```kotlin
// 在 setExactAlarm(context, alarmManager, 1001, clockInCal.timeInMillis, Constants.CLOCK_TYPE_CLOCK_IN) 之后追加:

// 同时预下发下班打卡保底闹钟
val (aStartHour, aStartMin, aEndHour, aEndMin) = try {
    val s = (prefs.getString("afternoon_start", "18:00") ?: "18:00").split(":")
    val e = (prefs.getString("afternoon_end", "18:10") ?: "18:10").split(":")
    listOf(s[0].toInt(), s[1].toInt(), e[0].toInt(), e[1].toInt())
} catch (ex: Exception) {
    listOf(18, 0, 18, 10)
}

val aStartTotalMins = aStartHour * 60 + aStartMin
val aEndTotalMins = aEndHour * 60 + aEndMin
val aDiff = (aEndTotalMins - aStartTotalMins).coerceAtLeast(0)
val clockOutMinuteOffset = if (aDiff > 0) Random.nextInt(0, aDiff + 1) else 0

val clockOutCal = (calendar.clone() as Calendar).apply {
    set(Calendar.HOUR_OF_DAY, aStartHour)
    set(Calendar.MINUTE, aStartMin + clockOutMinuteOffset)
    set(Calendar.SECOND, Random.nextInt(0, 60))
    set(Calendar.MILLISECOND, 0)
}
setExactAlarm(context, alarmManager, 1002, clockOutCal.timeInMillis, Constants.CLOCK_TYPE_CLOCK_OUT)
Log.d("AutoClock", "【周末/长休防护】已预先下发最近工作日下班打卡保底闹钟: ${clockOutCal.time}")
```

---

### 6. recordAccessibilityFailure 使用裸 Thread 而非结构化协程

**位置**: [WakeActivity.kt#L167-L180](../../app/src/main/java/com/lark/autoclock/WakeActivity.kt)

**Problem**: 使用 `Thread { ... }.start()` 进行文件 I/O，与项目其他地方（如 `AutoClockAccessibilityService` 中的 `ioScope.launch`）使用结构化协程的做法不一致。裸线程无法被取消，如果 Activity 被销毁后线程仍在运行，可能产生竞态写入。

**Fix**: 改用 `lifecycleScope`（Activity 已有 `androidx.lifecycle` 依赖）：

```kotlin
private fun recordAccessibilityFailure(clockType: String) {
    val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    val logLine = "[$timeStr] [$clockType] ❌无障碍断连 - 无障碍服务未连接，打卡未执行\n"

    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val logFile = File(filesDir, "clock_log.txt")
            logFile.appendText(logLine)

            val lines = logFile.readLines()
            if (lines.size > 250) {
                logFile.writeText(lines.takeLast(200).joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
            Log.e("WakeActivity", "写入失败日志异常: ${e.message}")
        }
    }
}
```

（需额外 import `androidx.lifecycle.lifecycleScope` 和 `kotlinx.coroutines.launch`）

---

## Suggestions (CONSIDER)

### 7. onNewIntent 跳过 2 秒屏幕唤醒延迟

**位置**: [WakeActivity.kt#L275-L296](../../app/src/main/java/com/lark/autoclock/WakeActivity.kt)

**Problem**: `onCreate` 中通过 `postDelayed({...}, 2000)` 等待 2 秒亮屏动画完成后再启动打卡流程，但 `onNewIntent` 直接调用 `tryStartClockInWithRetry` 而无等待。如果设备从深度 Doze 中刚被唤醒，屏幕可能尚未完全亮起，飞书的 AccessibilityEvent 可能因窗口未绘制而丢失。

**Fix** (方向性建议): 在 `onNewIntent` 中也加入短暂延迟（如 1 秒），或复用 `onCreate` 中的延迟模式。

---

### 8. 通知渠道重复创建逻辑可抽取为共享工具

**位置**:
- [WakeActivity.kt#L191-L198](../../app/src/main/java/com/lark/autoclock/WakeActivity.kt)
- [KeepAliveService.kt#L154-L165](../../app/src/main/java/com/lark/autoclock/service/KeepAliveService.kt)

**Problem**: `WakeActivity.sendAccessibilityAlertNotification` 和 `KeepAliveService.createNotificationChannel` 都创建了 `CHANNEL_ID_ALERT` 通知渠道。虽然 `createNotificationChannel` 是幂等操作不会出错，但代码重复。

**Fix** (方向性建议): 将通知渠道创建逻辑抽取到一个 `NotificationUtil` 工具类中，统一管理所有渠道的创建。

---

### 9. scheduleNextWorkdayClockInInAdvance 中 calendar.apply 直接修改循环迭代变量

**位置**: [ClockScheduler.kt#L144-L149](../../app/src/main/java/com/lark/autoclock/scheduler/ClockScheduler.kt)

**Problem**: `clockInCal = calendar.apply { set(...) }` 返回的是 `calendar` 本身，直接修改了循环中正在迭代的 `Calendar` 对象。虽然因为 `break` 语句使得此处不会产生 bug，但代码意图不够清晰，未来如果移除 `break` 或重构逻辑可能导致难以追踪的问题。

**Fix** (方向性建议): 使用 `calendar.clone()` 创建副本，或直接构造新的 `Calendar` 实例。

---

## Summary of Changes

- **全量代码审查覆盖了全部 15 个源文件、3 个测试文件、5 个资源文件及构建配置**，从完整性、正确性和副作用三个维度进行了深度分析。
- **发现 2 个关键问题**：AndroidManifest 权限重复声明、3 个 Kotlin 文件中通知文本硬编码违反字符串资源提取规范。
- **发现 4 个应修复问题**：`onNewIntent` 与 `finishReceiver` 竞态、兜底超时在最差重试路径下仅 1 秒余量、周末保底逻辑缺失下班闹钟、`recordAccessibilityFailure` 使用裸线程。
- **发现 3 个改进建议**：`onNewIntent` 跳过亮屏延迟、通知渠道创建逻辑重复、`calendar.apply` 修改循环变量。
- **代码整体质量良好**：无障碍服务单例化方案、防串号校验、Doze 补偿决策等核心逻辑设计合理，单元测试覆盖了关键边界条件。
