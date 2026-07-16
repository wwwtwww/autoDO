package com.lark.autoclock.service

object ClockSuccessMatcher {
    val successKeywords = listOf(
        "打卡成功", "上班·已打卡", "下班·已打卡", "上班已打卡", "下班已打卡", "极速打卡成功"
    )

    private val clockContextKeywords = listOf(
        "考勤", "打卡", "上班", "下班", "打卡范围", "打卡地点", "打卡时间", "打卡地址"
    )

    private val chatIndicators = listOf("输入消息", "发送", "消息记录", "聊天记录", "回复")

    fun isConfirmedClockSuccessText(allText: String): Boolean {
        val isChatPage = chatIndicators.count { allText.contains(it) } >= 2
        if (isChatPage) return false

        val hasSuccessKeyword = successKeywords.any { allText.contains(it) }
        val hasClockContext = clockContextKeywords.any { allText.contains(it) }
        return hasSuccessKeyword && hasClockContext
    }
}
