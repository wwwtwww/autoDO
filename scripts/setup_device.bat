@echo off
echo ==========================================
echo autoDO - 一键设备初始化脚本
echo ==========================================
echo 请确保手机已通过 USB 数据线连接电脑，并开启了“USB 调试”！
echo.
pause

echo.
echo [1/4] 正在授予通知权限...
adb shell pm grant com.lark.autoclock android.permission.POST_NOTIFICATIONS

echo.
echo [2/4] 正在授予悬浮窗权限 (SYSTEM_ALERT_WINDOW)...
adb shell appops set com.lark.autoclock SYSTEM_ALERT_WINDOW allow

echo.
echo [3/4] 正在添加后台省电白名单...
adb shell dumpsys deviceidle whitelist +com.lark.autoclock

echo.
echo [4/5] 正在授予精确闹钟权限 (SCHEDULE_EXACT_ALARM)...
adb shell appops set com.lark.autoclock SCHEDULE_EXACT_ALARM allow

echo.
echo [5/5] 正在强制开启无障碍服务...
adb shell settings put secure enabled_accessibility_services com.lark.autoclock/com.lark.autoclock.service.AutoClockAccessibilityService
adb shell settings put secure accessibility_enabled 1

echo.
echo ==========================================
echo 配置完成！请打开手机 App 检查各项状态是否正常。
echo (注意：部分高度定制的国产 ROM 可能仍需手动确认自启动权限)
echo ==========================================
pause
