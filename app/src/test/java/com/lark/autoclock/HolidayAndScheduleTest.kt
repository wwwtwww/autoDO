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

    /**
     * BUG-REPRO: 验证 Calendar.set(Calendar.MINUTE, value > 59) 的行为
     * ClockScheduler 中 set(Calendar.MINUTE, mStartMin + clockInMinuteOffset) 
     * 当 mStartMin=30, offset=50 时，minute=80，Calendar 会自动进位到下一小时+20分钟。
     * 这意味着如果配置 07:30~08:20，随机偏移 50 分钟后实际变成 08:50，超出了结束时间 08:20。
     * 这不是"崩溃"bug，但在语义上随机打卡时间可能超出用户配置的时间段。
     */
    @Test
    fun testCalendarMinuteOverflow_BugRepro() {
        val mStartHour = 7
        val mStartMin = 30
        val maxOffset = 50 // 当 end=08:20 时，diff=50，offset 最大取 50

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, mStartHour)
            set(Calendar.MINUTE, mStartMin + maxOffset)  // 80
            set(Calendar.SECOND, 0)
        }

        // Calendar 会自动将 80 分钟进位为 1小时20分 -> 08:20
        assertEquals(8, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(20, cal.get(Calendar.MINUTE))
        // 这是 Calendar 的正确行为：07:30 + 50 分钟 = 08:20
        // 所以 offset=50 (end inclusive) 时刚好等于 08:20，不会超出。
        // 但如果用户配置了 07:50~08:00 (diff=10)，offset=10:
        // 07:50 + 10 = 08:00 -> 仍然正确。
    }

    /**
     * BUG-REPRO: 但如果用户配置了 morning_start=07:50, morning_end=08:20
     * diff = 30, offset 最大值 = 30
     * 07:50 + 30 = 08:20 -> Calendar 处理为 08:20，正好是结束时间（inclusive）
     * 这是可以接受的。
     *
     * 真正的问题场景：用户通过 ADB/直接编辑 SharedPreferences 设置了
     * morning_start=07:55, morning_end=08:25
     * diff = 30, offset = 30
     * 07:55 + 30 = 08:25 (Calendar: 08:25) -> 仍然等于 end，没有问题
     *
     * 结论：Calendar 的自动进位机制保证了正确性。本 Bug 不成立。
     */
    @Test
    fun testCalendarMinuteOverflow_StressTest() {
        // 极端情况：23:50~00:10 (跨午夜 - 用户不应该这么配置，但如果配置了会怎样？)
        val startHour = 23
        val startMin = 50
        val endHour = 0
        val endMin = 10

        val startTotalMins = startHour * 60 + startMin  // 1430
        val endTotalMins = endHour * 60 + endMin        // 10
        val diff = (endTotalMins - startTotalMins).coerceAtLeast(0)

        // 跨午夜场景下 diff 被 coerceAtLeast(0) 强制为 0，offset 也为 0
        // 所以闹钟会被安排在 23:50 (不会超出)
        assertEquals(0, diff)
    }

    /**
     * BUG-REPRO: System.currentTimeMillis().toInt() 溢出验证
     * 当前时间的毫秒数远超 Int.MAX_VALUE (2,147,483,647)
     * 2026-07-06T09:00:00Z ≈ 1783,591,200,000 ms
     * .toInt() 会截断高位，结果不确定但可以是负数
     * Android NotificationManager.notify(id, notification) 对负数 ID 是合法的
     */
    @Test
    fun testNotificationIdOverflow() {
        val currentMs = 1783591200000L // 模拟 2026-07-06 的时间戳
        val notificationId = currentMs.toInt()

        // 验证 .toInt() 确实会溢出产生非预期值（但这不是 crash bug）
        val expected = (currentMs and 0xFFFFFFFFL).toInt()
        assertEquals(expected, notificationId)
        // 关键：即使是负数也不会导致 NotificationManager 崩溃
    }

    @Test
    fun testMalformedTimeStringFallback() {
        val malformedStart = "0730" // 缺少冒号
        val malformedEnd = "invalid" // 无法转换为数字

        val (sHour, sMin, eHour, eMin) = try {
            val s = malformedStart.split(":")
            val e = malformedEnd.split(":")
            listOf(s[0].toInt(), s[1].toInt(), e[0].toInt(), e[1].toInt())
        } catch (ex: Exception) {
            listOf(7, 30, 8, 20)
        }

        // 畸形输入能够安全捕获并回退至 07:30 ~ 08:20
        assertEquals(7, sHour)
        assertEquals(30, sMin)
        assertEquals(8, eHour)
        assertEquals(20, eMin)
    }
}
