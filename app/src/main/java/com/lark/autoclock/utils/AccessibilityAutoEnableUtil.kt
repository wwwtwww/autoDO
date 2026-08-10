package com.lark.autoclock.utils

import android.content.Context
import android.provider.Settings
import android.util.Log

object AccessibilityAutoEnableUtil {
    private const val TAG = "AutoEnableUtil"
    private const val MIN_TOGGLE_INTERVAL_MS = 8000L  // 最小 toggle 间隔，防止高频重试导致 bind 震荡
    @Volatile
    private var lastToggleAt = 0L

    /**
     * 检查是否拥有 WRITE_SECURE_SETTINGS 权限
     */
    fun hasWriteSecureSettingsPermission(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * 自动开启或强制重绑无障碍服务（前提：已通过 adb shell pm grant 赋予了 WRITE_SECURE_SETTINGS 权限）
     *
     * 当服务已在系统启用列表中但实例为 null（系统在 Doze 中杀掉了服务进程），
     * 需要通过 toggle（移除 → 写入 → 等待 → 重新添加 → 写入）强制系统重绑服务。
     * 直接写相同的值不会触发系统 ContentObserver，因为 SettingsProvider 会跳过未变化的值。
     *
     * **注意**：本方法在 toggle 路径中含 Thread.sleep(300)，会阻塞主线程 300ms。
     * 调用方均在主线程（WakeActivity 重试回调 / KeepAliveService 健康检测 Runnable），
     * 闹钟触发场景无用户交互 UI，可安全使用；勿在交互路径调用。
     *
     * **语义说明**：若用户手动关闭了无障碍服务，本方法会重新将其打开。
     * 对专用打卡机符合需求；如需尊重用户手动禁用决定，调用前应先检查 instance 是否为 null。
     */
    fun autoEnableAccessibilityService(context: Context): Boolean {
        if (!hasWriteSecureSettingsPermission(context)) {
            Log.e(TAG, "没有 WRITE_SECURE_SETTINGS 权限，无法自动开启无障碍服务")
            return false
        }

        try {
            val serviceName = context.packageName + "/" + com.lark.autoclock.service.AutoClockAccessibilityService::class.java.name
            
            // 获取当前已开启的服务列表，使用 Set 进行精确匹配防止子串误判
            val enabledServices = Settings.Secure.getString(
                context.contentResolver, 
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            val serviceSet = enabledServices.split(":").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
            
            val wasAlreadyEnabled = serviceName in serviceSet
            
            if (wasAlreadyEnabled) {
                // 服务已在启用列表中但实例为 null（系统杀掉了进程）。
                // 防震荡：若距上次 toggle 不足 MIN_TOGGLE_INTERVAL_MS，跳过本次 toggle，
                // 让系统有足够时间完成 bind（低端机/深度 Doze 刚唤醒时 bind 可能需数秒）。
                val now = System.currentTimeMillis()
                if (now - lastToggleAt < MIN_TOGGLE_INTERVAL_MS) {
                    Log.i(TAG, "距上次 toggle 不足 ${MIN_TOGGLE_INTERVAL_MS / 1000}s，跳过重复 toggle，等待系统完成绑定")
                    return true
                }
                lastToggleAt = now

                // 执行 toggle 强制重绑：先移除并写入（触发系统解绑），
                // 等待 300ms 让系统处理解绑，再重新添加并写入（触发系统重绑）。
                // try-finally 保证：即使 sleep 或第二次写入异常，服务也会被重新添加回列表。
                Log.i(TAG, "服务已在启用列表中但实例未连接，执行 toggle 强制重绑")
                serviceSet.remove(serviceName)
                val removed = Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    serviceSet.joinToString(":")
                )
                if (!removed) {
                    Log.e(TAG, "移除服务写入失败，放弃 toggle，直接重新添加")
                    serviceSet.add(serviceName)
                    Settings.Secure.putString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        serviceSet.joinToString(":")
                    )
                    return ensureMasterSwitch(context, wasAlreadyEnabled)
                }
                try {
                    // 阻塞等待系统处理解绑，300ms 远低于 ANR 阈值 (5s)，可安全使用
                    Thread.sleep(300)
                } finally {
                    // 无论 sleep 是否抛 InterruptedException，都保证将服务重新添加回列表
                    serviceSet.add(serviceName)
                    val reAdded = Settings.Secure.putString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        serviceSet.joinToString(":")
                    )
                    if (!reAdded) {
                        Log.e(TAG, "⚠️ 重新添加服务写入失败！服务可能停留在已禁用状态，下次调用将自愈")
                    }
                }
            } else {
                // 服务不在启用列表中，正常添加
                serviceSet.add(serviceName)
                val added = Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    serviceSet.joinToString(":")
                )
                if (!added) {
                    Log.e(TAG, "添加服务到启用列表写入失败")
                    return false
                }
            }

            return ensureMasterSwitch(context, wasAlreadyEnabled)
        } catch (e: Exception) {
            Log.e(TAG, "自动开启无障碍服务失败", e)
            return false
        }
    }

    /**
     * 确保无障碍功能总开关已打开，并输出成功日志
     */
    private fun ensureMasterSwitch(context: Context, wasAlreadyEnabled: Boolean): Boolean {
        val currentEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        if (currentEnabled != 1) {
            val ok = Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            if (!ok) {
                Log.e(TAG, "无障碍总开关开启写入失败")
                return false
            }
        }
        Log.i(TAG, "成功通过 WRITE_SECURE_SETTINGS ${if (wasAlreadyEnabled) "强制重绑" else "自动开启"}了无障碍服务")
        return true
    }
}
