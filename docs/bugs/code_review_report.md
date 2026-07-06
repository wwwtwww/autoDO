# Bug Investigation & RCA Report — autoDO

## 1. Bug 排查背景与分析 (Symptom Analysis)
在持续维护与实测过程中，针对 Android 12+ (API 31+) 设备及各国产 ROM 开展了深度的稳定性及异常排查。排查重点围绕**打卡定时任务静默失效**、**精确闹钟权限缺失隐患**以及**时间配置非法输入校验**展开。

---

## 2. 根因分析 (Root Cause Analysis - RCA)

### 🔴 RCA 1: Android 12+ 精确闹钟权限缺失导致打卡任务静默失效
* **根因路径**: `ClockScheduler.kt` 在设置精确闹钟前检查了 `alarmManager.canScheduleExactAlarms()`。当权限未授予时仅输出 Log 并返回，而 `MainActivity.kt` 仍会提示“随机打卡闹钟已下发”，使用户误以为打卡已被安排，实则系统闹钟完全未设定。
* **影响范围**: Android 12 及以上版本设备。

### 🟡 RCA 2: 时间配置边界与非法输入校验缺失
* **根因路径**: `MainActivity.kt` 的 `showTimeConfigDialog()` 在用户保存上下班时间段时未强制要求 `结束时间 > 开始时间`。若用户错选倒置时间（如 `18:30 ~ 18:00`），虽有防御兜底，但会导致随机延迟变为 0，防作弊打卡退化为固定时间。

---

## 3. 修复方案与防护建立 (Implementation & Verification)

### ✅ 1. 增加 `canScheduleExactAlarms` 权限自动检测与弹窗引导
* 在 [MainActivity.kt](file:///E:/autoDO/app/src/main/java/com/lark/autoclock/MainActivity.kt) 的 `onResume()` 中增加了针对 `canScheduleExactAlarms()` 的检测，若未授权则引导跳转至系统精确闹钟权限设置页。

### ✅ 2. 增加随机打卡时间段保存的前置有效性校验
* 在 [MainActivity.kt](file:///E:/autoDO/app/src/main/java/com/lark/autoclock/MainActivity.kt) 的 `showTimeConfigDialog()` 中增加了 `isTimeValid` 校验，强制要求 `结束时间 > 开始时间`，否则阻止保存并提示。

### ✅ 3. 一键 ADB 脚本升级
* 在 [scripts/setup_device.bat](file:///E:/autoDO/scripts/setup_device.bat) 中补全了 `SCHEDULE_EXACT_ALARM` 权限授权指令：
  `adb shell appops set com.lark.autoclock SCHEDULE_EXACT_ALARM allow`

### ✅ 4. 建立自动化单元测试防线
* 新建 [HolidayAndScheduleTest.kt](file:///E:/autoDO/app/src/test/java/com/lark/autoclock/HolidayAndScheduleTest.kt) 单元测试用例，保障打卡时间区间与倒置回退逻辑的稳定运行。

---

## 4. 结论 (Status: VERIFIED & FIXED)
经过本次排查与防护升级，项目在权限前置校验、随机闹钟调度安全性及用户配置合法性方面均达到了极高的鲁棒性。
