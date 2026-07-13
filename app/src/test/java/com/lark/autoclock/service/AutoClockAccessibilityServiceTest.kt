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

    @Test
    fun `empty string is not confirmed`() {
        assertFalse(ClockSuccessMatcher.isConfirmedClockSuccessText(""))
    }

    @Test
    fun `only context keywords without success keyword is not confirmed`() {
        val text = "考勤 上班 打卡范围 打卡地点"

        assertFalse(ClockSuccessMatcher.isConfirmedClockSuccessText(text))
    }

    @Test
    fun `single chat indicator with clock keywords is still confirmed`() {
        // 仅 1 个聊天指标（不足 2 个），不应被判定为聊天页面
        val text = "输入消息 考勤 打卡成功"

        assertTrue(ClockSuccessMatcher.isConfirmedClockSuccessText(text))
    }

    @Test
    fun `special dot separator clock in keyword is confirmed`() {
        val text = "考勤 上班·已打卡 打卡范围内"

        assertTrue(ClockSuccessMatcher.isConfirmedClockSuccessText(text))
    }

    @Test
    fun `special dot separator clock out keyword is confirmed`() {
        val text = "考勤 下班·已打卡 打卡地点"

        assertTrue(ClockSuccessMatcher.isConfirmedClockSuccessText(text))
    }

    @Test
    fun `success keyword without any context keyword is not confirmed`() {
        val text = "打卡成功 恭喜你完成了今天的步数目标"

        assertFalse(ClockSuccessMatcher.isConfirmedClockSuccessText(text))
    }

    @Test
    fun `two chat indicators plus clock keywords triggers chat page guard`() {
        val text = "考勤 打卡成功 输入消息 发送"

        assertFalse(ClockSuccessMatcher.isConfirmedClockSuccessText(text))
    }
}
