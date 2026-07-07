package com.lark.autoclock.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class HolidayHelperTest {
    @Test
    fun `type 0 and 3 are workday`() {
        assertEquals(
            HolidayHelper.WorkdayStatus.WORKDAY,
            HolidayHelper.parseWorkdayStatus("""{"code":0,"type":{"type":0}}""")
        )
        assertEquals(
            HolidayHelper.WorkdayStatus.WORKDAY,
            HolidayHelper.parseWorkdayStatus("""{"code":0,"type":{"type":3}}""")
        )
    }

    @Test
    fun `type 1 and 2 are restday`() {
        assertEquals(
            HolidayHelper.WorkdayStatus.RESTDAY,
            HolidayHelper.parseWorkdayStatus("""{"code":0,"type":{"type":1}}""")
        )
        assertEquals(
            HolidayHelper.WorkdayStatus.RESTDAY,
            HolidayHelper.parseWorkdayStatus("""{"code":0,"type":{"type":2}}""")
        )
    }

    @Test
    fun `unknown code type or malformed json are unknown`() {
        assertEquals(
            HolidayHelper.WorkdayStatus.UNKNOWN,
            HolidayHelper.parseWorkdayStatus("""{"code":1}""")
        )
        assertEquals(
            HolidayHelper.WorkdayStatus.UNKNOWN,
            HolidayHelper.parseWorkdayStatus("""{"code":0,"type":{"type":9}}""")
        )
        assertEquals(
            HolidayHelper.WorkdayStatus.UNKNOWN,
            HolidayHelper.parseWorkdayStatus("not json")
        )
    }
}
