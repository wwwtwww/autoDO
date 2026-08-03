# autoDO 无障碍保活 ADB 授权指南

为了解决系统后台自动关闭无障碍服务的问题，autoDO 需要系统级别的安全修改权限 (`WRITE_SECURE_SETTINGS`)，以便在无障碍掉线时能够**自动将其重启**。

这个权限属于系统核心安全权限，无法通过手机直接开启，必须通过电脑终端进行授权。只需要配置一次，App 卸载前永久有效。

---

## 📱 第一阶段：手机端准备（最重要的一步）

如果您在执行命令时遇到 `SecurityException` 或提示失败，**99% 是因为这一步没有设置好**。

1. **开启开发者选项**
   - 进入手机“设置” -> “关于手机”。
   - 连续快速点击“版本号”或者“OS版本”7次，直到提示“您已处于开发者模式”。

2. **开启 USB 调试与安全设置 (⚠️ 核心步骤)**
   - 进入“设置” -> “系统与更新” -> “开发人员选项”（或者在设置顶部直接搜索“开发者选项”）。
   - 打开 **“USB 调试”** 开关。
   - **如果您使用的是以下国产手机，必须打开对应的“安全调试”开关，否则 ADB 会拒绝授权**：
     - **小米 / Redmi (MIUI / HyperOS)**：找到并打开 **“USB调试(安全设置)”**（注意：开启此项可能需要插入 SIM 卡并登录小米账号）。
     - **OPPO / 一加 / Realme (ColorOS)**：找到并打开 **“禁止权限监控”**（如果不打开，授权命令会直接报错）。
     - **vivo / iQOO (OriginOS)**：找到并打开 **“USB安全调试”**。
     - **魅族 (Flyme)**：可能需要关闭系统管家里的 USB 安装拦截等安全防护。

---

## 💻 第二阶段：电脑端执行命令

### 1. 准备 ADB 环境
如果您要换一台新电脑进行操作，如果该电脑没有安装 Android Studio，您需要先准备一个简易的 ADB 环境：
- 前往 [Google 官方提供的 SDK Platform-Tools 下载页面](https://developer.android.com/studio/releases/platform-tools) 下载对应系统的压缩包。
- 将压缩包解压到电脑的任意位置（例如 `D:\platform-tools`）。
- 打开解压后的文件夹，在文件夹的地址栏输入 `cmd` 并按回车，即可打开终端并自带 ADB 环境。

### 2. 连接设备
- 用数据线将手机连接到电脑。
- 此时手机屏幕上可能会弹出一个确认框：“**是否允许 USB 调试？**”，勾选“始终允许来自此计算机的调试”，然后点击**确定**。
- 在电脑终端中输入：
  ```bash
  adb devices
  ```
  如果看到一行类似 `123456789abc device` 的输出，说明连接成功。如果是 `unauthorized`，请去手机屏幕上点允许。

### 3. 执行授权命令
复制下方这行完整的命令，粘贴到电脑的终端（CMD / PowerShell / Terminal）中，并按回车运行：

```bash
adb shell pm grant com.lark.autoclock android.permission.WRITE_SECURE_SETTINGS
```

### ✅ 4. 验证是否成功
- 如果按完回车后，**终端什么都没输出（直接跳到了下一行）**，就说明**授权成功**了！
- 此时您可以拔下数据线，关闭手机上的“开发者选项”了（或者为了安全起见，仅关闭“USB调试”）。

---

## ❓ 常见报错排除 (Troubleshooting)

| 报错信息 | 解决方案 |
| :--- | :--- |
| `'adb' 不是内部或外部命令...` | 电脑没有 ADB 环境，请参考上面的步骤下载 `Platform-Tools` 并进入其目录执行。 |
| `SecurityException: grantRuntimePermission...` | 手机拦截了权限授予。**必须**去开发者选项中打开 **“USB调试(安全设置)”** 或 **“禁止权限监控”**。 |
| `error: no devices/emulators found` | 电脑没识别到手机。请检查数据线是否支持数据传输、手机是否开启了 USB 调试、是否安装了驱动。 |
| `error: device offline` 或 `unauthorized` | 手机上没有点“允许 USB 调试”。请拔插数据线，重新点“允许”。 |
| `Exception occurred while executing: java.lang.IllegalArgumentException: Unknown package: com.lark.autoclock` | 手机上还没有安装 autoDO 应用程序，请先在手机上安装好应用再执行授权。 |
