package com.lark.autoclock.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LogUtil 日志容量裁剪策略（trimLines 纯函数）单元测试。
 * 锁定不变量：超过 MAX_LINES 才裁剪；裁剪后恰好保留最近 KEEP_LINES 行，且保留的是最新内容。
 */
class LogUtilTrimTest {

    private fun makeLines(count: Int): List<String> = (1..count).map { "log-line-$it" }

    @Test
    fun `trimLines - under limit - unchanged`() {
        val lines = makeLines(100)
        assertEquals(lines, LogUtil.trimLines(lines))
    }

    @Test
    fun `trimLines - exactly at MAX_LINES - unchanged`() {
        val lines = makeLines(LogUtil.MAX_LINES)
        assertEquals(lines, LogUtil.trimLines(lines))
    }

    @Test
    fun `trimLines - one over limit - trimmed to KEEP_LINES keeping newest`() {
        val lines = makeLines(LogUtil.MAX_LINES + 1)
        val trimmed = LogUtil.trimLines(lines)
        assertEquals(LogUtil.KEEP_LINES, trimmed.size)
        // 保留的必须是尾部最新内容：首元素应为原列表第 (总数 - KEEP_LINES + 1) 行
        assertEquals("log-line-${LogUtil.MAX_LINES + 1 - LogUtil.KEEP_LINES + 1}", trimmed.first())
        assertEquals("log-line-${LogUtil.MAX_LINES + 1}", trimmed.last())
    }

    @Test
    fun `trimLines - far over limit - trimmed to KEEP_LINES`() {
        val lines = makeLines(1000)
        val trimmed = LogUtil.trimLines(lines)
        assertEquals(LogUtil.KEEP_LINES, trimmed.size)
        assertEquals("log-line-${1000 - LogUtil.KEEP_LINES + 1}", trimmed.first())
        assertEquals("log-line-1000", trimmed.last())
    }

    @Test
    fun `trimLines - capacity policy locked - MAX is 500 and KEEP is 400`() {
        // 调度日志时代（约 8~12 行/天）的容量策略锁：防止被无意改回 200 导致历史记录过早冲刷
        assertEquals(500, LogUtil.MAX_LINES)
        assertEquals(400, LogUtil.KEEP_LINES)
        assertTrue(LogUtil.KEEP_LINES < LogUtil.MAX_LINES)
    }
}
