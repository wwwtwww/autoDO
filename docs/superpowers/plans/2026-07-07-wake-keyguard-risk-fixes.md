# Wake Keyguard Risk Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the lock-screen wake flow fail safely when required permissions or unlock conditions are missing.

**Architecture:** Keep the existing wake stack: Receiver WakeLock, full-screen notification, direct Activity best-effort, WakeActivity window flags, and WakeActivity WakeLock. Add missing MainActivity checks for secure lockscreen and Android 14+ full-screen intent permission, and prevent WakeActivity from launching the clock service when keyguard dismissal fails.

**Tech Stack:** Android Kotlin, Activity/NotificationManager/KeyguardManager APIs, Gradle Android plugin.

---

## File Structure

- Modify: `app/src/main/java/com/lark/autoclock/MainActivity.kt`
  - Adds secure lockscreen warning and Android 14+ full-screen intent permission guidance.
- Modify: `app/src/main/java/com/lark/autoclock/WakeActivity.kt`
  - Tracks keyguard dismissal failure and skips starting the accessibility service if unlock cannot proceed.
- Modify: `app/src/main/java/com/lark/autoclock/scheduler/ClockActionReceiver.kt`
  - Clarifies direct Activity launch as a best-effort fallback in logs and comments.

### Task 1: MainActivity Permission and Lockscreen Guards

**Files:**
- Modify: `app/src/main/java/com/lark/autoclock/MainActivity.kt`

- [ ] **Step 1: Add imports**

Add imports near the existing Android imports:

```kotlin
import android.app.KeyguardManager
import android.app.NotificationManager
```

- [ ] **Step 2: Add prompt preference keys**

Add these properties near `KEY_BATTERY_PROMPTED`:

```kotlin
private val KEY_LOCKSCREEN_PROMPTED = "lockscreen_prompted"
private val KEY_FULL_SCREEN_PROMPTED = "full_screen_prompted"
```

- [ ] **Step 3: Add secure lockscreen warning helper**

Add this method before `requestIgnoreBatteryOptimization()`:

```kotlin
private fun showSecureLockscreenWarning() {
    AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
        .setTitle("锁屏密码会阻止自动打卡")
        .setMessage("当前设备启用了 PIN、图案、指纹、人脸或其他安全锁屏。Android 不允许应用自动绕过这些验证。若要在锁屏状态自动打卡，请将测试手机锁屏方式改为无密码或滑动解锁。")
        .setPositiveButton("去锁屏设置") { _, _ ->
            try {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "请手动打开系统锁屏设置", Toast.LENGTH_LONG).show()
            }
        }
        .setNegativeButton("我知道了", null)
        .show()
}
```

- [ ] **Step 4: Add full-screen permission helper**

Add this method after `showSecureLockscreenWarning()`:

```kotlin
private fun requestFullScreenIntentPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        Toast.makeText(this, "请允许全屏通知，否则锁屏时可能无法唤醒打卡界面", Toast.LENGTH_LONG).show()
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}
```

- [ ] **Step 5: Add serial checks in `onResume()`**

Inside `onResume()`, after `val hasPrompted = prefs.getBoolean(KEY_BATTERY_PROMPTED, false)`, add lockscreen and full-screen prompt state:

```kotlin
val hasLockscreenPrompted = prefs.getBoolean(KEY_LOCKSCREEN_PROMPTED, false)
val hasFullScreenPrompted = prefs.getBoolean(KEY_FULL_SCREEN_PROMPTED, false)
```

Then before the battery optimization check, add:

```kotlin
val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
if (keyguardManager.isKeyguardSecure && !hasLockscreenPrompted) {
    prefs.edit().putBoolean(KEY_LOCKSCREEN_PROMPTED, true).apply()
    showSecureLockscreenWarning()
    return
}

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (!notificationManager.canUseFullScreenIntent() && !hasFullScreenPrompted) {
        prefs.edit().putBoolean(KEY_FULL_SCREEN_PROMPTED, true).apply()
        requestFullScreenIntentPermission()
        return
    }
}
```

### Task 2: WakeActivity Fail-Safe Unlock Handling

**Files:**
- Modify: `app/src/main/java/com/lark/autoclock/WakeActivity.kt`

- [ ] **Step 1: Add unlock state property**

Add near `mainHandler`:

```kotlin
private var keyguardDismissFailed = false
```

- [ ] **Step 2: Mark keyguard dismissal failures**

In `requestDismissKeyguard` callbacks, update `onDismissError()` and `onDismissCancelled()`:

```kotlin
override fun onDismissError() {
    keyguardDismissFailed = true
    Log.e("WakeActivity", "锁屏消除失败，跳过自动打卡")
}
override fun onDismissCancelled() {
    keyguardDismissFailed = true
    Log.w("WakeActivity", "锁屏消除被取消，跳过自动打卡")
}
```

- [ ] **Step 3: Add helper to release and finish**

Add this method before `onDestroy()`:

```kotlin
private fun releaseLocksAndFinish() {
    com.lark.autoclock.scheduler.ClockActionReceiver.releaseWakeLock()
    if (wakeLock?.isHeld == true) {
        try {
            wakeLock?.release()
        } catch (e: Exception) {
            Log.e("WakeActivity", "释放 WakeLock 异常: ${e.message}")
        }
    }
    finish()
}
```

- [ ] **Step 4: Gate service startup**

Replace the delayed block that starts `AutoClockAccessibilityService` with:

```kotlin
mainHandler.postDelayed({
    val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    if (keyguardDismissFailed || keyguardManager.isKeyguardLocked && keyguardManager.isKeyguardSecure) {
        Log.w("WakeActivity", "仍处于安全锁屏或解锁失败，终止本次自动打卡")
        releaseLocksAndFinish()
        return@postDelayed
    }

    if (chainAction == "ACTION_START_CLOCK_IN") {
        Log.d("WakeActivity", "正在触发飞书打卡流...")
        val serviceIntent = Intent(this, AutoClockAccessibilityService::class.java)
        serviceIntent.action = "ACTION_START_CLOCK_IN"
        startService(serviceIntent)
    }
    mainHandler.postDelayed({
        releaseLocksAndFinish()
    }, 3000)
}, 2000)
```

- [ ] **Step 5: Reuse helper in `onDestroy()`**

Keep `onDestroy()` cleanup direct, but avoid calling `finish()` from `onDestroy()`. The existing release code can remain as a duplicate safety net.

### Task 3: Clarify Receiver Direct Launch Fallback

**Files:**
- Modify: `app/src/main/java/com/lark/autoclock/scheduler/ClockActionReceiver.kt`

- [ ] **Step 1: Update direct launch comment and logs**

Replace the direct launch comment and success/failure logs with wording that marks it as best-effort:

```kotlin
// ======== 第 3 层：best-effort 直接启动（Android 10+ / 部分 ROM 可能拦截，不能作为成功依据）========
```

```kotlin
Log.d("AutoClock", "已尝试直接启动 WakeActivity（best-effort，实际可能被系统拦截）")
```

```kotlin
Log.e("AutoClock", "直接启动 WakeActivity 被拦截或失败，仅依赖全屏通知路径: ${e.message}")
```

### Task 4: Verification

**Files:**
- Verify: `app/src/main/java/com/lark/autoclock/MainActivity.kt`
- Verify: `app/src/main/java/com/lark/autoclock/WakeActivity.kt`
- Verify: `app/src/main/java/com/lark/autoclock/scheduler/ClockActionReceiver.kt`

- [ ] **Step 1: Run whitespace check**

Run: `git diff --check`

Expected: no whitespace errors. A line-ending warning is acceptable if no error is reported.

- [ ] **Step 2: Run unit tests if Gradle is available**

Run: `./gradlew testDebugUnitTest`

Expected: BUILD SUCCESSFUL. If `gradlew` is missing and system `gradle` is unavailable, record this verification blocker.

- [ ] **Step 3: Run debug build if Gradle is available**

Run: `./gradlew assembleDebug`

Expected: BUILD SUCCESSFUL. If `gradlew` is missing and system `gradle` is unavailable, record this verification blocker.

## Self-Review

- Spec coverage: full-screen permission, secure lockscreen warning, unlock failure gating, and direct launch semantics are all covered.
- Placeholder scan: no TODO/TBD placeholders remain.
- Type consistency: `KEY_LOCKSCREEN_PROMPTED`, `KEY_FULL_SCREEN_PROMPTED`, `keyguardDismissFailed`, and `releaseLocksAndFinish` are consistently named.
