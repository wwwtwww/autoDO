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
import com.lark.autoclock.utils.HolidayHelper

class MainActivity : AppCompatActivity() {
    private val PREFS_NAME = "AutoClockPrefs"
    private val KEY_BATTERY_PROMPTED = "battery_prompted"
    private val KEY_LOCKSCREEN_PROMPTED = "lockscreen_prompted"
    private val KEY_FULL_SCREEN_PROMPTED = "full_screen_prompted"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 请求 Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // 1. 跳转无障碍设置
        findViewById<Button>(R.id.btn_enable_accessibility).setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // 2. 测试亮屏与解锁
        findViewById<Button>(R.id.btn_test_unlock).setOnClickListener {
            Toast.makeText(this, "倒计时 10 秒钟，请立刻按电源键锁屏！", Toast.LENGTH_LONG).show()

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(this, com.lark.autoclock.scheduler.ClockActionReceiver::class.java)
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(this, 999, intent, pendingFlags)

            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 10000,
                pendingIntent
            )
        }

        // 3. 单独测试飞书打卡
        findViewById<Button>(R.id.btn_test_clock_in).setOnClickListener {
            Toast.makeText(this, "正在启动极速打卡测试流...", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, com.lark.autoclock.service.AutoClockAccessibilityService::class.java)
            intent.action = "ACTION_START_CLOCK_IN"
            startService(intent)
        }

        // 4. 激活正式任务
        findViewById<Button>(R.id.btn_schedule_tasks).setOnClickListener {
            ClockScheduler.scheduleDailySetup(this)
            Toast.makeText(this, "守护进程已启动，请将手机放置在充电座上即可，无需其他操作。", Toast.LENGTH_LONG).show()

            lifecycleScope.launch {
                val status = withContext(Dispatchers.IO) { HolidayHelper.getTodayWorkdayStatus() }
                val resolvedStatus = if (status == HolidayHelper.WorkdayStatus.UNKNOWN) {
                    val dayOfWeek = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
                    HolidayHelper.resolveStatusOnNetworkFailure(dayOfWeek)
                } else status

                if (resolvedStatus == HolidayHelper.WorkdayStatus.WORKDAY) {
                    ClockScheduler.scheduleTodayClockActions(this@MainActivity)
                    Toast.makeText(this@MainActivity, "今天为工作日，今日的随机打卡闹钟已下发！", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "节假日判定：今天休息，不执行打卡任务。", Toast.LENGTH_SHORT).show()
                }
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
    }

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
                    when {
                        line.contains("✅") -> "<font color='#34A853'>$line</font>"
                        line.contains("⚠️") -> "<font color='#EA4335'>$line</font>"
                        else -> line
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
                val time = tv.text.toString().split(":")
                val hour = time[0].toInt()
                val minute = time[1].toInt()
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
            text = "配置随机打卡时间段"
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
                    .putString("morning_start", mStartStr)
                    .putString("morning_end", mEndStr)
                    .putString("afternoon_start", aStartStr)
                    .putString("afternoon_end", aEndStr)
                    .apply()
                
                // 重新下发闹钟
                ClockScheduler.scheduleTodayClockActions(this)
                Toast.makeText(this, "时间配置已保存，今日闹钟已重新调度！", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
