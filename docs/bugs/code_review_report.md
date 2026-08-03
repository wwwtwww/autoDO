# Bug Investigation & RCA Report — autoDO

## 1. Bug 排查背景与分析 (Symptom Analysis)
在持续维护与实测过程中，针对 Android 12+ (API 31+) 设备及各国产 ROM 开展了深度的稳定性及异常排查。排查重点围绕**打卡定时任务静默失效**、**精确闹钟权限缺失隐患**以及**时间配置非法输入校验**展开。

---

## 2. 根因分析 (Root Cause Analysis - RCA)

### 🔴 RCA 3: realme 大师探索版 (realme UI / ColorOS) 深度睡眠解锁误判
* **设备与环境**: **realme 大师探索版 (realme GT Master Exploration Edition, realme UI / ColorOS)**
* **根因路径**: 在经历长达 10 小时以上熄屏（如周五上午打卡后到周五 18:00）或跨周末超长休眠后，系统触发闹钟拉起 `WakeActivity`。在 `onCreate()` 中调用 `KeyguardManager.requestDismissKeyguard` 时，由于 realme UI 屏幕刚从深沉睡眠中亮起、Activity 窗口尚未完全绘制完成，系统异步回调 `onDismissCancelled()` / `onDismissError()`。
  旧代码会将 `keyguardDismissFailed` 标志置为 `true`，并在 2 秒后因判断该标志为 `true` 而直接销毁 Activity (`releaseLocksAndFinish()`)，导致无障碍打卡流在没有启动飞书前即被误杀中断。因为无障碍服务并没有被系统断连，所以无障碍不会报失效；且用户事后手动测试“模拟解锁”时由于屏幕早已点亮并获得焦点，测试必定成功。

### 🔴 RCA 4: 跨周末 54 小时超长 Doze 休眠导致 Monday 调度链断裂
* **根因路径**: 原有逻辑仅在每日 00:30 AM 的 `DailySetupReceiver` 中递归注册下一天 00:30 的闹钟。周末两天判定为 `RESTDAY` 时不下发任何打卡闹钟。从周五 18:10 到周一 00:30 经历了 54 小时无任何人机交互的深度冻结，realme UI 系统的夜间极度省电机制会将 00:30 的普通 `setExactAndAllowWhileIdle` 闹钟推迟或冻结，导致周一 00:30 的调度广播根本未被接收，周一早上的打卡闹钟未被下发。

---

## 3. 修复方案与防护建立 (Implementation & Verification)

### ✅ 1. 修复 Keyguard 解锁误判与兼容免密/滑动解锁 (WakeActivity.kt)
* 在 [WakeActivity.kt](file:///E:/autoDO/app/src/main/java/com/lark/autoclock/WakeActivity.kt) 中取消将 `requestDismissKeyguard` 的异步 `cancelled/error` 视作致命失败；对于免密/滑动锁屏 (`isKeyguardSecure == false`)，完全交给 `WindowManager` 原生 Flags (`FLAG_DISMISS_KEYGUARD` / `FLAG_SHOW_WHEN_LOCKED` / `FLAG_TURN_SCREEN_ON`) 自动解锁，仅当设有了 PIN/密码锁 (`isKeyguardSecure == true`) 才中断打卡。并在 2 秒 delay 处增加二次 `requestDismissKeyguard` 触发，保障 realme UI 唤醒成功率。

### ✅ 2. 调度闹钟升级为最高优先级 `setAlarmClock` (ClockScheduler.kt)
* 将 [ClockScheduler.kt](file:///E:/autoDO/app/src/main/java/com/lark/autoclock/scheduler/ClockScheduler.kt) 中的闹钟下发机制升级为系统原生 `AlarmManager.setAlarmClock(AlarmClockInfo)` API。`setAlarmClock` 在 Android 及 realme UI / ColorOS 中享有最高的硬件唤醒与 Doze 穿透优先级，不受后台应用冻结影响。

### ✅ 3. 增加周末/长休防护 (scheduleNextWorkdayClockInInAdvance)
* 在 [ClockScheduler.kt](file:///E:/autoDO/app/src/main/java/com/lark/autoclock/scheduler/ClockScheduler.kt) 中新增 `scheduleNextWorkdayClockInInAdvance()` 逻辑。当 `DailySetupReceiver` 在休息日/节假日（如周六、周日）触发时，会自动预先计算并下发下一个未来工作日（如周一）的上班打卡保底闹钟。即使周末 54 小时过程中 00:30 调度因系统冻结被延迟，周一早上的打卡闹钟早已提前保底生效！

### ✅ 4. 增加 `canScheduleExactAlarms` 权限自动检测与弹窗引导
* 在 [MainActivity.kt](file:///E:/autoDO/app/src/main/java/com/lark/autoclock/MainActivity.kt) 的 `onResume()` 中增加了针对 `canScheduleExactAlarms()` 的检测，若未授权则引导跳转至系统精确闹钟权限设置页。

### ✅ 5. 增加随机打卡时间段保存的前置有效性校验
* 在 [MainActivity.kt](file:///E:/autoDO/app/src/main/java/com/lark/autoclock/MainActivity.kt) 的 `showTimeConfigDialog()` 中增加了 `isTimeValid` 校验，强制要求 `结束时间 > 开始时间`，否则阻止保存并提示。

### ✅ 6. 建立自动化单元测试防线
* 完善 [FridayMondayCriticalTest.kt](file:///E:/autoDO/app/src/test/java/com/lark/autoclock/scheduler/FridayMondayCriticalTest.kt) 单元测试用例，保障打卡时间区间、Doze 延迟决策及长休保底逻辑的稳定运行。

---

## 4. 结论 (Status: VERIFIED & FIXED)
经过本次对 realme 大师探索版 (realme UI / ColorOS) 运行环境的专项排查与深度优化，彻底解决了周五下班卡与周一上班卡的锁屏解锁误判及跨周末休眠冻结难题。现已形成底层硬件闹钟 + Keyguard 容错 + 周末保底双重锁的三重高鲁棒性防护体系。
