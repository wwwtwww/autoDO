# Code Review Report — LarkAutoClock (第三轮补充审查)

## Summary
本项目（LarkAutoClock 飞书自动打卡辅助应用）经过前两轮的深度优化和 Bug 修复，所有致命阻碍性问题（包含 `DailySetupReceiver` 裸线程可能被杀、`WakeActivity` WakeLock 泄漏、`BootReceiver` 缺失导致重启闹钟丢失、`HolidayHelper` 连接资源未关闭等）均已得到完美修复。

但在针对 ColorOS / 某些国产 ROM 锁屏状态下无法成功唤醒和亮屏的调试中，本轮深层分析发现了一个**核心权限引导缺失问题**（涉及悬浮窗权限 `SYSTEM_ALERT_WINDOW`），这会导致后台启动 `WakeActivity` 被系统拦截。

## Status: CHANGES REQUESTED

---

## Key Findings

### 🔴 Critical Issues

#### 1. 声明了悬浮窗权限但未在 App 启动时引导用户授权
- **文件**: [MainActivity.kt](file:///E:/autoDO/app/src/main/java/com/lark/autoclock/MainActivity.kt) / [AndroidManifest.xml](file:///E:/autoDO/app/src/main/AndroidManifest.xml)
- **问题**: 在 `AndroidManifest.xml` 中声明了 `android.permission.SYSTEM_ALERT_WINDOW` 权限，但 `MainActivity.kt` 并没有检测并引导用户跳转到设置页开启“允许显示在其他应用上”（悬浮窗）权限。
- **影响**: 在 Android 10+ 系统中，出于安全限制，系统默认禁止后台 Service 或 Receiver 直接启动 Activity（即 `ClockActionReceiver` 中的 `context.startActivity(directIntent)` 会被系统直接拦截并报错）。只有当应用被授予“悬浮窗”权限（`SYSTEM_ALERT_WINDOW`）或具有前台权限时，才允许从后台拉起 Activity。这是导致用户反馈“测试亮屏与滑动解锁不成功”的最核心诱因。
- **修复建议**:
  在 `MainActivity` 中加入悬浮窗权限的检测与引导逻辑：
  ```kotlin
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (!Settings.canDrawOverlays(this)) {
          Toast.makeText(this, "请开启悬浮窗权限，否则锁屏打卡将无法唤醒屏幕！", Toast.LENGTH_LONG).show()
          val intent = Intent(
              Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
              Uri.parse("package:$packageName")
          )
          startActivity(intent)
      }
  }
  ```

---

### 🟡 Important Improvements

#### 1. 无障碍服务未过滤监听的包名
- **文件**: [accessibility_service_config.xml](file:///E:/autoDO/app/src/main/res/xml/accessibility_service_config.xml)
- **问题**: 无障碍配置文件中未限制 `android:packageNames`。虽然由于需要在系统 Launcher 桌面寻找并点击“假勤”图标，不能把包名写死为仅飞书，但当前配置会导致应用接收系统中**所有**应用（包括微信、支付宝、系统设置等）的所有窗口状态变更和内容变更事件。这在后台会频繁触发 `onAccessibilityEvent`，消耗不必要的 CPU 和电量。
- **修复建议**: 建议在 `AutoClockAccessibilityService.kt` 的 `onAccessibilityEvent` 顶部增加针对包名的运行时动态过滤白名单（例如，只处理飞书包名和常见系统桌面包名），其余应用的事件直接 return。

#### 2. 上下午打卡全屏通知使用相同 Notification ID
- **文件**: [ClockActionReceiver.kt](file:///E:/autoDO/app/src/main/java/com/lark/autoclock/scheduler/ClockActionReceiver.kt#L63)
- **问题**: 上午和下午打卡触发全屏通知时，均硬编码使用了相同的 `1001` 作为 Notification ID。这会导致下午的通知直接覆盖上午尚未被用户手动清除或被系统自动归档的通知，不利于后期根据系统通知栏进行打卡记录历史回溯。
- **修复建议**: 建议将 ID 修改为使用唯一值（例如时间戳）或为上下午打卡分别定义不同常量的 ID（例如 `1001` 和 `1002`）。

---

### 🔵 Minor Suggestions & Nitpicks

#### 1. `UnlockHelper` 成为完全未被引用的“死代码类”
- **文件**: [UnlockHelper.kt](file:///E:/autoDO/app/src/main/java/com/lark/autoclock/utils/UnlockHelper.kt)
- **问题**: 在第二轮修复了手势解锁逻辑后，`UnlockHelper` 类中仅保留了 `isDeviceLocked(context)` 一个方法，但经过全局搜索确认，整个项目（包括 `MainActivity.kt`、`WakeActivity.kt` 等）**已没有任何一处代码引用该方法或 `UnlockHelper` 类**。
- **修复建议**: 既然当前唤醒和解锁完全依赖 `WakeActivity` 的 `requestDismissKeyguard` 以及 WindowManager 标志，建议直接删除 `UnlockHelper.kt` 整个文件，以保持项目绝对纯净。

#### 2. 魔法数字建议常量化
- **文件**: 多个文件
- **问题**: 代码中存在多处直接硬编码的时间延迟，如无障碍服务中的 `1500`ms（等待桌面加载）、`5000`ms（打卡成功后等待返回桌面）、`3000`ms（`WakeActivity` 自动销毁延迟）等。
- **修复建议**: 建议将这些数字统一提取到各自类的 `companion object` 中作为具名常量，提高代码可读性与集中维护效率。

---

## Detailed Feedback

| File | Line | Issue | Suggestion |
| :--- | :--- | :--- | :--- |
| [MainActivity.kt](file:///E:/autoDO/app/src/main/java/com/lark/autoclock/MainActivity.kt) | L84 附近 | 未检测和引导用户授权 `SYSTEM_ALERT_WINDOW` | 在 `onCreate` 或 `onResume` 中增加 `Settings.canDrawOverlays` 检查并跳转引导。 |
| [UnlockHelper.kt](file:///E:/autoDO/app/src/main/java/com/lark/autoclock/utils/UnlockHelper.kt) | 全文件 | 类及其方法已无任何调用者，属于多余死代码 | 直接删除 `UnlockHelper.kt` 文件。 |
| [ClockActionReceiver.kt](file:///E:/autoDO/app/src/main/java/com/lark/autoclock/scheduler/ClockActionReceiver.kt) | L63 | 硬编码的通知 ID `1001` 可能导致通知覆盖 | 替换为基于时间戳或上下午区分的唯一 ID。 |
| [accessibility_service_config.xml](file:///E:/autoDO/app/src/main/res/xml/accessibility_service_config.xml) | 全文件 | 未配置包名过滤导致接收全局所有 App 的事件 | 在 `AutoClockAccessibilityService.kt` 的 `onAccessibilityEvent` 中增加运行时包名白名单拦截。 |

---

## Questions & Clarifications
1. **悬浮窗权限在 ColorOS 上的现状**: ColorOS 对后台启动 Activity 的限制极其严格，即使授予了悬浮窗权限，有时仍需要在系统设置的“自启动管理”或“后台弹出界面”中手动开启。我们在代码中引导开启悬浮窗权限是第一步，也是最重要的一步。

---

## Positive Highlights
- **优秀的异常兜底**: `HolidayHelper` 中针对网络 API 异常设计了完备的 `try-catch` 降级逻辑，保证了离线状态下打卡闹钟的最低限度可用。
- **开机恢复自启动**: 新增的 `BootReceiver` 在系统重启后可以正确加载之前被重置的所有任务。
