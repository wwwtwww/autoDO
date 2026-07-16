package com.lark.autoclock

/**
 * 全局共享常量，消除跨组件魔术字符串。
 */
object Constants {

    // ===== Intent Extra Keys =====
    const val EXTRA_CLOCK_TYPE = "CLOCK_TYPE"
    const val EXTRA_CHAIN_ACTION = "CHAIN_ACTION"

    // ===== Chain Actions =====
    const val ACTION_START_CLOCK_IN = "ACTION_START_CLOCK_IN"

    // ===== Broadcast Actions =====
    const val ACTION_CLOCK_FINISHED = "com.lark.autoclock.ACTION_CLOCK_FINISHED"

    // ===== Clock Types =====
    const val CLOCK_TYPE_CLOCK_IN = "上班"
    const val CLOCK_TYPE_CLOCK_OUT = "下班"
    const val CLOCK_TYPE_UNKNOWN = "未知"

    // ===== Notification Channel IDs =====
    const val CHANNEL_ID_WAKE = "autoclock_channel"
}
