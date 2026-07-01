package com.lark.autoclock

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lark.autoclock.utils.UnlockHelper
import com.lark.autoclock.scheduler.ClockScheduler
import com.lark.autoclock.utils.HolidayHelper

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_enable_accessibility).setOnClickListener {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        findViewById<Button>(R.id.btn_test_unlock).setOnClickListener {
            Toast.makeText(this, "倒计时 10 秒钟...", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({
                UnlockHelper.wakeUpScreen(this)
                val intent = Intent(this, com.lark.autoclock.service.AutoClockAccessibilityService::class.java)
                intent.action = "ACTION_TEST_UNLOCK"
                startService(intent)
            }, 10000)
        }

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
            
            // 为了调试方便，点击该按钮会强制立即为今天（如果今天是工作日）计算并部署一套定时打卡锁
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
    }
}
