package com.lark.autoclock.scheduler

import com.lark.autoclock.utils.HolidayHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * 针对「周五下班卡」和「周一上班卡」两个最高风险场景的最严格单元测试。
 *
 * 测试覆盖三层防线：
 * 1. HolidayHelper 节假日降级判定 —— 确保网络异常时周一/周五永远判定为工作日
 * 2. ClockScheduler 时序补偿决策 —— 覆盖每一个时间边界点
 * 3. 端到端场景模拟 —— 模拟真实的「周五傍晚 Doze 延迟」和「周一早晨 Doze 延迟」
 */
class FridayMondayCriticalTest {

    // ========================================================================================
    // 辅助方法：构造指定日期和时间的毫秒时间戳
    // ========================================================================================

    /**
     * 构造「今天的某个时刻」的毫秒时间戳
     */
    private fun todayAt(hour: Int, minute: Int, second: Int = 0): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * 构造「距离 now 之后 deltaMinutes 分钟」的时间戳（用于模拟未来的闹钟时间）
     */
    private fun minutesFromNow(now: Long, deltaMinutes: Int): Long {
        return now + deltaMinutes * 60 * 1000L
    }

    // ========================================================================================
    // 第一层：HolidayHelper 节假日降级 —— 确保周一和周五永远被判定为工作日
    // ========================================================================================

    @Test
    fun `LAYER1 - Monday is WORKDAY on network failure`() {
        assertEquals(
            HolidayHelper.WorkdayStatus.WORKDAY,
            HolidayHelper.resolveStatusOnNetworkFailure(Calendar.MONDAY)
        )
    }

    @Test
    fun `LAYER1 - Friday is WORKDAY on network failure`() {
        assertEquals(
            HolidayHelper.WorkdayStatus.WORKDAY,
            HolidayHelper.resolveStatusOnNetworkFailure(Calendar.FRIDAY)
        )
    }

    @Test
    fun `LAYER1 - Saturday is RESTDAY on network failure`() {
        assertEquals(
            HolidayHelper.WorkdayStatus.RESTDAY,
            HolidayHelper.resolveStatusOnNetworkFailure(Calendar.SATURDAY)
        )
    }

    @Test
    fun `LAYER1 - Sunday is RESTDAY on network failure`() {
        assertEquals(
            HolidayHelper.WorkdayStatus.RESTDAY,
            HolidayHelper.resolveStatusOnNetworkFailure(Calendar.SUNDAY)
        )
    }

    // ========================================================================================
    // 第二层：ClockScheduler 上班打卡补偿决策 —— 逐个边界测试
    // ========================================================================================

    // --- 正常下发场景 ---

    @Test
    fun `CLOCK_IN - alarm in future - returns SCHEDULE`() {
        val now = todayAt(7, 0)
        val alarm = minutesFromNow(now, 30) // 闹钟在 30 分钟后
        assertEquals(ClockScheduler.ClockAction.SCHEDULE, ClockScheduler.resolveClockInAction(alarm, now))
    }

    @Test
    fun `CLOCK_IN - alarm 1ms in future - returns SCHEDULE`() {
        val now = todayAt(8, 20)
        val alarm = now + 1 // 闹钟比当前时间晚 1ms
        assertEquals(ClockScheduler.ClockAction.SCHEDULE, ClockScheduler.resolveClockInAction(alarm, now))
    }

    // --- 补偿场景（闹钟已过 但仍在 11:30 之前）---

    @Test
    fun `CLOCK_IN - Monday 8_30 alarm was 7_30 - COMPENSATE`() {
        val now = todayAt(8, 30)
        val alarm = todayAt(7, 30) // 闹钟在 1 小时前
        assertEquals(ClockScheduler.ClockAction.COMPENSATE, ClockScheduler.resolveClockInAction(alarm, now))
    }

    @Test
    fun `CLOCK_IN - Monday 9_00 Doze wakeup - COMPENSATE`() {
        val now = todayAt(9, 0)
        val alarm = todayAt(7, 45)
        assertEquals(ClockScheduler.ClockAction.COMPENSATE, ClockScheduler.resolveClockInAction(alarm, now))
    }

    @Test
    fun `CLOCK_IN - Monday 10_00 very late Doze - COMPENSATE`() {
        val now = todayAt(10, 0)
        val alarm = todayAt(8, 0)
        assertEquals(ClockScheduler.ClockAction.COMPENSATE, ClockScheduler.resolveClockInAction(alarm, now))
    }

    @Test
    fun `CLOCK_IN - Monday 11_29 one minute before deadline - COMPENSATE`() {
        val now = todayAt(11, 29)
        val alarm = todayAt(8, 0)
        assertEquals(ClockScheduler.ClockAction.COMPENSATE, ClockScheduler.resolveClockInAction(alarm, now))
    }

    @Test
    fun `CLOCK_IN - Monday 11_29_59 one second before deadline - COMPENSATE`() {
        val now = todayAt(11, 29, 59)
        val alarm = todayAt(8, 0)
        assertEquals(ClockScheduler.ClockAction.COMPENSATE, ClockScheduler.resolveClockInAction(alarm, now))
    }

    // --- 跳过场景（已过 11:30 补偿截止线）---

    @Test
    fun `CLOCK_IN - Monday 11_30 exactly at deadline - SKIP`() {
        val now = todayAt(11, 30)
        val alarm = todayAt(8, 0)
        assertEquals(ClockScheduler.ClockAction.SKIP, ClockScheduler.resolveClockInAction(alarm, now))
    }

    @Test
    fun `CLOCK_IN - Monday 11_31 one minute past deadline - SKIP`() {
        val now = todayAt(11, 31)
        val alarm = todayAt(8, 0)
        assertEquals(ClockScheduler.ClockAction.SKIP, ClockScheduler.resolveClockInAction(alarm, now))
    }

    @Test
    fun `CLOCK_IN - Monday 14_00 afternoon - SKIP`() {
        val now = todayAt(14, 0)
        val alarm = todayAt(8, 0)
        assertEquals(ClockScheduler.ClockAction.SKIP, ClockScheduler.resolveClockInAction(alarm, now))
    }

    @Test
    fun `CLOCK_IN - Monday 23_59 midnight - SKIP`() {
        val now = todayAt(23, 59)
        val alarm = todayAt(8, 0)
        assertEquals(ClockScheduler.ClockAction.SKIP, ClockScheduler.resolveClockInAction(alarm, now))
    }

    // ========================================================================================
    // 第二层：ClockScheduler 下班打卡补偿决策 —— 逐个边界测试
    // ========================================================================================

    // --- 正常下发场景 ---

    @Test
    fun `CLOCK_OUT - alarm in future - returns SCHEDULE`() {
        val now = todayAt(17, 0)
        val alarm = todayAt(18, 5) // 闹钟在 1 小时后
        assertEquals(ClockScheduler.ClockAction.SCHEDULE, ClockScheduler.resolveClockOutAction(alarm, now))
    }

    @Test
    fun `CLOCK_OUT - Friday 17_30 alarm at 18_00 - SCHEDULE`() {
        val now = todayAt(17, 30)
        val alarm = todayAt(18, 0)
        assertEquals(ClockScheduler.ClockAction.SCHEDULE, ClockScheduler.resolveClockOutAction(alarm, now))
    }

    // --- 补偿场景（闹钟已过 但仍在 22:00 之前）---

    @Test
    fun `CLOCK_OUT - Friday 18_30 alarm was 18_05 - COMPENSATE`() {
        val now = todayAt(18, 30)
        val alarm = todayAt(18, 5) // 闹钟在 25 分钟前
        assertEquals(ClockScheduler.ClockAction.COMPENSATE, ClockScheduler.resolveClockOutAction(alarm, now))
    }

    @Test
    fun `CLOCK_OUT - Friday 19_00 Doze delayed - COMPENSATE`() {
        val now = todayAt(19, 0)
        val alarm = todayAt(18, 0)
        assertEquals(ClockScheduler.ClockAction.COMPENSATE, ClockScheduler.resolveClockOutAction(alarm, now))
    }

    @Test
    fun `CLOCK_OUT - Friday 20_00 late evening - COMPENSATE`() {
        val now = todayAt(20, 0)
        val alarm = todayAt(18, 10)
        assertEquals(ClockScheduler.ClockAction.COMPENSATE, ClockScheduler.resolveClockOutAction(alarm, now))
    }

    @Test
    fun `CLOCK_OUT - Friday 21_59 one minute before deadline - COMPENSATE`() {
        val now = todayAt(21, 59)
        val alarm = todayAt(18, 0)
        assertEquals(ClockScheduler.ClockAction.COMPENSATE, ClockScheduler.resolveClockOutAction(alarm, now))
    }

    @Test
    fun `CLOCK_OUT - Friday 21_59_59 one second before deadline - COMPENSATE`() {
        val now = todayAt(21, 59, 59)
        val alarm = todayAt(18, 0)
        assertEquals(ClockScheduler.ClockAction.COMPENSATE, ClockScheduler.resolveClockOutAction(alarm, now))
    }

    // --- 跳过场景（已过 22:00 补偿截止线）---

    @Test
    fun `CLOCK_OUT - Friday 22_00 exactly at deadline - SKIP`() {
        val now = todayAt(22, 0)
        val alarm = todayAt(18, 0)
        assertEquals(ClockScheduler.ClockAction.SKIP, ClockScheduler.resolveClockOutAction(alarm, now))
    }

    @Test
    fun `CLOCK_OUT - Friday 22_01 one minute past deadline - SKIP`() {
        val now = todayAt(22, 1)
        val alarm = todayAt(18, 0)
        assertEquals(ClockScheduler.ClockAction.SKIP, ClockScheduler.resolveClockOutAction(alarm, now))
    }

    @Test
    fun `CLOCK_OUT - Friday 23_30 late night - SKIP`() {
        val now = todayAt(23, 30)
        val alarm = todayAt(18, 0)
        assertEquals(ClockScheduler.ClockAction.SKIP, ClockScheduler.resolveClockOutAction(alarm, now))
    }

    // ========================================================================================
    // 第三层：端到端场景模拟 —— 模拟真实的周五/周一 Doze 延迟全链路
    // ========================================================================================

    /**
     * 场景：周五傍晚，手机在 18:00 前处于 Doze 模式，
     * 凌晨 00:30 的 DailySetupReceiver 正常下发了下班闹钟（18:05），
     * 但手机直到 19:30 才被系统唤醒执行调度。
     *
     * 预期：HolidayHelper 判定周五为工作日 → ClockScheduler 发现下班闹钟已过
     * → 当前 19:30 < 22:00 → 触发即时补打卡
     */
    @Test
    fun `E2E - Friday evening Doze delay triggers compensation`() {
        // Layer 1: 周五在断网时仍判定为工作日
        val fridayStatus = HolidayHelper.resolveStatusOnNetworkFailure(Calendar.FRIDAY)
        assertEquals(HolidayHelper.WorkdayStatus.WORKDAY, fridayStatus)

        // Layer 2: 下班闹钟已过，但在 22:00 之前 → 补偿
        val alarm = todayAt(18, 5)
        val now = todayAt(19, 30)
        assertEquals(ClockScheduler.ClockAction.COMPENSATE, ClockScheduler.resolveClockOutAction(alarm, now))
    }

    /**
     * 场景：周一早晨，手机整个周末处于 Doze 模式，
     * 凌晨 00:30 的调度任务被推迟到用户早上拿起手机时才触发（08:45）。
     * 计算出的随机上班打卡时间为 07:50，已经过去了。
     *
     * 预期：HolidayHelper 判定周一为工作日 → ClockScheduler 发现上班闹钟已过
     * → 当前 08:45 < 11:30 → 触发即时补打卡
     */
    @Test
    fun `E2E - Monday morning Doze delay triggers compensation`() {
        // Layer 1: 周一在断网时仍判定为工作日
        val mondayStatus = HolidayHelper.resolveStatusOnNetworkFailure(Calendar.MONDAY)
        assertEquals(HolidayHelper.WorkdayStatus.WORKDAY, mondayStatus)

        // Layer 2: 上班闹钟已过，但在 11:30 之前 → 补偿
        val alarm = todayAt(7, 50)
        val now = todayAt(8, 45)
        assertEquals(ClockScheduler.ClockAction.COMPENSATE, ClockScheduler.resolveClockInAction(alarm, now))
    }

    /**
     * 场景：周六凌晨 00:30 DailySetupReceiver 触发，但网络异常。
     *
     * 预期：HolidayHelper 判定周六为休息日 → 不下发任何打卡闹钟 → 不会误打卡
     */
    @Test
    fun `E2E - Saturday network failure does NOT trigger clock`() {
        val saturdayStatus = HolidayHelper.resolveStatusOnNetworkFailure(Calendar.SATURDAY)
        assertEquals(HolidayHelper.WorkdayStatus.RESTDAY, saturdayStatus)
        // REST_DAY 状态下 DailySetupReceiver 不会调用 scheduleTodayClockActions，无需进入补偿逻辑
    }

    /**
     * 场景：周一早上 11:31 才醒来（极端 Doze），上班打卡时间 08:00 已经过去 3.5 小时。
     *
     * 预期：虽然是工作日，但已超过 11:30 补偿截止线 → SKIP，不再补打。
     * 这是合理的：打了也是旷工迟到太久没意义。
     */
    @Test
    fun `E2E - Monday extreme late Doze past deadline skips`() {
        val mondayStatus = HolidayHelper.resolveStatusOnNetworkFailure(Calendar.MONDAY)
        assertEquals(HolidayHelper.WorkdayStatus.WORKDAY, mondayStatus)

        val alarm = todayAt(8, 0)
        val now = todayAt(11, 31)
        assertEquals(ClockScheduler.ClockAction.SKIP, ClockScheduler.resolveClockInAction(alarm, now))
    }

    /**
     * 场景：周五晚上 22:01 才发现下班卡没打。
     *
     * 预期：虽然是工作日，但已超过 22:00 补偿截止线 → SKIP。
     */
    @Test
    fun `E2E - Friday extreme late evening past deadline skips`() {
        val fridayStatus = HolidayHelper.resolveStatusOnNetworkFailure(Calendar.FRIDAY)
        assertEquals(HolidayHelper.WorkdayStatus.WORKDAY, fridayStatus)

        val alarm = todayAt(18, 5)
        val now = todayAt(22, 1)
        assertEquals(ClockScheduler.ClockAction.SKIP, ClockScheduler.resolveClockOutAction(alarm, now))
    }

    // ========================================================================================
    // 交叉验证：上班决策函数不应被用于下班场景（防止开发者混用）
    // ========================================================================================

    @Test
    fun `CROSS - clockIn function uses 11_30 deadline not 22_00`() {
        // 15:00 在 11:30 之后但在 22:00 之前
        // 如果错误使用了 22:00 截止线，会返回 COMPENSATE
        // 但正确的 clockIn 应该返回 SKIP（因为 15:00 > 11:30）
        val now = todayAt(15, 0)
        val alarm = todayAt(8, 0)
        assertEquals(ClockScheduler.ClockAction.SKIP, ClockScheduler.resolveClockInAction(alarm, now))
    }

    @Test
    fun `CROSS - clockOut function uses 22_00 deadline not 11_30`() {
        // 15:00 在 11:30 之后但在 22:00 之前
        // clockOut 应该返回 COMPENSATE（因为 15:00 < 22:00）
        val now = todayAt(15, 0)
        val alarm = todayAt(14, 0)
        assertEquals(ClockScheduler.ClockAction.COMPENSATE, ClockScheduler.resolveClockOutAction(alarm, now))
    }
}
