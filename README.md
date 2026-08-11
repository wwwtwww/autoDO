# autoDO — 飞书物理备用机自动打卡

Android 自动打卡应用，通过无障碍服务实现飞书考勤自动打卡。

## 签名与覆盖安装

本项目将 `debug.keystore` 提交到仓库根目录，并在 `build.gradle.kts` 中显式配置 `signingConfig` 指向该文件，确保 CI 与本地构建签名 100% 一致。

### 首次安装

从 GitHub Actions 下载 CI 构建的 APK 后直接安装即可。

### 从旧版本迁移

若手机上已安装旧版本（此前由本地 Android Studio 默认签名构建），需执行一次性迁移：

```bash
# 1. 卸载旧版（签名不同，无法覆盖安装）
adb uninstall com.lark.autoclock

# 2. 安装 CI 构建的 APK
adb install app-debug.apk

# 3. 重新授予系统权限
adb shell pm grant com.lark.autoclock android.permission.WRITE_SECURE_SETTINGS
```

迁移后，所有 CI 构建的 APK 均可覆盖安装，权限不会丢失。

### 本地构建

`debug.keystore` 已入库，clone 后可直接构建。若该文件被手动删除，构建会在签名阶段 fail-fast 失败（而非静默生成新 keystore 导致签名不一致），这是有意为之的保护机制。
