package com.lark.autoclock.scheduler

import com.lark.autoclock.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 调度链冗余组件的单元测试：
 * 1. computeNextDailySetupTime —— 凌晨主链时刻计算的边界语义
 * 2. computeBackupSetupTime —— 主备链严格配对关系
 * 3. resolveRollingFallbackScope —— 滚动保底覆盖范围（锁死「上班卡绝不覆盖 1002 下班闹钟」不变量）
 */
class SchedulerRobustnessTest {

    private fun todayAt(hour: Int, minute: Int, second: Int = 0, millis: Int = 0): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, millis)
        }.timeInMillis

    private fun assertTimeIs(timeInMillis: Long, hour: Int, minute: Int) {
        val cal = Calendar.getInstance().apply { this.timeInMillis = timeInMillis }
        assertEquals(hour, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(minute, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
    }

    // ====================================================================================
    // computeNextDailySetupTime 边界测试
    // ====================================================================================

    @Test
    fun `nextDailySetup - 00_29 before deadline - returns today 00_30`() {
        val now = todayAt(0, 29)
        val result = ClockScheduler.computeNextDailySetupTime(now)
        assertEquals(todayAt(0, 30), result)
    }

    @Test
    fun `nextDailySetup - exactly 00_30_00_000 - rolls to tomorrow`() {
        // 闹钟时刻恰好等于当前时刻：已到期，必须顺延到明天，否则会立即触发/无效
        val now = todayAt(0, 30, 0, 0)
        val result = ClockScheduler.computeNextDailySetupTime(now)
        assertEquals(todayAt(0, 30) + 24 * 3600 * 1000L, result)
    }

    @Test
    fun `nextDailySetup - 00_30_00_500 just past - rolls to tomorrow`() {
        val now = todayAt(0, 30, 0, 500)
        val result = ClockScheduler.computeNextDailySetupTime(now)
        assertEquals(todayAt(0, 30) + 24 * 3600 * 1000L, result)
    }

    @Test
    fun `nextDailySetup - 00_35 main just fired - backup pairs to tomorrow 00_45 not today`() {
        // 回归：主链 00:30 触发后重武装时，备链必须是明天 00:45，绝不能落在今天
        val now = todayAt(0, 35)
        val mainAt = ClockScheduler.computeNextDailySetupTime(now)
        val backupAt = ClockScheduler.computeBackupSetupTime(mainAt)
        assertEquals(todayAt(0, 30) + 24 * 3600 * 1000L, mainAt)
        assertEquals(todayAt(0, 45) + 24 * 3600 * 1000L, backupAt)
    }

    @Test
    fun `nextDailySetup - 00_50 after backup window - rolls to tomorrow`() {
        val now = todayAt(0, 50)
        val result = ClockScheduler.computeNextDailySetupTime(now)
        assertEquals(todayAt(0, 30) + 24 * 3600 * 1000L, result)
    }

    @Test
    fun `nextDailySetup - 23_59 late night - rolls to tomorrow`() {
        val now = todayAt(23, 59)
        val result = ClockScheduler.computeNextDailySetupTime(now)
        assertEquals(todayAt(0, 30) + 24 * 3600 * 1000L, result)
    }

    @Test
    fun `nextDailySetup - 00_00 midnight - returns today 00_30`() {
        val now = todayAt(0, 0)
        val result = ClockScheduler.computeNextDailySetupTime(now)
        assertEquals(todayAt(0, 30), result)
    }

    // ====================================================================================
    // computeBackupSetupTime 主备配对关系
    // ====================================================================================

    @Test
    fun `backup pairing - always main plus configured offset`() {
        val mainAt = todayAt(0, 30)
        val backupAt = ClockScheduler.computeBackupSetupTime(mainAt)
        assertEquals(Constants.BACKUP_DAILY_SETUP_OFFSET_MINUTES * 60_000L, backupAt - mainAt)
        assertTimeIs(backupAt, 0, 30 + Constants.BACKUP_DAILY_SETUP_OFFSET_MINUTES)
    }

    // ====================================================================================
    // resolveRollingFallbackScope 覆盖范围决策
    // ====================================================================================

    @Test
    fun `fallback scope - clock in - covers only next workday clock in`() {
        val scope = ClockScheduler.resolveRollingFallbackScope(Constants.CLOCK_TYPE_CLOCK_IN)
        assertEquals(ClockScheduler.FallbackScope.CLOCK_IN_ONLY, scope)
        assertTrue(scope!!.includeClockIn)
        // 关键不变量：上班卡闭环时绝不能覆盖今天的下班闹钟（requestCode 1002 尚未触发）
        assertFalse(scope.includeClockOut)
    }

    @Test
    fun `fallback scope - clock out - covers both`() {
        // 下班卡闭环后今天两个闹钟均已触发，可安全补下一工作日的上班+下班
        val scope = ClockScheduler.resolveRollingFallbackScope(Constants.CLOCK_TYPE_CLOCK_OUT)
        assertEquals(ClockScheduler.FallbackScope.BOTH, scope)
        assertTrue(scope!!.includeClockIn)
        assertTrue(scope.includeClockOut)
    }

    @Test
    fun `fallback scope - test type - returns null`() {
        assertNull(ClockScheduler.resolveRollingFallbackScope("测试"))
    }

    @Test
    fun `fallback scope - unknown type - returns null`() {
        assertNull(ClockScheduler.resolveRollingFallbackScope(Constants.CLOCK_TYPE_UNKNOWN))
    }
}
