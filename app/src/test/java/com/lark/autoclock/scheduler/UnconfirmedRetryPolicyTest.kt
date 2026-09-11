package com.lark.autoclock.scheduler

import com.lark.autoclock.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 极速打卡未确认时「3 分钟自动重试」策略纯逻辑单元测试。
 *
 * 覆盖：
 * 1. 正常打卡（上班/下班）首次未确认触发重试
 * 2. 达到最大重试次数 (MAX_UNCONFIRMED_RETRY_COUNT = 1) 后停止重试，杜绝无限死循环
 * 3. 调试测试模式（clockType = "测试"）或未知类型避让，不调度延迟闹钟
 * 4. 自定义上限与边界值验证
 */
class UnconfirmedRetryPolicyTest {

    @Test
    fun `clock-in first attempt unconfirmed should retry`() {
        // 上班卡首次触发 (retryCount = 0)，未确认时应调度重试
        assertTrue(
            ClockScheduler.shouldScheduleUnconfirmedRetry(
                clockType = Constants.CLOCK_TYPE_CLOCK_IN,
                currentRetryCount = 0,
                maxRetry = Constants.MAX_UNCONFIRMED_RETRY_COUNT
            )
        )
    }

    @Test
    fun `clock-out first attempt unconfirmed should retry`() {
        // 下班卡首次触发 (retryCount = 0)，未确认时应调度重试
        assertTrue(
            ClockScheduler.shouldScheduleUnconfirmedRetry(
                clockType = Constants.CLOCK_TYPE_CLOCK_OUT,
                currentRetryCount = 0,
                maxRetry = Constants.MAX_UNCONFIRMED_RETRY_COUNT
            )
        )
    }

    @Test
    fun `clock-in retry attempt unconfirmed should NOT retry again`() {
        // 上班卡重试第 1 次仍未确认 (retryCount = 1)，达到默认上限 1，不应再调度
        assertFalse(
            ClockScheduler.shouldScheduleUnconfirmedRetry(
                clockType = Constants.CLOCK_TYPE_CLOCK_IN,
                currentRetryCount = 1,
                maxRetry = Constants.MAX_UNCONFIRMED_RETRY_COUNT
            )
        )
    }

    @Test
    fun `clock-out retry attempt unconfirmed should NOT retry again`() {
        // 下班卡重试第 1 次仍未确认 (retryCount = 1)，达到默认上限 1，不应再调度
        assertFalse(
            ClockScheduler.shouldScheduleUnconfirmedRetry(
                clockType = Constants.CLOCK_TYPE_CLOCK_OUT,
                currentRetryCount = 1,
                maxRetry = Constants.MAX_UNCONFIRMED_RETRY_COUNT
            )
        )
    }

    @Test
    fun `test clock-in mode should NEVER schedule delayed retry`() {
        // 用户在主界面手动点击「测试」打卡，即便未确认也不得调度 3 分钟闹钟
        assertFalse(
            ClockScheduler.shouldScheduleUnconfirmedRetry(
                clockType = "测试",
                currentRetryCount = 0,
                maxRetry = Constants.MAX_UNCONFIRMED_RETRY_COUNT
            )
        )
    }

    @Test
    fun `unknown clock-in type should NEVER schedule delayed retry`() {
        assertFalse(
            ClockScheduler.shouldScheduleUnconfirmedRetry(
                clockType = Constants.CLOCK_TYPE_UNKNOWN,
                currentRetryCount = 0,
                maxRetry = Constants.MAX_UNCONFIRMED_RETRY_COUNT
            )
        )
        assertFalse(
            ClockScheduler.shouldScheduleUnconfirmedRetry(
                clockType = "",
                currentRetryCount = 0,
                maxRetry = Constants.MAX_UNCONFIRMED_RETRY_COUNT
            )
        )
    }

    @Test
    fun `constants verify default values`() {
        // 验证默认配置为：重试 1 次，重试间隔 3 分钟 (180,000 ms)
        assertEquals(1, Constants.MAX_UNCONFIRMED_RETRY_COUNT)
        assertEquals(180000L, Constants.UNCONFIRMED_RETRY_INTERVAL_MS)
    }
}
