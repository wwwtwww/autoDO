package com.lark.autoclock.service

object ClockSuccessMatcher {
    val successKeywords = listOf(
        "打卡成功", "上班·已打卡", "下班·已打卡", "上班已打卡", "下班已打卡", "极速打卡成功"
    )

    private val clockContextKeywords = listOf(
        "考勤", "打卡", "上班", "下班", "打卡范围", "打卡地点", "打卡时间", "打卡地址"
    )

    private val chatIndicators = listOf("输入消息", "发送", "消息记录", "聊天记录", "回复")

    fun isConfirmedClockSuccessText(allText: String, expectedClockType: String): Boolean {
        val isChatPage = chatIndicators.count { allText.contains(it) } >= 2
        if (isChatPage) return false

        // 防串号校验（极其重要）：飞书启动时，经常会残留上一次打卡成功界面的缓存（如早晨拉起时屏幕还显示着昨晚的“下班极速打卡成功”）。
        // 如果我们是“上班”打卡，但屏幕上的成功标志明确带有“下班”字样，说明这是旧缓存，必须判定为无效！
        if (expectedClockType == com.lark.autoclock.Constants.CLOCK_TYPE_CLOCK_IN) {
            if (allText.contains("下班极速打卡成功") || allText.contains("下班·已打卡") || allText.contains("下班已打卡")) {
                return false
            }
        } else if (expectedClockType == com.lark.autoclock.Constants.CLOCK_TYPE_CLOCK_OUT) {
            if (allText.contains("上班极速打卡成功") || allText.contains("上班·已打卡") || allText.contains("上班已打卡")) {
                return false
            }
        }

        val hasSuccessKeyword = successKeywords.any { allText.contains(it) }
        val hasClockContext = clockContextKeywords.any { allText.contains(it) }
        return hasSuccessKeyword && hasClockContext
    }
}
