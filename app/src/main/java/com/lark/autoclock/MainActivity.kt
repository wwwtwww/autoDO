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
import android.text.Html
import android.text.Spanned
import android.view.LayoutInflater
import android.widget.Button
import android.app.TimePickerDialog
import java.util.Locale
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import com.lark.autoclock.scheduler.ClockScheduler

class MainActivity : AppCompatActivity() {
    private val PREFS_NAME = ClockScheduler.PREFS_NAME
    private val KEY_BATTERY_PROMPTED = "battery_prompted"
    private val KEY_LOCKSCREEN_PROMPTED = "lockscreen_prompted"
    private val KEY_FULL_SCREEN_PROMPTED = "full_screen_prompted"
    private val KEY_KEEPALIVE_ENABLED = ClockScheduler.KEY_KEEPALIVE_ENABLED

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

        // 1. 跳转无障碍设置
        findViewById<View>(R.id.btn_enable_accessibility).setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
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
        findViewById<Button>(R.id.btn_schedule_tasks).setOnClickListener {
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
        }

        // 5. 查看打卡日志
        findViewById<Button>(R.id.btn_view_logs).setOnClickListener {
            showLogsDialog()
        }

        // 6. 配置随机打卡时间段
        findViewById<Button>(R.id.btn_config_time).setOnClickListener {
            showTimeConfigDialog()
        }

        findViewById<Button>(R.id.btn_manage_exceptions).setOnClickListener {
            showExceptionsDialog()
        }

        // 7. 自启动权限管理
        findViewById<View>(R.id.btn_auto_start).setOnClickListener {
            openAutoStartSettings()
        }

        // 8. 电池优化管理
        findViewById<View>(R.id.btn_battery_optimization).setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        // 9. 前台保活通知开关（第二层保活，遇打卡遗漏时手动开启）
        val btnKeepAlive = findViewById<Button>(R.id.btn_keepalive)
        updateKeepAliveButtonUI(btnKeepAlive)
        btnKeepAlive.setOnClickListener {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean(KEY_KEEPALIVE_ENABLED, false)
            val newState = !isEnabled
            prefs.edit().putBoolean(KEY_KEEPALIVE_ENABLED, newState).apply()

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
            updateKeepAliveButtonUI(btnKeepAlive)
        }
    }

    private fun updateKeepAliveButtonUI(button: Button) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(KEY_KEEPALIVE_ENABLED, false)
        if (isEnabled) {
            button.text = getString(R.string.btn_keepalive_on)
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.keepalive_on_bg)
            )
        } else {
            button.text = getString(R.string.btn_keepalive)
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.keepalive_off_bg)
            )
        }
    }

    /**
     * 跳转至国产 ROM 的自启动管理页面
     */
    private fun openAutoStartSettings() {
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

    private fun showExceptionsDialog() {
        val exceptions = com.lark.autoclock.utils.LocalScheduleManager.getAllExceptions(this)
        val sortedKeys = exceptions.keys.sortedDescending()
        val items = sortedKeys.map { date ->
            val type = if (exceptions[date] == "WORK") getString(R.string.exception_type_work_short) else getString(R.string.exception_type_rest_short)
            "$date  -  $type"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_title_exceptions))
            .setItems(items) { _, which ->
                val date = sortedKeys[which]
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_title_delete_exception))
                    .setMessage(getString(R.string.dialog_msg_delete_exception, date))
                    .setPositiveButton(getString(R.string.dialog_btn_delete)) { _, _ ->
                        com.lark.autoclock.utils.LocalScheduleManager.removeException(this, date)
                        showExceptionsDialog() // 刷新
                    }
                    .setNegativeButton(getString(R.string.dialog_btn_cancel), null)
                    .show()
            }
            .setPositiveButton(getString(R.string.dialog_btn_add_exception)) { _, _ ->
                val calendar = java.util.Calendar.getInstance()
                android.app.DatePickerDialog(this, { _, year, month, dayOfMonth ->
                    val dateStr = String.format(Locale.getDefault(), "%04d-%02d-%02d", year, month + 1, dayOfMonth)
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.dialog_title_set_status, dateStr))
                        .setItems(arrayOf(getString(R.string.exception_type_work), getString(R.string.exception_type_rest))) { _, which ->
                            val isWork = which == 0
                            com.lark.autoclock.utils.LocalScheduleManager.addException(this, dateStr, isWork)
                            Toast.makeText(this, getString(R.string.toast_exception_added), Toast.LENGTH_SHORT).show()
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
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
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
            val logs = try {
                logFile.readLines().takeLast(100).reversed().joinToString("<br><br>") { line ->
                    val escaped = android.text.Html.escapeHtml(line)
                    when {
                        line.contains("✅") -> "<font color='#34A853'>$escaped</font>"
                        line.contains("⚠️") -> "<font color='#EA4335'>$escaped</font>"
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
                }
                scrollView.addView(textView)

                val titleView = TextView(this@MainActivity).apply {
                    text = getString(R.string.dialog_title_logs)
                    textSize = 20f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(getColor(R.color.text_dark))
                    setPadding(50, 50, 50, 10)
                }

                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setCustomTitle(titleView)
                    .setView(scrollView)
                    .setPositiveButton(getString(R.string.dialog_btn_close), null)
                    .setNeutralButton(getString(R.string.dialog_btn_clear_logs)) { _, _ ->
                        if (logFile.delete()) {
                            Toast.makeText(this@MainActivity, getString(R.string.toast_logs_cleared), Toast.LENGTH_SHORT).show()
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

        tvMStart.text = prefs.getString("morning_start", "07:30")
        tvMEnd.text = prefs.getString("morning_end", "08:20")
        tvAStart.text = prefs.getString("afternoon_start", "18:00")
        tvAEnd.text = prefs.getString("afternoon_end", "18:10")

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

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setCustomTitle(titleView)
            .setView(view)
            .setPositiveButton(getString(R.string.dialog_btn_save_apply)) { _, _ ->
                val mStartStr = tvMStart.text.toString()
                val mEndStr = tvMEnd.text.toString()
                val aStartStr = tvAStart.text.toString()
                val aEndStr = tvAEnd.text.toString()

                val isTimeValid = { s: String, e: String ->
                    try {
                        val regex = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")
                        if (!regex.matches(s) || !regex.matches(e)) false
                        else {
                            val (sh, sm) = s.split(":").map { it.toInt() }
                            val (eh, em) = e.split(":").map { it.toInt() }
                            (eh * 60 + em) > (sh * 60 + sm)
                        }
                    } catch (ex: Exception) {
                        false
                    }
                }

                if (!isTimeValid(mStartStr, mEndStr)) {
                    Toast.makeText(this, getString(R.string.toast_save_failed_morning), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (!isTimeValid(aStartStr, aEndStr)) {
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
            }
            .setNegativeButton(getString(R.string.dialog_btn_cancel), null)
            .show()
    }

    private fun updateStatusAndPermissionsUI() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. 无障碍权限状态
        val isAccessibilityEnabled = isAccessibilityServiceEnabled(this)
        val tvAccessibilityStatus = findViewById<TextView>(R.id.tv_status_accessibility)
        if (isAccessibilityEnabled) {
            tvAccessibilityStatus.text = getString(R.string.status_authorized)
            tvAccessibilityStatus.setTextColor(getColor(R.color.status_green))
        } else {
            tvAccessibilityStatus.text = getString(R.string.status_unauthorized)
            tvAccessibilityStatus.setTextColor(getColor(R.color.status_red))
        }

        // 2. 电池优化白名单状态
        val isBatteryOptimizationIgnored = isIgnoringBatteryOptimizations(this)
        val tvBatteryStatus = findViewById<TextView>(R.id.tv_status_battery)
        if (isBatteryOptimizationIgnored) {
            tvBatteryStatus.text = getString(R.string.status_battery_closed)
            tvBatteryStatus.setTextColor(getColor(R.color.status_green))
        } else {
            tvBatteryStatus.text = getString(R.string.status_not_closed)
            tvBatteryStatus.setTextColor(getColor(R.color.status_red))
        }

        // 3. 悬浮窗权限状态
        val isOverlayEnabled = Settings.canDrawOverlays(this)
        val tvOverlayStatus = findViewById<TextView>(R.id.tv_status_overlay)
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
        val tvAlarmStatus = findViewById<TextView>(R.id.tv_status_alarm)
        if (isAlarmEnabled) {
            tvAlarmStatus.text = getString(R.string.status_ready)
            tvAlarmStatus.setTextColor(getColor(R.color.status_green))
        } else {
            tvAlarmStatus.text = getString(R.string.status_not_ready)
            tvAlarmStatus.setTextColor(getColor(R.color.status_red))
        }

        // 5. 全局状态卡片更新
        val isServiceRunning = isAccessibilityEnabled // 无障碍开启即代表服务在后台激活
        val tvGlobalStatus = findViewById<TextView>(R.id.tv_global_status)
        val tvGlobalStatusDesc = findViewById<TextView>(R.id.tv_global_status_desc)
        val globalStatusCard = findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_global_status)

        if (isServiceRunning) {
            val status = com.lark.autoclock.utils.LocalScheduleManager.getTodayWorkdayStatus(this)
            if (status == com.lark.autoclock.utils.LocalScheduleManager.WorkdayStatus.WORKDAY) {
                val mStart = prefs.getString("morning_start", "08:00")
                val mEnd = prefs.getString("morning_end", "08:10")
                val aStart = prefs.getString("afternoon_start", "18:00")
                val aEnd = prefs.getString("afternoon_end", "18:10")
                tvGlobalStatus.text = getString(R.string.status_global_scheduled)
                tvGlobalStatus.setTextColor(getColor(R.color.success_green))
                tvGlobalStatusDesc.text = getString(R.string.status_desc_global_scheduled, mStart, mEnd, aStart, aEnd)
                globalStatusCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(getColor(R.color.success_green_bg)))
            } else {
                tvGlobalStatus.text = getString(R.string.status_global_rest)
                tvGlobalStatus.setTextColor(getColor(R.color.primary_blue))
                tvGlobalStatusDesc.text = getString(R.string.status_desc_global_rest)
                globalStatusCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(getColor(R.color.light_blue_bg)))
            }
        } else {
            tvGlobalStatus.text = getString(R.string.status_global_not_running)
            tvGlobalStatus.setTextColor(getColor(R.color.error_red_dark))
            tvGlobalStatusDesc.text = getString(R.string.status_desc_global_not_running)
            globalStatusCard.setCardBackgroundColor(android.content.res.ColorStateList.valueOf(getColor(R.color.error_bg)))
        }
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
