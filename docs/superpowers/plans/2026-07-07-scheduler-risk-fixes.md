# Scheduler Risk Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make holiday-status failures explicit while preserving the anti-missed-clock default of scheduling on unknown days.

**Architecture:** Keep the existing exact-alarm chain and timor.tech API. Add a typed `WorkdayStatus` result in `HolidayHelper`, make `DailySetupReceiver` handle `UNKNOWN` explicitly as a safe workday fallback, and simplify `BootReceiver` so one receiver path owns both tomorrow scheduling and today's check.

**Tech Stack:** Android Kotlin, BroadcastReceiver, AlarmManager, JUnit 4.

---

## File Structure

- Modify: `app/src/main/java/com/lark/autoclock/utils/HolidayHelper.kt`
  - Adds typed workday status parsing and keeps boolean compatibility.
- Modify: `app/src/main/java/com/lark/autoclock/scheduler/DailySetupReceiver.kt`
  - Handles `WORKDAY`, `RESTDAY`, and `UNKNOWN` explicitly.
- Modify: `app/src/main/java/com/lark/autoclock/scheduler/BootReceiver.kt`
  - Sends one daily setup broadcast instead of scheduling twice.
- Create: `app/src/test/java/com/lark/autoclock/utils/HolidayHelperTest.kt`
  - JVM tests for pure holiday API response parsing.

### Task 1: Add Holiday Parsing Tests

**Files:**
- Create: `app/src/test/java/com/lark/autoclock/utils/HolidayHelperTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.lark.autoclock.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class HolidayHelperTest {
    @Test
    fun `type 0 and 3 are workday`() {
        assertEquals(HolidayHelper.WorkdayStatus.WORKDAY, HolidayHelper.parseWorkdayStatus("""{"code":0,"type":{"type":0}}"""))
        assertEquals(HolidayHelper.WorkdayStatus.WORKDAY, HolidayHelper.parseWorkdayStatus("""{"code":0,"type":{"type":3}}"""))
    }

    @Test
    fun `type 1 and 2 are restday`() {
        assertEquals(HolidayHelper.WorkdayStatus.RESTDAY, HolidayHelper.parseWorkdayStatus("""{"code":0,"type":{"type":1}}"""))
        assertEquals(HolidayHelper.WorkdayStatus.RESTDAY, HolidayHelper.parseWorkdayStatus("""{"code":0,"type":{"type":2}}"""))
    }

    @Test
    fun `unknown code type or malformed json are unknown`() {
        assertEquals(HolidayHelper.WorkdayStatus.UNKNOWN, HolidayHelper.parseWorkdayStatus("""{"code":1}"""))
        assertEquals(HolidayHelper.WorkdayStatus.UNKNOWN, HolidayHelper.parseWorkdayStatus("""{"code":0,"type":{"type":9}}"""))
        assertEquals(HolidayHelper.WorkdayStatus.UNKNOWN, HolidayHelper.parseWorkdayStatus("not json"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails if Gradle is available**

Run: `./gradlew testDebugUnitTest --tests "com.lark.autoclock.utils.HolidayHelperTest"`

Expected: FAIL because `WorkdayStatus` and `parseWorkdayStatus` do not exist. If `gradlew` is missing and system `gradle` is unavailable, record this blocker.

### Task 2: Implement Typed Holiday Status

**Files:**
- Modify: `app/src/main/java/com/lark/autoclock/utils/HolidayHelper.kt`

- [ ] **Step 1: Add enum and parser**

Add inside `object HolidayHelper`:

```kotlin
enum class WorkdayStatus {
    WORKDAY, RESTDAY, UNKNOWN
}

fun parseWorkdayStatus(response: String): WorkdayStatus {
    return try {
        val jsonObject = JSONObject(response)
        if (jsonObject.getInt("code") != 0) return WorkdayStatus.UNKNOWN

        when (jsonObject.getJSONObject("type").getInt("type")) {
            0, 3 -> WorkdayStatus.WORKDAY
            1, 2 -> WorkdayStatus.RESTDAY
            else -> WorkdayStatus.UNKNOWN
        }
    } catch (e: Exception) {
        WorkdayStatus.UNKNOWN
    }
}
```

- [ ] **Step 2: Replace `isTodayWorkday` core with typed method**

Add this method:

```kotlin
fun getTodayWorkdayStatus(): WorkdayStatus {
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    val urlString = "https://timor.tech/api/holiday/info/$today"
    var connection: HttpURLConnection? = null

    return try {
        val url = URL(urlString)
        connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 3000
        connection.readTimeout = 3000

        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            parseWorkdayStatus(response)
        } else {
            Log.w("HolidayHelper", "节假日 API HTTP ${connection.responseCode}，状态未知")
            WorkdayStatus.UNKNOWN
        }
    } catch (e: Exception) {
        Log.w("HolidayHelper", "节假日 API 请求失败，状态未知: ${e.message}")
        WorkdayStatus.UNKNOWN
    } finally {
        connection?.disconnect()
    }
}
```

- [ ] **Step 3: Keep boolean compatibility wrapper**

Replace existing `isTodayWorkday()` with:

```kotlin
fun isTodayWorkday(): Boolean {
    return getTodayWorkdayStatus() != WorkdayStatus.RESTDAY
}
```

- [ ] **Step 4: Remove unused fallback method**

Delete `private fun isFallbackWorkday()` because unknown status no longer falls back to weekend-only logic.

### Task 3: Handle Unknown Explicitly in DailySetupReceiver

**Files:**
- Modify: `app/src/main/java/com/lark/autoclock/scheduler/DailySetupReceiver.kt`

- [ ] **Step 1: Replace boolean branch with status branch**

Replace the `val isWorkday = HolidayHelper.isTodayWorkday()` block with:

```kotlin
when (HolidayHelper.getTodayWorkdayStatus()) {
    HolidayHelper.WorkdayStatus.WORKDAY -> {
        Log.d("AutoClock", "API反馈今天是工作日，开始下发布置精准随机闹钟")
        ClockScheduler.scheduleTodayClockActions(context)
    }
    HolidayHelper.WorkdayStatus.RESTDAY -> {
        Log.d("AutoClock", "API反馈今天是休息日/节假日，跳过今天的打卡！")
    }
    HolidayHelper.WorkdayStatus.UNKNOWN -> {
        Log.w("AutoClock", "无法确认节假日状态，安全降级为工作日并下发打卡闹钟")
        ClockScheduler.scheduleTodayClockActions(context)
    }
}
```

### Task 4: Simplify BootReceiver Scheduling Path

**Files:**
- Modify: `app/src/main/java/com/lark/autoclock/scheduler/BootReceiver.kt`

- [ ] **Step 1: Remove duplicate direct scheduling**

Replace the boot handling block with:

```kotlin
if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
    Log.d("AutoClock", "设备已重启，触发今日任务检查并恢复明日调度")
    val checkIntent = Intent(context, DailySetupReceiver::class.java)
    context.sendBroadcast(checkIntent)
}
```

### Task 5: Verification

**Files:**
- Verify: `app/src/main/java/com/lark/autoclock/utils/HolidayHelper.kt`
- Verify: `app/src/main/java/com/lark/autoclock/scheduler/DailySetupReceiver.kt`
- Verify: `app/src/main/java/com/lark/autoclock/scheduler/BootReceiver.kt`
- Verify: `app/src/test/java/com/lark/autoclock/utils/HolidayHelperTest.kt`

- [ ] **Step 1: Run whitespace check**

Run: `git diff --check`

Expected: no whitespace errors. Git line-ending warnings are acceptable if there are no errors.

- [ ] **Step 2: Run targeted unit test if Gradle is available**

Run: `./gradlew testDebugUnitTest --tests "com.lark.autoclock.utils.HolidayHelperTest"`

Expected: PASS. If `gradlew` is missing and system `gradle` is unavailable, record this blocker.

- [ ] **Step 3: Run debug build if Gradle is available**

Run: `./gradlew assembleDebug`

Expected: BUILD SUCCESSFUL. If `gradlew` is missing and system `gradle` is unavailable, record this blocker.

## Self-Review

- Spec coverage: typed status, explicit unknown safe fallback, fallback removal, and boot scheduling cleanup are covered.
- Placeholder scan: no TODO/TBD placeholders remain.
- Type consistency: `WorkdayStatus`, `parseWorkdayStatus`, and `getTodayWorkdayStatus` are consistently named.
