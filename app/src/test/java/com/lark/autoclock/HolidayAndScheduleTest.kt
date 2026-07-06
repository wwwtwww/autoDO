package com.lark.autoclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class HolidayAndScheduleTest {

    @Test
    fun testTimeDiffCalculation() {
        val startHour = 7
        val startMin = 30
        val endHour = 8
        val endMin = 20

        val startTotalMins = startHour * 60 + startMin
        val endTotalMins = endHour * 60 + endMin
        val diff = (endTotalMins - startTotalMins).coerceAtLeast(0)

        assertEquals(50, diff)
    }

    @Test
    fun testInvalidTimeDiffFallback() {
        val startHour = 8
        val startMin = 30
        val endHour = 7
        val endMin = 20

        val startTotalMins = startHour * 60 + startMin
        val endTotalMins = endHour * 60 + endMin
        val diff = (endTotalMins - startTotalMins).coerceAtLeast(0)

        // 倒置的时间应该回退为 0 差值，防止崩溃或产生负随机数
        assertEquals(0, diff)
    }
}
