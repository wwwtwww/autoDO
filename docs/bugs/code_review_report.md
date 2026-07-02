# Code Review Report — LarkAutoClock (第三轮)

## Summary
本项目（LarkAutoClock 飞书自动打卡辅助应用）经过前两轮的深度优化和 Bug 修复，所有致命阻碍性问题（包含 `DailySetupReceiver` 裸线程可能被杀、`WakeActivity` WakeLock 泄漏、`BootReceiver` 缺失导致重启闹钟丢失、`HolidayHelper` 连接资源未关闭等）均已得到完美修复。

当前代码整体逻辑严密、防风控与锁屏唤醒架构设计合理，已具备极高的稳定性与安全性。本轮审查仅发现少量非阻塞性的架构优化建议与死代码清理项。

## Status: APPROVED (with minor suggestions)

## Key Findings

### 🔴 Critical Issues
**无**。当前代码不存在任何阻碍程序运行、导致崩溃或资源泄漏的严重逻辑缺陷。

### 🟡 Important Improvements
#### 1. 无障碍服务未过滤监听的包名
- **文件**: [accessibility_service_config.xml](file:///E:/autoDO/app/src/main/res/xml/accessibility_service_config.xml)
- **问题**: 无障碍配置文件中未声明 `android:packageNames`。虽然由于需要在系统 Launcher 桌面寻找并点击“假勤”图标，不能把包名写死为仅飞书，但当前配置会导致应用接收系统中**所有**应用（包括微信、支付宝、系统设置等）的所有窗口状态变更和内容变更事件。这在后台会频繁触发 `onAccessibilityEvent`，消耗不必要的 CPU 和电量。
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
- **修复建议**: 既然当前唤醒和解锁完全依赖 `WakeActivity` 的 `requestDismissKeyguard` 以及 WindowManager 标志，建议直接删除 [UnlockHelper.kt](file:///E:/autoDO/app/src/main/java/com/lark/autoclock/utils/UnlockHelper.kt) 整个文件，以保持项目绝对纯净。

#### 2. 魔法数字建议常量化
- **文件**: 多个文件
- **问题**: 代码中存在多处直接硬编码的时间延迟，如无障碍服务中的 `1500`ms（等待桌面加载）、`5000`ms（打卡成功后等待返回桌面）、`3000`ms（`WakeActivity` 自动销毁延迟）等。
- **修复建议**: 建议将这些数字统一提取到各自类的 `companion object` 中作为具名常量，提高代码可读性与集中维护效率。

---

## Detailed Feedback

| File | Line | Issue | Suggestion |
| :--- | :--- | :--- | :--- |
| [UnlockHelper.kt](file:///E:/autoDO/app/src/main/java/com/lark/autoclock/utils/UnlockHelper.kt) | 全文件 | 类及其方法已无任何调用者，属于多余死代码 | 直接删除 `UnlockHelper.kt` 文件。 |
| [ClockActionReceiver.kt](file:///E:/autoDO/app/src/main/java/com/lark/autoclock/scheduler/ClockActionReceiver.kt) | L63 | 硬编码的通知 ID `1001` 可能导致通知覆盖 | 替换为基于时间戳或上下午区分的唯一 ID。 |
| [accessibility_service_config.xml](file:///E:/autoDO/app/src/main/res/xml/accessibility_service_config.xml) | 全文件 | 未配置包名过滤导致接收全局所有 App 的事件 | 在 `AutoClockAccessibilityService.kt` 的 `onAccessibilityEvent` 中增加运行时包名白名单拦截。 |

---

## Questions & Clarifications
- **问题**: 项目后续是否考虑加入本地打卡历史记录的持久化展示（如 SQLite / Room / SharedPreferences）？如果考虑，则上面的“通知 ID 覆盖”问题可以忽略；如果完全依赖系统通知栏留存打卡凭证，则强烈建议修复通知 ID。

---

## Positive Highlights
- **完美的 Bug 修复闭环**: 开发者极其精准地贯彻了前两轮的修复策略，用 `goAsync()` 解决了后台 Receiver 执行长耗时任务的系统机制冲突，用 `BootReceiver` 填补了开机自动重调度的空白。
- **优秀的异常兜底**: `HolidayHelper` 中针对网络 API 异常设计了完备的 `try-catch` 降级逻辑（退回到本地根据周六日判断），保证了离线状态下打卡闹钟的最低限度可用。
