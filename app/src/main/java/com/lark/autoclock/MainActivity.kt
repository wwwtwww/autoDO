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
        findViewById<Button>(R.id.btn_enable_accessibility).setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // 2. 测试亮屏与解锁
        findViewById<Button>(R.id.btn_test_unlock).setOnClickListener {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Toast.makeText(this, "测试亮屏需要精确闹钟权限，请在设置中授予！", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "倒计时 10 秒钟，请立刻按电源键锁屏！", Toast.LENGTH_LONG).show()

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
                Toast.makeText(this, "测试打卡失败：请先开启 autoDO 无障碍服务！", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "正在启动极速打卡测试流...", Toast.LENGTH_SHORT).show()
            val service = com.lark.autoclock.service.AutoClockAccessibilityService.instance
            if (service != null) {
                service.startClockIn("测试")
            } else {
                Toast.makeText(this, "无障碍服务未连接，请重新开启后再试", Toast.LENGTH_LONG).show()
            }
        }

        // 4. 激活正式任务
        findViewById<Button>(R.id.btn_schedule_tasks).setOnClickListener {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Toast.makeText(this, "激活任务需要精确闹钟权限，请在设置中授予！", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            
            ClockScheduler.scheduleDailySetup(this)
            Toast.makeText(this, "守护进程已启动，请将手机放置在充电座上即可，无需其他操作。", Toast.LENGTH_LONG).show()

            val status = com.lark.autoclock.utils.LocalScheduleManager.getTodayWorkdayStatus(this)
            if (status == com.lark.autoclock.utils.LocalScheduleManager.WorkdayStatus.WORKDAY) {
                ClockScheduler.scheduleTodayClockActions(this)
                Toast.makeText(this, "今天为打卡日，今日的随机打卡闹钟已下发！", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "本地配置：今天休息，不执行打卡任务。", Toast.LENGTH_SHORT).show()
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
        findViewById<Button>(R.id.btn_auto_start).setOnClickListener {
            openAutoStartSettings()
        }

        // 8. 电池优化管理
        findViewById<Button>(R.id.btn_battery_optimization).setOnClickListener {
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
                    Toast.makeText(this, "⚠️ 前台保活已开启，但无障碍打卡服务未连接！请同时去开启无障碍，否则打卡无法工作！", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "前台保活已开启，常驻通知将防止进程被挂起", Toast.LENGTH_LONG).show()
                }
            } else {
                stopService(Intent(this, com.lark.autoclock.service.KeepAliveService::class.java))
                Toast.makeText(this, "前台保活已关闭", Toast.LENGTH_SHORT).show()
            }
            updateKeepAliveButtonUI(btnKeepAlive)
        }
    }

    private fun updateKeepAliveButtonUI(button: Button) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(KEY_KEEPALIVE_ENABLED, false)
        if (isEnabled) {
            button.text = "🔔  前台保活：已开启（点击关闭）"
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#FFF3E0")
            )
        } else {
            button.text = "🔔  前台保活通知（遇遗漏再开启）"
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#FFF8E1")
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
            Toast.makeText(this, "未能自动打开自启动管理，请在设置中手动开启", Toast.LENGTH_LONG).show()
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
            val type = if (exceptions[date] == "WORK") "强制打卡 (补班)" else "强制休息 (节假日)"
            "$date  -  $type"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("例外日期管理")
            .setItems(items) { _, which ->
                val date = sortedKeys[which]
                AlertDialog.Builder(this)
                    .setTitle("删除例外")
                    .setMessage("确定要删除 $date 的例外配置吗？")
                    .setPositiveButton("删除") { _, _ ->
                        com.lark.autoclock.utils.LocalScheduleManager.removeException(this, date)
                        showExceptionsDialog() // 刷新
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setPositiveButton("添加例外") { _, _ ->
                val calendar = java.util.Calendar.getInstance()
                android.app.DatePickerDialog(this, { _, year, month, dayOfMonth ->
                    val dateStr = String.format(Locale.getDefault(), "%04d-%02d-%02d", year, month + 1, dayOfMonth)
                    AlertDialog.Builder(this)
                        .setTitle("设置 $dateStr 的状态")
                        .setItems(arrayOf("强制打卡 (调休补班)", "强制休息 (法定节假日)")) { _, which ->
                            val isWork = which == 0
                            com.lark.autoclock.utils.LocalScheduleManager.addException(this, dateStr, isWork)
                            Toast.makeText(this, "已添加例外规则", Toast.LENGTH_SHORT).show()
                            showExceptionsDialog() // 刷新
                        }
                        .show()
                }, calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH), calendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ... (secure warning methods) ...

    /**
     * 安全锁屏无法被普通应用自动绕过，必须提前提示用户。
     */
    private fun showSecureLockscreenWarning() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("锁屏密码会阻止自动打卡")
            .setMessage("当前设备启用了 PIN、图案、指纹、人脸或其他安全锁屏。Android 不允许应用自动绕过这些验证。若要在锁屏状态自动打卡，请将测试手机锁屏方式改为无密码或滑动解锁。")
            .setPositiveButton("去锁屏设置") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, "请手动打开系统锁屏设置", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("我知道了", null)
            .show()
    }

    /**
     * Android 14+ 可能默认关闭普通应用的全屏通知能力。
     */
    private fun requestFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Toast.makeText(this, "请允许全屏通知，否则锁屏时可能无法唤醒打卡界面", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this, "请开启悬浮窗权限，否则锁屏打卡将无法唤醒屏幕！", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "请授权精确闹钟权限，否则打卡闹钟将无法准时触发！", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "暂无打卡记录", Toast.LENGTH_SHORT).show()
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
                "读取日志失败: ${e.message}"
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
                    setTextColor(android.graphics.Color.parseColor("#3C4043"))
                }
                scrollView.addView(textView)

                val titleView = TextView(this@MainActivity).apply {
                    text = "历史打卡记录"
                    textSize = 20f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(android.graphics.Color.parseColor("#202124"))
                    setPadding(50, 50, 50, 10)
                }

                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setCustomTitle(titleView)
                    .setView(scrollView)
                    .setPositiveButton("关闭", null)
                    .setNeutralButton("清空记录") { _, _ ->
                        if (logFile.delete()) {
                            Toast.makeText(this@MainActivity, "记录已清空", Toast.LENGTH_SHORT).show()
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
            text = "配置基础周期与时间段"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.parseColor("#202124"))
            setPadding(50, 50, 50, 10)
        }

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setCustomTitle(titleView)
            .setView(view)
            .setPositiveButton("保存并生效") { _, _ ->
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
                    Toast.makeText(this, "保存失败：上午打卡结束时间必须晚于开始时间！", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (!isTimeValid(aStartStr, aEndStr)) {
                    Toast.makeText(this, "保存失败：下午打卡结束时间必须晚于开始时间！", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "时间配置已保存，今日闹钟已重新调度！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "时间配置已保存，今天为休息日不排班。", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
