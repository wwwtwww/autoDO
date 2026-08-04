package com.lark.autoclock.utils

import android.content.Context
import android.provider.Settings
import android.util.Log

object AccessibilityAutoEnableUtil {
    private const val TAG = "AutoEnableUtil"

    /**
     * 检查是否拥有 WRITE_SECURE_SETTINGS 权限
     */
    fun hasWriteSecureSettingsPermission(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * 自动开启无障碍服务（前提：已通过 adb shell pm grant 赋予了 WRITE_SECURE_SETTINGS 权限）
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
            
            if (serviceName !in serviceSet) {
                // 如果没有开启，则追加到集合中
                serviceSet.add(serviceName)
                
                // 写入新的服务列表
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    serviceSet.joinToString(":")
                )
            }

            // 确保无障碍功能总开关是打开的（先读后写，避免不必要的系统写操作）
            val currentEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            )
            if (currentEnabled != 1) {
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1
                )
            }
            
            Log.i(TAG, "成功通过 WRITE_SECURE_SETTINGS 自动开启了无障碍服务")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "自动开启无障碍服务失败", e)
            return false
        }
    }
}
