package com.lark.autoclock.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoClockAccessibilityServiceTest {
    @Test
    fun `chat text with clock phrase is not confirmed`() {
        val text = "张三 我今天已打卡了哈 输入消息 发送 聊天记录"

        assertFalse(ClockSuccessMatcher.isConfirmedClockSuccessText(text))
    }

    @Test
    fun `clock time alone is not confirmed`() {
        val text = "打卡时间 09:01 已于 今天"

        assertFalse(ClockSuccessMatcher.isConfirmedClockSuccessText(text))
    }

    @Test
    fun `success text with attendance context is confirmed`() {
        val text = "考勤 上班 打卡成功 打卡范围内 打卡时间 09:01"

        assertTrue(ClockSuccessMatcher.isConfirmedClockSuccessText(text))
    }
}
