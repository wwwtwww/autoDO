package com.lark.autoclock.utils

/**
 * 时间字符串校验纯函数（无 Context 依赖，可单元测试）。
 *
 * 业务约束：打卡时段仅支持同日内配置 —— 结束时间必须晚于开始时间，
 * 不支持跨午夜时段（如 23:00 ~ 01:00），该约束已通过弹窗文案向用户显式声明。
 */
object TimeValidator {

    private val TIME_FORMAT = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    /** 校验 HH:mm 格式（24 小时制）是否合法 */
    fun isValidFormat(time: String): Boolean = TIME_FORMAT.matches(time)

    /**
     * HH:mm 转换为当日分钟数（00:00 -> 0，23:59 -> 1439）。
     * 格式非法时返回 -1。
     */
    fun toMinutesOfDay(time: String): Int {
        if (!isValidFormat(time)) return -1
        val parts = time.split(":")
        return parts[0].toInt() * 60 + parts[1].toInt()
    }

    /**
     * 同日时段校验：两侧格式均合法，且结束时间严格晚于开始时间。
     * 跨午夜（如 23:00 ~ 01:00）与零时长（如 08:00 ~ 08:00）均视为非法。
     */
    fun isValidSameDayRange(start: String, end: String): Boolean {
        val s = toMinutesOfDay(start)
        val e = toMinutesOfDay(end)
        return s >= 0 && e >= 0 && e > s
    }

    /**
     * 判断给定 HH:mm 时刻是否已早于当前时刻（即该时刻是否已过去）。
     * 可选传入 Calendar 以便单测注入固定基准时间。
     */
    fun isTimePassed(time: String, cal: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
        val targetMinutes = toMinutesOfDay(time)
        if (targetMinutes < 0) return false
        val currentMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return currentMinutes > targetMinutes
    }
}
