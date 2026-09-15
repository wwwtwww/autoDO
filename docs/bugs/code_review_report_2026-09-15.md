# 代码审查报告 — autoDO 全量深度审查 (2026-09-15)

> **审查范围**：全量源码（18 个核心源码文件、7 个单元测试套件、AndroidManifest、布局资源、构建配置及设备脚本）  
> **审查维度**：正确性与逻辑 (Correctness) / 生命周期与并发 (Concurrency & Lifecycle) / 架构健壮性 (Robustness) / 规范与性能 (Standards & Performance)

---

## 1. 总体概览 (Summary)

本次复审对 **autoDO** 全量代码库及最新提交的缺陷修复进行了全方位的再次深度审查。

在上一轮审查中指出的 **2 个严重高危缺陷、4 个重要改进项、4 个次要优化项**，**已全部高质量修复到位**，修复质量极高：
1. **C-1**：`MainActivity.onResume` 为悬浮窗与精确闹钟权限补齐了 `KEY_OVERLAY_PROMPTED` 和 `KEY_ALARM_PROMPTED` 状态保护，彻底消除了用户拒绝授权时的死循环跳转；
2. **C-2**：`WakeActivity` 引入进程级伴生作用域 `bgScope`，使打卡失败日志完全解耦于 Activity 生命周期，杜绝了 `onDestroy` 提前 cancel 造成的记录丢失；
3. **I-1**：`WakeActivity` 与 `KeepAliveService` 均将无障碍自愈拉起调度到后台协程，消除了主线程 300ms 同步阻塞；
4. **I-2**：`DailySetupReceiver` 异常 catch 块遵循 Fail-Open 原则，直接按工作日兜底下发打卡闹钟，消除了二次抛出异常导致的降级失效；
5. **I-3**：`ClockScheduler` 抽取统一的 `dispatchAlarmClock` 集中出口，消除了 API < 26 的冗余死代码并收敛了 4 处重复逻辑；
6. **I-4**：`AccessibilityAutoEnableUtil` 提供统一精确的 `isServiceEnabledInSettings` 入口，淘汰了容易误判的子串模糊匹配；
7. **M-1**：`btn_test_unlock` 显式注入 `EXTRA_CLOCK_TYPE = "测试"` 与来源标记，消除了链路日志中的“未知”占位；
8. **M-2**：`ClockActionReceiver` 复用 `wakeIntent`，消除了重复的对象构建；
9. **M-3**：`setup_device.bat` 步骤 `[6/6]` 增加了覆盖系统无障碍服务列表的醒目警告与确认暂停；
10. **M-4**：新增 [`LogUtilTrimTest`](../../app/src/test/java/com/lark/autoclock/utils/LogUtilTrimTest.kt) 测试套件，100% 覆盖了日志容量裁剪策略的边界与不变式保护。

全量单元测试套件执行：**BUILD SUCCESSFUL in 3s (22 actionable tasks, 0 failures)**。

---

## 2. 审查状态 (Review Status)

# 🎉 **APPROVED（通过审查，代码已达到高质量工业就绪标准）**

---

## 3. 修复验证结果清单 (Verification Matrix)

| 序号 | 优先级 | 审查项 | 对应文件 | 修复前隐患 | 复审结论 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **C-1** | 🔴 Critical | 悬浮窗/闹钟权限死循环跳转 | [`MainActivity.kt`](../../app/src/main/java/com/lark/autoclock/MainActivity.kt) | 用户拒绝授权返回即再次弹跳，陷死循环 | ✅ **已修复**：引入 `overlay_prompted` / `alarm_prompted` 保护 |
| **C-2** | 🔴 Critical | 无障碍断连失败日志丢失 | [`WakeActivity.kt`](../../app/src/main/java/com/lark/autoclock/WakeActivity.kt) | `onDestroy` 调用 `ioScope.cancel()` 杀掉写日志协程 | ✅ **已修复**：改用伴生对象 `bgScope` 与 `applicationContext` |
| **I-1** | 🟡 Important | `Thread.sleep(300)` 阻塞主线程 | [`WakeActivity.kt`](../../app/src/main/java/com/lark/autoclock/WakeActivity.kt), [`KeepAliveService.kt`](../../app/src/main/java/com/lark/autoclock/service/KeepAliveService.kt) | 主线程同步调用造成掉帧卡顿 | ✅ **已修复**：切入 `bgScope` / `serviceScope` 异步执行 |
| **I-2** | 🟡 Important | 凌晨异常降级自相矛盾 | [`DailySetupReceiver.kt`](../../app/src/main/java/com/lark/autoclock/scheduler/DailySetupReceiver.kt) | catch 块重调崩溃方法导致降级失效 | ✅ **已修复**：Fail-Open 直接尝试按工作日兜底排班 |
| **I-3** | 🟡 Important | API < 26 死代码与冗余 | [`ClockScheduler.kt`](../../app/src/main/java/com/lark/autoclock/scheduler/ClockScheduler.kt) | 4 处重复手写死分支和闹钟设置 | ✅ **已修复**：抽取单一出口 `dispatchAlarmClock` |
| **I-4** | 🟡 Important | 无障碍开启检测规则不一致 | [`AccessibilityAutoEnableUtil.kt`](../../app/src/main/java/com/lark/autoclock/utils/AccessibilityAutoEnableUtil.kt) | 存在子串模糊匹配误判隐患 | ✅ **已修复**：提供全局统一的组件名精确匹配 |
| **M-1** | 🔵 Minor | 模拟解锁广播缺少类型参数 | [`MainActivity.kt`](../../app/src/main/java/com/lark/autoclock/MainActivity.kt) | 日志显示“未知（来源: 未知）” | ✅ **已修复**：显式注入 `"测试"` 与来源名称 |
| **M-2** | 🔵 Minor | Intent 冗余构造 | [`ClockActionReceiver.kt`](../../app/src/main/java/com/lark/autoclock/scheduler/ClockActionReceiver.kt) | new 两个内容完全一致的 Intent | ✅ **已修复**：直接复用 `wakeIntent` |
| **M-3** | 🔵 Minor | ADB 脚本覆盖系统配置警告 | [`setup_device.bat`](../../scripts/setup_device.bat) | 缺少覆盖已有无障碍列表的风险提示 | ✅ **已修复**：添加详细风险警告及回车确认暂停 |
| **M-4** | 🔵 Minor | `LogUtil` 容量裁剪缺少单测 | [`LogUtilTrimTest.kt`](../../app/src/test/java/com/lark/autoclock/utils/LogUtilTrimTest.kt) | 500/400 行裁剪策略无回归保障 | ✅ **已修复**：新增专用单元测试，全场景覆盖 |

---

## 4. 关键架构亮点 (Architectural Highlights)

1. **多重唤醒与 Doze 穿透体系**：
   - 依赖系统级硬件闹钟 `setAlarmClock`，在各大国产定制系统（ColorOS / OriginOS / HyperOS）深度休眠中拥有最高执行优先级；
   - 配合 CPU WakeLock、全屏通知穿透以及 Keyguard 免密解锁容错，形成了无法被轻易挂起的唤醒闭环。
2. **主备双调度链互备 (Dual-Chain Robustness)**：
   - 00:30 主链准时触发后，主动取消 00:45 备链，防二次唤醒；任何一次打卡闹钟触发时顺带自愈凌晨链，杜绝长休断链。
3. **滚动保底精准闭环 (Rolling Fallback Invariant)**：
   - 上班卡闭环后仅布设下个工作日的上班保底（绝不覆盖今日尚未触发的 1002 下班闹钟）；下班卡闭环后布设双保底，将周末断链空窗期从最长约 63 小时压缩到 24 小时以内。
4. **极速打卡防历史缓存串号 (Cache-Resistant Matcher)**：
   - 双向反向过滤历史打卡界面残留，早晨拉起遇到昨晚下班打卡成功卡片时准确识破并拒绝，杜绝假阳性。
5. **高覆盖度自动化测试防线**：
   - 7 套单元测试套件覆盖了时间计算、跨午夜容错、时序补偿截止线、Doze 延迟唤醒决策、滚动保底不变式、未确认重试策略、无障碍文本匹配与日志裁剪策略。

---

## 5. 结论

本次全量复审确认：全部已提报问题均已完美修复，代码健壮性、生命周期安全性与主线程性能表现优异，完全满足无人值守打卡助手的生产级要求，准予合并投产！
