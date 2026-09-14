package com.lark.autoclock

/**
 * 全局共享常量，消除跨组件魔术字符串。
 */
object Constants {

    // ===== Intent Extra Keys =====
    const val EXTRA_CLOCK_TYPE = "CLOCK_TYPE"
    const val EXTRA_CHAIN_ACTION = "CHAIN_ACTION"
    const val EXTRA_DELAYED_RETRY_COUNT = "DELAYED_RETRY_COUNT"
    const val EXTRA_UNCONFIRMED_RETRY_COUNT = "UNCONFIRMED_RETRY_COUNT"
    const val EXTRA_ALARM_SOURCE = "ALARM_SOURCE"

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

    // ===== 默认打卡时段（MainActivity / ClockScheduler 共享，避免多处维护魔法字符串） =====
    // 注意：仅支持同日时段，结束时间必须晚于开始时间，不支持跨午夜（如 23:00 ~ 01:00）
    const val DEFAULT_MORNING_START = "07:30"
    const val DEFAULT_MORNING_END = "08:20"
    const val DEFAULT_AFTERNOON_START = "18:00"
    const val DEFAULT_AFTERNOON_END = "18:10"

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

    // ===== 极速打卡未确认自动重试配置 =====
    const val MAX_UNCONFIRMED_RETRY_COUNT = 1            // 极速打卡未确认时的自动重试次数 (1次)
    const val UNCONFIRMED_RETRY_INTERVAL_MS = 180000L    // 未确认自动重试间隔 (3分钟 = 180s)

    // ===== 调度链冗余与可观测配置 =====
    // 闹钟来源标记：写入 EXTRA_ALARM_SOURCE，用于调度日志区分「哪条链触发的闹钟」
    const val ALARM_SOURCE_OFFICIAL = "正式排班"
    const val ALARM_SOURCE_FALLBACK = "保底"
    const val ALARM_SOURCE_IMMEDIATE = "即时补打"
    const val ALARM_SOURCE_DELAYED_RETRY = "延迟重试"
    const val ALARM_SOURCE_UNCONFIRMED_RETRY = "未确认重试"
    const val ALARM_SOURCE_DAILY_SETUP_MAIN = "主链"
    const val ALARM_SOURCE_DAILY_SETUP_BACKUP = "备链"
    const val ALARM_SOURCE_UNKNOWN = "未知"

    const val REQUEST_CODE_DAILY_SETUP_MAIN = 0          // 凌晨主调度链 requestCode（历史沿用 0）
    const val REQUEST_CODE_DAILY_SETUP_BACKUP = 5000     // 凌晨备份调度链 requestCode（主链 +15 分钟）
    const val BACKUP_DAILY_SETUP_OFFSET_MINUTES = 15     // 备份调度链与主链的间隔（分钟）
    const val ALARM_PERMISSION_NOTIFICATION_ID = 10004   // 精确闹钟权限失效告警通知ID
    const val WORK_NAME_ALARM_HEALTH_CHECK = "alarm_health_check" // WorkManager 每日健康检查任务名
}
