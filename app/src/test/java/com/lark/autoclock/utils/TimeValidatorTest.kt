package com.lark.autoclock.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TimeValidator 纯函数单元测试。
 * 覆盖：HH:mm 格式校验、当日分钟数换算、同日时段合法性（含跨午夜与零时长边界）。
 */
class TimeValidatorTest {

    // ===== isValidFormat =====

    @Test
    fun testValidFormats() {
        assertTrue(TimeValidator.isValidFormat("00:00"))
        assertTrue(TimeValidator.isValidFormat("07:30"))
        assertTrue(TimeValidator.isValidFormat("23:59"))
        assertTrue(TimeValidator.isValidFormat("08:05"))
    }

    @Test
    fun testInvalidFormats() {
        // 缺前导零 / 越界 / 结构错误 / 空串
        assertFalse(TimeValidator.isValidFormat("7:30"))
        assertFalse(TimeValidator.isValidFormat("24:00"))
        assertFalse(TimeValidator.isValidFormat("07:5"))
        assertFalse(TimeValidator.isValidFormat("0730"))
        assertFalse(TimeValidator.isValidFormat("07:60"))
        assertFalse(TimeValidator.isValidFormat("ab:cd"))
        assertFalse(TimeValidator.isValidFormat(""))
        assertFalse(TimeValidator.isValidFormat("07:30:00"))
    }

    // ===== toMinutesOfDay =====

    @Test
    fun testMinutesConversion() {
        assertEquals(0, TimeValidator.toMinutesOfDay("00:00"))
        assertEquals(450, TimeValidator.toMinutesOfDay("07:30"))   // 默认上班开始
        assertEquals(500, TimeValidator.toMinutesOfDay("08:20"))   // 默认上班结束
        assertEquals(1080, TimeValidator.toMinutesOfDay("18:00"))  // 默认下班开始
        assertEquals(1090, TimeValidator.toMinutesOfDay("18:10"))  // 默认下班结束
        assertEquals(1439, TimeValidator.toMinutesOfDay("23:59"))
    }

    @Test
    fun testMinutesConversionInvalidReturnsMinusOne() {
        assertEquals(-1, TimeValidator.toMinutesOfDay("24:00"))
        assertEquals(-1, TimeValidator.toMinutesOfDay("07:5"))
        assertEquals(-1, TimeValidator.toMinutesOfDay(""))
    }

    // ===== isValidSameDayRange =====

    @Test
    fun testValidSameDayRanges() {
        // 项目默认配置
        assertTrue(TimeValidator.isValidSameDayRange("07:30", "08:20"))
        assertTrue(TimeValidator.isValidSameDayRange("18:00", "18:10"))
        // 最小间隔 1 分钟
        assertTrue(TimeValidator.isValidSameDayRange("08:00", "08:01"))
        // 跨越整点边界
        assertTrue(TimeValidator.isValidSameDayRange("23:00", "23:59"))
    }

    @Test
    fun testReversedRangeIsInvalid() {
        // 结束早于开始（倒置）
        assertFalse(TimeValidator.isValidSameDayRange("08:20", "07:30"))
    }

    @Test
    fun testZeroLengthRangeIsInvalid() {
        // 零时长窗口无法产生随机打卡时刻
        assertFalse(TimeValidator.isValidSameDayRange("08:00", "08:00"))
    }

    @Test
    fun testCrossMidnightRangeIsInvalid() {
        // 跨午夜（夜班场景）当前不支持，文案已向用户显式声明
        assertFalse(TimeValidator.isValidSameDayRange("23:00", "01:00"))
    }

    @Test
    fun testRangeWithInvalidOperandIsInvalid() {
        // 任一侧格式非法则整体非法
        assertFalse(TimeValidator.isValidSameDayRange("7:30", "08:20"))
        assertFalse(TimeValidator.isValidSameDayRange("07:30", "24:00"))
        assertFalse(TimeValidator.isValidSameDayRange("", "08:20"))
    }

    // ===== isTimePassed =====

    @Test
    fun testIsTimePassed() {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 12)
        cal.set(java.util.Calendar.MINUTE, 30)

        // 目标时间 08:20 早于 12:30 -> 已过期
        assertTrue(TimeValidator.isTimePassed("08:20", cal))
        // 目标时间 12:29 早于 12:30 -> 已过期
        assertTrue(TimeValidator.isTimePassed("12:29", cal))
        // 目标时间 12:30 等于当前时刻 -> 尚未超过
        assertFalse(TimeValidator.isTimePassed("12:30", cal))
        // 目标时间 18:10 晚于 12:30 -> 尚未到达
        assertFalse(TimeValidator.isTimePassed("18:10", cal))
        // 格式非法 -> 判定为未过期 (false 安全回退)
        assertFalse(TimeValidator.isTimePassed("invalid", cal))
    }
}
