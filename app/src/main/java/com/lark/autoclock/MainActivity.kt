package com.lark.autoclock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.SharedPreferences
import android.os.PowerManager
import android.provider.Settings
import android.text.Html
import android.text.Spanned
import android.widget.Button
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
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
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
            Toast.makeText(this, "正在启动飞书自动打卡...", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, com.lark.autoclock.service.AutoClockAccessibilityService::class.java)
            intent.action = "ACTION_START_CLOCK_IN"
            startService(intent)
        }

        // 4. 激活正式任务
        findViewById<Button>(R.id.btn_schedule_tasks).setOnClickListener {
            ClockScheduler.scheduleDailySetup(this)
            Toast.makeText(this, "调度系统已就绪！每晚 00:30 将自动查询 API 并在工作日随机安排。", Toast.LENGTH_LONG).show()

            Thread {
                if (HolidayHelper.isTodayWorkday()) {
                    runOnUiThread {
                        ClockScheduler.scheduleTodayClockActions(this@MainActivity)
                        Toast.makeText(this@MainActivity, "今天为工作日，防作弊随机打卡已下发系统底层闹钟！", Toast.LENGTH_LONG).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "网络查询结果：今天是节假日，休息！", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        // 5. 查看打卡日志
        findViewById<Button>(R.id.btn_view_logs).setOnClickListener {
            showLogsDialog()
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

            if (!pm.isIgnoringBatteryOptimizations(packageName) && !hasPrompted) {
                prefs.edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply()
                requestIgnoreBatteryOptimization()
            }
            
            // 检查悬浮窗权限（用于后台启动 Activity 唤醒屏幕）
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请开启悬浮窗权限，否则锁屏打卡将无法唤醒屏幕！", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
    }

    private fun showLogsDialog() {
        val logFile = File(filesDir, "clock_log.txt")
        if (!logFile.exists() || logFile.length() == 0L) {
            Toast.makeText(this, "暂无打卡记录", Toast.LENGTH_SHORT).show()
            return
        }

        val logs = try {
            logFile.readLines().reversed().joinToString("<br><br>") { line ->
                when {
                    line.contains("✅") -> "<font color='#4CAF50'>$line</font>"
                    line.contains("⚠️") -> "<font color='#F44336'>$line</font>"
                    else -> line
                }
            }
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }

        val spannedLog: Spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(logs, Html.FROM_HTML_MODE_COMPACT)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(logs)
        }

        val scrollView = ScrollView(this)
        val textView = TextView(this).apply {
            text = spannedLog
            setPadding(50, 50, 50, 50)
            textSize = 14f
        }
        scrollView.addView(textView)

        AlertDialog.Builder(this)
            .setTitle("打卡历史记录 (最近在上)")
            .setView(scrollView)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空记录") { _, _ ->
                if (logFile.delete()) {
                    Toast.makeText(this, "记录已清空", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }
}
