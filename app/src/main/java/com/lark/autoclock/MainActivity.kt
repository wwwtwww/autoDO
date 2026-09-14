package com.lark.autoclock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope

import android.app.KeyguardManager
import android.app.NotificationManager
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Typeface
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.app.TimePickerDialog
import java.io.File
import java.util.Locale
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lark.autoclock.scheduler.ClockScheduler
import com.lark.autoclock.utils.TimeValidator

class MainActivity : AppCompatActivity() {
    private val PREFS_NAME = ClockScheduler.PREFS_NAME
    private val KEY_BATTERY_PROMPTED = "battery_prompted"
    private val KEY_LOCKSCREEN_PROMPTED = "lockscreen_prompted"
    private val KEY_FULL_SCREEN_PROMPTED = "full_screen_prompted"
    private val KEY_KEEPALIVE_ENABLED = ClockScheduler.KEY_KEEPALIVE_ENABLED
    private val KEY_AUTOSTART_CONFIGURED = "autostart_configured"
    private var pendingAutostartCheck = false

    // 视图控件缓存（消除 onResume 高频刷新时的 View 树重复遍历）
    private val btnScheduleTasks by lazy { findViewById<MaterialButton>(R.id.btn_schedule_tasks) }
    private val cardGlobalStatus by lazy { findViewById<MaterialCardView>(R.id.card_global_status) }
    private val tvGlobalStatus by lazy { findViewById<TextView>(R.id.tv_global_status) }
    private val tvGlobalStatusDesc by lazy { findViewById<TextView>(R.id.tv_global_status_desc) }
    private val layoutStatusChips by lazy { findViewById<View>(R.id.layout_status_chips) }
    private val chipMorning by lazy { findViewById<TextView>(R.id.chip_morning) }
    private val chipAfternoon by lazy { findViewById<TextView>(R.id.chip_afternoon) }
    private val tvAccessibilityStatus by lazy { findViewById<TextView>(R.id.tv_status_accessibility) }
    private val tvBatteryStatus by lazy { findViewById<TextView>(R.id.tv_status_battery) }
    private val tvOverlayStatus by lazy { findViewById<TextView>(R.id.tv_status_overlay) }
    private val tvAlarmStatus by lazy { findViewById<TextView>(R.id.tv_status_alarm) }
    private val tvAutostartStatus by lazy { findViewById<TextView>(R.id.tv_status_autostart) }
    private val tvSubtitleConfigTime by lazy { findViewById<TextView>(R.id.tv_subtitle_config_time) }
    private val tvSubtitleExceptions by lazy { findViewById<TextView>(R.id.tv_subtitle_exceptions) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 请求 Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // 恢复前台保活服务（如果用户之前已开启）
        if (getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_KEEPALIVE_ENABLED, false)) {
            androidx.core.content.ContextCompat.startForegroundService(
                this,
                Intent(this, com.lark.autoclock.service.KeepAliveService::class.java)
            )
        }

        // 注册 WorkManager 每日闹钟健康检查（异构兜底，KEEP 策略下幂等）
        com.lark.autoclock.scheduler.AlarmHealthCheckWorker.enqueue(this)

        // 1. 跳转无障碍设置 / 自动开启（含 Thread.sleep 的 toggle 逻辑必须异步执行，避免主线程卡顿）
        findViewById<View>(R.id.btn_enable_accessibility).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = com.lark.autoclock.utils.AccessibilityAutoEnableUtil
                    .autoEnableAccessibilityService(this@MainActivity)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(this@MainActivity, getString(R.string.toast_adb_auto_enabled), Toast.LENGTH_SHORT).show()
                        updateStatusAndPermissionsUI()
                    } else {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                    }
                }
            }
        }

        // 2. 测试亮屏与解锁
        findViewById<Button>(R.id.btn_test_unlock).setOnClickListener {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Toast.makeText(this, getString(R.string.toast_need_exact_alarm_test), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Toast.makeText(this, getString(R.string.toast_lock_screen_countdown), Toast.LENGTH_LONG).show()

            val intent = Intent(this, com.lark.autoclock.scheduler.ClockActionReceiver::class.java)
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(this, 999, intent, pendingFlags)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 10000,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 10000,
                    pendingIntent
                )
            }
        }

        // 3. 单独测试飞书打卡
        findViewById<Button>(R.id.btn_test_clock_in).setOnClickListener {
            val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val serviceName = packageName + "/" + com.lark.autoclock.service.AutoClockAccessibilityService::class.java.name
            if (enabledServices?.contains(serviceName) != true) {
                Toast.makeText(this, getString(R.string.toast_test_no_accessibility), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Toast.makeText(this, getString(R.string.toast_test_clock_starting), Toast.LENGTH_SHORT).show()
            val service = com.lark.autoclock.service.AutoClockAccessibilityService.instance
            if (service != null) {
                service.startClockIn("测试")
            } else {
                Toast.makeText(this, getString(R.string.toast_accessibility_not_connected), Toast.LENGTH_LONG).show()
            }
        }

        // 4. 激活正式任务
        btnScheduleTasks.setOnClickListener {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Toast.makeText(this, getString(R.string.toast_need_exact_alarm_activate), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            
            ClockScheduler.scheduleDailySetup(this)
            Toast.makeText(this, getString(R.string.toast_guard_started), Toast.LENGTH_LONG).show()

            val status = com.lark.autoclock.utils.LocalScheduleManager.getTodayWorkdayStatus(this)
            if (status == com.lark.autoclock.utils.LocalScheduleManager.WorkdayStatus.WORKDAY) {
                ClockScheduler.scheduleTodayClockActions(this)
                Toast.makeText(this, getString(R.string.toast_today_scheduled), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.toast_today_rest), Toast.LENGTH_SHORT).show()
            }
            updateStatusAndPermissionsUI()
        }

        // 5. 查看打卡日志
        findViewById<View>(R.id.btn_view_logs).setOnClickListener {
            showLogsDialog()
        }

        // 6. 配置随机打卡时间段
        findViewById<View>(R.id.btn_config_time).setOnClickListener {
            showTimeConfigDialog()
        }

        findViewById<View>(R.id.btn_manage_exceptions).setOnClickListener {
            showExceptionsDialog()
        }

        // 7. 自启动权限管理
        findViewById<View>(R.id.btn_auto_start).setOnClickListener {
            handleAutoStartClick()
        }

        // 8. 电池优化管理
        findViewById<View>(R.id.btn_battery_optimization).setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        // 9. 前台保活通知开关（第二层保活，遇打卡遗漏时手动开启）
        // 已重构为与权限行一致的 Switch 列表项：Switch 本身不可点（XML clickable=false），
        // 点击事件完全由外层 keepAliveRow 统一分发，开关仅作为状态展示，避免双监听语义重叠
        val keepAliveRow = findViewById<View>(R.id.btn_keepalive)
        val keepAliveSwitch = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_keepalive)
        keepAliveSwitch.isChecked = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_KEEPALIVE_ENABLED, false)
        val toggleKeepAlive = {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val newState = !prefs.getBoolean(KEY_KEEPALIVE_ENABLED, false)
            prefs.edit().putBoolean(KEY_KEEPALIVE_ENABLED, newState).apply()
            keepAliveSwitch.isChecked = newState

            if (newState) {
                androidx.core.content.ContextCompat.startForegroundService(
                    this,
                    Intent(this, com.lark.autoclock.service.KeepAliveService::class.java)
                )
                // 联动预警：无障碍服务未连接时给出明确警示，避免只开保活却无法打卡
                if (com.lark.autoclock.service.AutoClockAccessibilityService.instance == null) {
                    Toast.makeText(this, getString(R.string.toast_keepalive_no_accessibility), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, getString(R.string.toast_keepalive_on), Toast.LENGTH_LONG).show()
                }
            } else {
                stopService(Intent(this, com.lark.autoclock.service.KeepAliveService::class.java))
                Toast.makeText(this, getString(R.string.toast_keepalive_off), Toast.LENGTH_SHORT).show()
            }
        }
        keepAliveRow.setOnClickListener { toggleKeepAlive() }

        // 10. 悬浮窗权限行：点击整行直接跳转授权页（原为纯展示行，状态异常时点击无反应）
        findViewById<View>(R.id.layout_overlay).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.toast_overlay_permission), Toast.LENGTH_LONG).show()
            }
        }

        // 11. 精确闹钟权限行：点击整行直接跳转授权页
        findViewById<View>(R.id.layout_alarm).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // 部分国产 ROM 拦截精确闹钟授权页，回退到应用详情页
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } else {
                Toast.makeText(this, getString(R.string.toast_alarm_not_needed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleAutoStartClick() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isConfigured = prefs.getBoolean(KEY_AUTOSTART_CONFIGURED, false)
        if (isConfigured) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.dialog_title_autostart_manage))
                .setMessage(getString(R.string.dialog_msg_autostart_manage))
                .setPositiveButton(getString(R.string.dialog_btn_autostart_reopen)) { _, _ ->
                    openAutoStartSettings()
                }
                .setNeutralButton(getString(R.string.dialog_btn_autostart_reset)) { _, _ ->
                    prefs.edit().putBoolean(KEY_AUTOSTART_CONFIGURED, false).apply()
                    Toast.makeText(this, getString(R.string.toast_autostart_reset), Toast.LENGTH_SHORT).show()
                    updateStatusAndPermissionsUI()
                }
                .setNegativeButton(getString(R.string.dialog_btn_cancel), null)
                .show()
        } else {
            openAutoStartSettings()
        }
    }

    /**
     * 跳转至国产 ROM 的自启动管理页面
     */
    private fun openAutoStartSettings() {
        pendingAutostartCheck = true

        val intents = arrayOf(
            Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
            Intent().setComponent(android.content.ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
            Intent().setComponent(android.content.ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(android.content.ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
            Intent().setComponent(android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
            Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
            Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")),
            Intent().setComponent(android.content.ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"))
        )

        var success = false
        for (intent in intents) {
            try {
                if (packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    success = true
                    break
                }
            } catch (e: Exception) {
                // Ignore and try next
            }
        }

        if (!success) {
            Toast.makeText(this, getString(R.string.toast_autostart_failed), Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    /**
     * 用户从外部设置返回后，弹窗确认自启动权限是否已开启
     */
    private fun checkAutostartAfterReturn() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_title_autostart_confirm))
            .setMessage(getString(R.string.dialog_msg_autostart_confirm))
            .setPositiveButton(getString(R.string.dialog_btn_autostart_enabled)) { _, _ ->
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_AUTOSTART_CONFIGURED, true).apply()
                Toast.makeText(this, getString(R.string.toast_autostart_configured), Toast.LENGTH_SHORT).show()
                updateStatusAndPermissionsUI()
            }
            .setNegativeButton(getString(R.string.dialog_btn_autostart_later), null)
            .show()
    }

    private fun showExceptionsDialog() {
        val exceptions = com.lark.autoclock.utils.LocalScheduleManager.getAllExceptions(this)
        val sortedKeys = exceptions.keys.sortedDescending()
        val items = sortedKeys.map { date ->
            val type = if (exceptions[date] == "WORK") getString(R.string.exception_type_work_short) else getString(R.string.exception_type_rest_short)
            "$date  -  $type"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_title_exceptions))
            .setItems(items) { _, which ->
                val date = sortedKeys[which]
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.dialog_title_delete_exception))
                    .setMessage(getString(R.string.dialog_msg_delete_exception, date))
                    .setPositiveButton(getString(R.string.dialog_btn_delete)) { _, _ ->
                        com.lark.autoclock.utils.LocalScheduleManager.removeException(this, date)
                        updateConfigPreviews()
                        updateStatusAndPermissionsUI()
                        showExceptionsDialog() // 刷新
                    }
                    .setNegativeButton(getString(R.string.dialog_btn_cancel), null)
                    .show()
            }
            .setPositiveButton(getString(R.string.dialog_btn_add_exception)) { _, _ ->
                val calendar = java.util.Calendar.getInstance()
                android.app.DatePickerDialog(this, { _, year, month, dayOfMonth ->
                    val dateStr = String.format(Locale.getDefault(), "%04d-%02d-%02d", year, month + 1, dayOfMonth)
                    MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.dialog_title_set_status, dateStr))
                        .setItems(arrayOf(getString(R.string.exception_type_work), getString(R.string.exception_type_rest))) { _, which ->
                            val isWork = which == 0
                            com.lark.autoclock.utils.LocalScheduleManager.addException(this, dateStr, isWork)
                            Toast.makeText(this, getString(R.string.toast_exception_added), Toast.LENGTH_SHORT).show()
                            updateConfigPreviews()
                            updateStatusAndPermissionsUI()
                            showExceptionsDialog() // 刷新
                        }
                        .show()
                }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
            }
            .setNegativeButton(getString(R.string.dialog_btn_close), null)
            .show()
    }

    // ... (secure warning methods) ...

    /**
     * 安全锁屏无法被普通应用自动绕过，必须提前提示用户。
     */
    private fun showSecureLockscreenWarning() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_title_lockscreen_warning))
            .setMessage(getString(R.string.dialog_msg_lockscreen_warning))
            .setPositiveButton(getString(R.string.dialog_btn_go_lockscreen_settings)) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.toast_lock_screen_manual), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(getString(R.string.dialog_btn_got_it), null)
            .show()
    }

    /**
     * Android 14+ 可能默认关闭普通应用的全屏通知能力。
     */
    private fun requestFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Toast.makeText(this, getString(R.string.toast_full_screen_notification), Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    /**
     * 请求关闭电池优化（Android 6.0+），在 OPPO/ColorOS 上极其关键
     */
    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    // 部分国产 ROM 拦截了这个 Intent，直接跳转应用详情
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 实时更新各个卡片上的权限状态和顶部服务状态看板
        updateStatusAndPermissionsUI()
        // 刷新设置项当前值副标题预览，无需点进弹窗即可确认生效规则
        updateConfigPreviews()

        // 检查自启动设置返回后的确认弹窗
        if (pendingAutostartCheck) {
            pendingAutostartCheck = false
            checkAutostartAfterReturn()
            return
        }

        // 每次回到主界面都检查一次电池优化状态，但使用 SharedPreferences 避免无限弹窗
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val hasPrompted = prefs.getBoolean(KEY_BATTERY_PROMPTED, false)
            val hasLockscreenPrompted = prefs.getBoolean(KEY_LOCKSCREEN_PROMPTED, false)
            val hasFullScreenPrompted = prefs.getBoolean(KEY_FULL_SCREEN_PROMPTED, false)

            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguardManager.isKeyguardSecure && !hasLockscreenPrompted) {
                prefs.edit().putBoolean(KEY_LOCKSCREEN_PROMPTED, true).apply()
                showSecureLockscreenWarning()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!notificationManager.canUseFullScreenIntent() && !hasFullScreenPrompted) {
                    prefs.edit().putBoolean(KEY_FULL_SCREEN_PROMPTED, true).apply()
                    requestFullScreenIntentPermission()
                    return
                }
            }

            if (!pm.isIgnoringBatteryOptimizations(packageName) && !hasPrompted) {
                prefs.edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply()
                requestIgnoreBatteryOptimization()
                return // 串行处理：跳出，等用户返回再检查下一个
            }
            
            // 检查悬浮窗权限（用于后台启动 Activity 唤醒屏幕）
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, getString(R.string.toast_overlay_permission), Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return // 串行处理：跳出，等用户返回再检查下一个
            }

            // 检查 Android 12+ 精确闹钟权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                if (!alarmManager.canScheduleExactAlarms()) {
                    Toast.makeText(this, getString(R.string.toast_exact_alarm_permission), Toast.LENGTH_LONG).show()
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                        return // 串行处理：跳出，等用户返回再检查下一个
                    } catch (e: Exception) {
                        android.util.Log.e("AutoClock", "跳转精确闹钟设置失败: ${e.message}")
                    }
                }
            }
        }
    }

    private fun showLogsDialog() {
        val logFile = File(filesDir, "clock_log.txt")
        if (!logFile.exists() || logFile.length() == 0L) {
            Toast.makeText(this, getString(R.string.toast_no_logs), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // plainLog 用于「一键复制全部」，与 HTML 渲染版本同源，避免二次文件 IO
            var plainLog = ""
            val logs = try {
                val lines = logFile.readLines().takeLast(100).reversed()
                plainLog = lines.joinToString("\n\n")
                lines.joinToString("<br><br>") { line ->
                    val escaped = android.text.Html.escapeHtml(line)
                    when {
                        line.contains("✅") -> "<font color='#34A853'>$escaped</font>"
                        line.contains("⚠️") -> "<font color='#EA4335'>$escaped</font>"
                        line.contains("❌") -> "<font color='#EA4335'>$escaped</font>"
                        else -> escaped
                    }
                }
            } catch (e: Exception) {
                getString(R.string.toast_read_log_failed, e.message)
            }

            withContext(Dispatchers.Main) {
                val spannedLog: Spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Html.fromHtml(logs, Html.FROM_HTML_MODE_COMPACT)
                } else {
                    @Suppress("DEPRECATION")
                    Html.fromHtml(logs)
                }

                val scrollView = ScrollView(this@MainActivity).apply {
                    setPadding(0, 20, 0, 20)
                }
                val textView = TextView(this@MainActivity).apply {
                    text = spannedLog
                    setPadding(50, 20, 50, 40)
                    textSize = 13f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setLineSpacing(10f, 1.2f)
                    setTextColor(getColor(R.color.log_text_gray))
                    setTextIsSelectable(true)  // 允许用户长按选择并复制日志文字
                }
                scrollView.addView(textView)

                // 底部「一键复制全部」：比长按拖选更友好的排查体验
                val copyButton = android.widget.Button(this@MainActivity).apply {
                    text = getString(R.string.dialog_btn_copy_logs)
                    isAllCaps = false
                    setOnClickListener {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                getString(R.string.dialog_title_logs), plainLog
                            )
                        )
                        Toast.makeText(this@MainActivity, getString(R.string.toast_logs_copied), Toast.LENGTH_SHORT).show()
                    }
                }
                val container = android.widget.LinearLayout(this@MainActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                }
                container.addView(
                    scrollView,
                    android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                )
                container.addView(
                    copyButton,
                    android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(16, 0, 16, 16) }
                )

                val titleView = TextView(this@MainActivity).apply {
                    text = getString(R.string.dialog_title_logs)
                    textSize = 20f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(getColor(R.color.text_dark))
                    setPadding(50, 50, 50, 10)
                }

                MaterialAlertDialogBuilder(this@MainActivity)
                    .setCustomTitle(titleView)
                    .setView(container)
                    .setPositiveButton(getString(R.string.dialog_btn_close), null)
                    .setNeutralButton(getString(R.string.dialog_btn_clear_logs)) { _, _ ->
                        lifecycleScope.launch {
                            val deleted = com.lark.autoclock.utils.LogUtil.clearLog(this@MainActivity)
                            if (deleted) {
                                Toast.makeText(this@MainActivity, getString(R.string.toast_logs_cleared), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .show()
            }
        }
    }

    private fun showTimeConfigDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_time_config, null)
        
        val cbMon = view.findViewById<android.widget.CheckBox>(R.id.cb_mon)
        val cbTue = view.findViewById<android.widget.CheckBox>(R.id.cb_tue)
        val cbWed = view.findViewById<android.widget.CheckBox>(R.id.cb_wed)
        val cbThu = view.findViewById<android.widget.CheckBox>(R.id.cb_thu)
        val cbFri = view.findViewById<android.widget.CheckBox>(R.id.cb_fri)
        val cbSat = view.findViewById<android.widget.CheckBox>(R.id.cb_sat)
        val cbSun = view.findViewById<android.widget.CheckBox>(R.id.cb_sun)
        
        cbMon.isChecked = prefs.getBoolean("cycle_mon", true)
        cbTue.isChecked = prefs.getBoolean("cycle_tue", true)
        cbWed.isChecked = prefs.getBoolean("cycle_wed", true)
        cbThu.isChecked = prefs.getBoolean("cycle_thu", true)
        cbFri.isChecked = prefs.getBoolean("cycle_fri", true)
        cbSat.isChecked = prefs.getBoolean("cycle_sat", false)
        cbSun.isChecked = prefs.getBoolean("cycle_sun", false)

        val tvMStart = view.findViewById<TextView>(R.id.tv_morning_start)
        val tvMEnd = view.findViewById<TextView>(R.id.tv_morning_end)
        val tvAStart = view.findViewById<TextView>(R.id.tv_afternoon_start)
        val tvAEnd = view.findViewById<TextView>(R.id.tv_afternoon_end)

        tvMStart.text = prefs.getString("morning_start", Constants.DEFAULT_MORNING_START)
        tvMEnd.text = prefs.getString("morning_end", Constants.DEFAULT_MORNING_END)
        tvAStart.text = prefs.getString("afternoon_start", Constants.DEFAULT_AFTERNOON_START)
        tvAEnd.text = prefs.getString("afternoon_end", Constants.DEFAULT_AFTERNOON_END)

        val setupTimePicker = { tv: TextView ->
            tv.setOnClickListener {
                val (hour, minute) = try {
                    val parts = tv.text.toString().split(":")
                    parts[0].toInt() to parts[1].toInt()
                } catch (e: Exception) {
                    7 to 30 // 回退默认值
                }
                TimePickerDialog(this, { _, selectedHour, selectedMinute ->
                    tv.text = String.format(Locale.getDefault(), "%02d:%02d", selectedHour, selectedMinute)
                }, hour, minute, true).show()
            }
        }

        setupTimePicker(tvMStart)
        setupTimePicker(tvMEnd)
        setupTimePicker(tvAStart)
        setupTimePicker(tvAEnd)

        val titleView = TextView(this).apply {
            text = getString(R.string.dialog_title_time_config)
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.text_dark))
            setPadding(50, 50, 50, 10)
        }

        MaterialAlertDialogBuilder(this)
            .setCustomTitle(titleView)
            .setView(view)
            .setPositiveButton(getString(R.string.dialog_btn_save_apply)) { _, _ ->
                val mStartStr = tvMStart.text.toString()
                val mEndStr = tvMEnd.text.toString()
                val aStartStr = tvAStart.text.toString()
                val aEndStr = tvAEnd.text.toString()

                // 时间校验已提取为 TimeValidator 纯函数（可单元测试）：同日时段，不支持跨午夜
                if (!TimeValidator.isValidSameDayRange(mStartStr, mEndStr)) {
                    Toast.makeText(this, getString(R.string.toast_save_failed_morning), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (!TimeValidator.isValidSameDayRange(aStartStr, aEndStr)) {
                    Toast.makeText(this, getString(R.string.toast_save_failed_afternoon), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                prefs.edit()
                    .putBoolean("cycle_mon", cbMon.isChecked)
                    .putBoolean("cycle_tue", cbTue.isChecked)
                    .putBoolean("cycle_wed", cbWed.isChecked)
                    .putBoolean("cycle_thu", cbThu.isChecked)
                    .putBoolean("cycle_fri", cbFri.isChecked)
                    .putBoolean("cycle_sat", cbSat.isChecked)
                    .putBoolean("cycle_sun", cbSun.isChecked)
                    .putString("morning_start", mStartStr)
                    .putString("morning_end", mEndStr)
                    .putString("afternoon_start", aStartStr)
                    .putString("afternoon_end", aEndStr)
                    .apply()
                
                // 重新下发闹钟
                val status = com.lark.autoclock.utils.LocalScheduleManager.getTodayWorkdayStatus(this)
                if (status == com.lark.autoclock.utils.LocalScheduleManager.WorkdayStatus.WORKDAY) {
                    ClockScheduler.scheduleTodayClockActions(this)
                    Toast.makeText(this, getString(R.string.toast_time_config_saved_scheduled), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.toast_time_config_saved_rest), Toast.LENGTH_SHORT).show()
                }
                // 弹窗保存后立即刷新主看板与副标题预览（Dialog 关闭不触发 onResume）
                updateStatusAndPermissionsUI()
                updateConfigPreviews()
            }
            .setNegativeButton(getString(R.string.dialog_btn_cancel), null)
            .show()
    }

    /**
     * 设置项当前值副标题预览（P1）：主界面直接展示当前生效的周期、时段与例外规则。
     */
    private fun updateConfigPreviews() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val mStart = prefs.getString("morning_start", Constants.DEFAULT_MORNING_START) ?: Constants.DEFAULT_MORNING_START
        val mEnd = prefs.getString("morning_end", Constants.DEFAULT_MORNING_END) ?: Constants.DEFAULT_MORNING_END
        val aStart = prefs.getString("afternoon_start", Constants.DEFAULT_AFTERNOON_START) ?: Constants.DEFAULT_AFTERNOON_START
        val aEnd = prefs.getString("afternoon_end", Constants.DEFAULT_AFTERNOON_END) ?: Constants.DEFAULT_AFTERNOON_END
        tvSubtitleConfigTime.text =
            getString(R.string.subtitle_config_time, weekSummary(prefs), mStart, mEnd, aStart, aEnd)

        val exceptionCount = com.lark.autoclock.utils.LocalScheduleManager.getAllExceptions(this).size
        tvSubtitleExceptions.text =
            if (exceptionCount > 0) getString(R.string.subtitle_exceptions_fmt, exceptionCount)
            else getString(R.string.subtitle_exceptions_none)
    }

    /**
     * 将周期勾选状态归纳为易读文案：每天 / 工作日（周一至周五）/ 逐项列举 / 未选择。
     * 默认值与 showTimeConfigDialog 保持一致：周一至周五开启，周末关闭。
     */
    private fun weekSummary(prefs: android.content.SharedPreferences): String {
        val weekLabels = listOf(
            "cycle_mon" to getString(R.string.week_mon),
            "cycle_tue" to getString(R.string.week_tue),
            "cycle_wed" to getString(R.string.week_wed),
            "cycle_thu" to getString(R.string.week_thu),
            "cycle_fri" to getString(R.string.week_fri),
            "cycle_sat" to getString(R.string.week_sat),
            "cycle_sun" to getString(R.string.week_sun)
        )
        val defaults = listOf(true, true, true, true, true, false, false)
        val selected = weekLabels.mapIndexed { i, (key, label) ->
            if (prefs.getBoolean(key, defaults[i])) label else null
        }.filterNotNull()
        return when {
            selected.size == 7 -> getString(R.string.week_summary_everyday)
            selected == weekLabels.take(5).map { it.second } -> getString(R.string.week_summary_workday)
            selected.isEmpty() -> getString(R.string.week_summary_none)
            else -> selected.joinToString("、")
        }
    }

    private fun updateStatusAndPermissionsUI() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. 无障碍权限状态
        val isAccessibilityEnabled = isAccessibilityServiceEnabled(this)
        if (isAccessibilityEnabled) {
            tvAccessibilityStatus.text = getString(R.string.status_authorized)
            tvAccessibilityStatus.setTextColor(getColor(R.color.status_green))
        } else {
            tvAccessibilityStatus.text = getString(R.string.status_unauthorized)
            tvAccessibilityStatus.setTextColor(getColor(R.color.status_red))
        }

        // 2. 电池优化白名单状态
        val isBatteryOptimizationIgnored = isIgnoringBatteryOptimizations(this)
        if (isBatteryOptimizationIgnored) {
            tvBatteryStatus.text = getString(R.string.status_battery_closed)
            tvBatteryStatus.setTextColor(getColor(R.color.status_green))
        } else {
            tvBatteryStatus.text = getString(R.string.status_not_closed)
            tvBatteryStatus.setTextColor(getColor(R.color.status_red))
        }

        // 3. 悬浮窗权限状态
        val isOverlayEnabled = Settings.canDrawOverlays(this)
        if (isOverlayEnabled) {
            tvOverlayStatus.text = getString(R.string.status_authorized)
            tvOverlayStatus.setTextColor(getColor(R.color.status_green))
        } else {
            tvOverlayStatus.text = getString(R.string.status_unauthorized)
            tvOverlayStatus.setTextColor(getColor(R.color.status_red))
        }

        // 4. 精确闹钟权限状态
        val isAlarmEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
        if (isAlarmEnabled) {
            tvAlarmStatus.text = getString(R.string.status_ready)
            tvAlarmStatus.setTextColor(getColor(R.color.status_green))
        } else {
            tvAlarmStatus.text = getString(R.string.status_not_ready)
            tvAlarmStatus.setTextColor(getColor(R.color.status_red))
        }

        // 5. 开机自启动权限配置状态（基于用户操作闭环标记）
        val isAutostartConfigured = prefs.getBoolean(KEY_AUTOSTART_CONFIGURED, false)
        if (isAutostartConfigured) {
            tvAutostartStatus.text = getString(R.string.status_autostart_configured)
            tvAutostartStatus.setTextColor(getColor(R.color.status_green))
        } else {
            tvAutostartStatus.text = getString(R.string.status_go_config)
            tvAutostartStatus.setTextColor(getColor(R.color.primary_blue))
        }

        // 6. 全局状态卡片与主按钮更新
        val isServiceRunning = isAccessibilityEnabled // 无障碍开启即代表服务在后台激活

        if (isServiceRunning) {
            // 服务已在运行时，主按钮语义切换为“重新同步 / 刷新今日守护”，避免与上方看板“今日打卡已安排”产生认知冲突
            btnScheduleTasks.setText(R.string.btn_refresh_guard)
            btnScheduleTasks.setIconResource(R.drawable.ic_autorenew)
            btnScheduleTasks.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.primary_blue))

            val status = com.lark.autoclock.utils.LocalScheduleManager.getTodayWorkdayStatus(this)
            if (status == com.lark.autoclock.utils.LocalScheduleManager.WorkdayStatus.WORKDAY) {
                // 与 Constants 中共享的默认时段一致
                val mStart = prefs.getString("morning_start", Constants.DEFAULT_MORNING_START) ?: Constants.DEFAULT_MORNING_START
                val mEnd = prefs.getString("morning_end", Constants.DEFAULT_MORNING_END) ?: Constants.DEFAULT_MORNING_END
                val aStart = prefs.getString("afternoon_start", Constants.DEFAULT_AFTERNOON_START) ?: Constants.DEFAULT_AFTERNOON_START
                val aEnd = prefs.getString("afternoon_end", Constants.DEFAULT_AFTERNOON_END) ?: Constants.DEFAULT_AFTERNOON_END
                tvGlobalStatus.text = getString(R.string.status_global_scheduled)
                tvGlobalStatus.setTextColor(getColor(R.color.success_green))
                tvGlobalStatusDesc.text = getString(R.string.status_desc_global_scheduled)
                cardGlobalStatus.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(getColor(R.color.success_green_bg)))

                // 时段胶囊：结构化展示上/下午随机窗口及今日打卡进度（双行状态指示）
                val isMorningDone = ClockScheduler.isClockAlreadyCompletedToday(this, Constants.CLOCK_TYPE_CLOCK_IN)
                val isAfternoonDone = ClockScheduler.isClockAlreadyCompletedToday(this, Constants.CLOCK_TYPE_CLOCK_OUT)

                chipMorning.text = formatChipText(
                    getString(R.string.chip_morning_window, mStart, mEnd),
                    mEnd,
                    isMorningDone
                )
                chipAfternoon.text = formatChipText(
                    getString(R.string.chip_afternoon_window, aStart, aEnd),
                    aEnd,
                    isAfternoonDone
                )
                layoutStatusChips.visibility = View.VISIBLE
            } else {
                tvGlobalStatus.text = getString(R.string.status_global_rest)
                tvGlobalStatus.setTextColor(getColor(R.color.primary_blue))
                tvGlobalStatusDesc.text = getString(R.string.status_desc_global_rest)
                cardGlobalStatus.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(getColor(R.color.light_blue_bg)))
                layoutStatusChips.visibility = View.GONE
            }
        } else {
            // 服务未运行时，保持大绿色的“立即启用”主行动点
            btnScheduleTasks.setText(R.string.btn_start_guard)
            btnScheduleTasks.setIconResource(R.drawable.ic_play_arrow)
            btnScheduleTasks.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.status_green))

            tvGlobalStatus.text = getString(R.string.status_global_not_running)
            tvGlobalStatus.setTextColor(getColor(R.color.error_red_dark))
            tvGlobalStatusDesc.text = getString(R.string.status_desc_global_not_running)
            cardGlobalStatus.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(getColor(R.color.error_bg)))
            layoutStatusChips.visibility = View.GONE
        }
    }

    /**
     * 格式化时段胶囊内容（双行展示：第 1 行时段窗口，第 2 行打卡完成状态）
     * 状态分为三态：
     * 1. 已打卡完成 -> 今日已完成 ✅ (状态绿)
     * 2. 未完成且已逾期 -> 已过窗口未打卡 ⚠️ (状态红，强提示用户去手动补卡)
     * 3. 未完成且未到期 -> 等待自动触发 ⏳ (品牌蓝)
     */
    private fun formatChipText(windowText: String, endTime: String, isDone: Boolean): SpannableString {
        val (statusText, color) = when {
            isDone -> getString(R.string.status_clock_done) to getColor(R.color.status_green)
            TimeValidator.isTimePassed(endTime) -> getString(R.string.status_clock_missed) to getColor(R.color.status_red)
            else -> getString(R.string.status_clock_pending) to getColor(R.color.primary_blue)
        }
        val fullText = "$windowText\n$statusText"
        val spannable = SpannableString(fullText)

        // 第 1 行：时段窗口描述（使用次级文字颜色）
        spannable.setSpan(
            ForegroundColorSpan(getColor(R.color.text_secondary)),
            0,
            windowText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // 第 2 行：打卡完成状态（粗体，状态色彩分明）
        val startIndex = windowText.length + 1
        val endIndex = fullText.length
        spannable.setSpan(
            ForegroundColorSpan(color),
            startIndex,
            endIndex,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            startIndex,
            endIndex,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val serviceName = context.packageName + "/" + com.lark.autoclock.service.AutoClockAccessibilityService::class.java.name
        return enabledServices?.contains(serviceName) == true
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true
    }
}
