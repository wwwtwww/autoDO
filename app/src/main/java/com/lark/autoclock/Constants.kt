package com.lark.autoclock

/**
 * 全局共享常量，消除跨组件魔术字符串。
 */
object Constants {

    // ===== Intent Extra Keys =====
    const val EXTRA_CLOCK_TYPE = "CLOCK_TYPE"
    const val EXTRA_CHAIN_ACTION = "CHAIN_ACTION"
    const val EXTRA_DELAYED_RETRY_COUNT = "DELAYED_RETRY_COUNT"

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

    // ===== 生命与超时配置 (ms) =====
    const val TIMEOUT_ACCESSIBILITY_SCAN = 45000L      // 无障碍扫描最长等待时间 (45s)
    const val TIMEOUT_WAKE_ACTIVITY_FALLBACK = 65000L  // WakeActivity 兜底销毁超时 (65s) — 覆盖最差重试路径 (2s+6s+45s+3s=56s) 加安全余量
    const val WAKELOCK_ACQUIRE_DURATION = 60000L       // WakeLock 绝对持锁时长 (60s)

    // ===== 无障碍断连恢复配置 =====
    const val ACCESSIBILITY_RETRY_COUNT = 3              // instance 为 null 时的重试次数
    const val ACCESSIBILITY_RETRY_INTERVAL_MS = 3000L   // 每次重试间隔 (3s)
    const val HEALTH_CHECK_INTERVAL_MS = 900000L         // 健康检测周期 (15分钟)
    const val DELAYED_RETRY_COUNT = 2                  // 无障碍断连后的延迟全量重试次数 (每次间隔60s)
    const val DELAYED_RETRY_INTERVAL_MS = 60000L        // 延迟重试间隔 (60s)
    const val CHANNEL_ID_ALERT = "autoclock_alert_channel" // 告警通知通道ID
    const val ALERT_NOTIFICATION_ID = 10003             // 告警通知ID
}
