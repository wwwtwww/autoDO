# 代码审查报告（合并三视角）

- **审查日期**: 2026-08-04
- **审查范围**: commit `e9e98a6`（`.github/workflows/build.yml` —— CI 签名统一改造，支持覆盖安装）
- **审查视角**: completeness（完整性）/ correctness（正确性）/ impact（副作用影响）

---

## Critical Issues（必须修复）

无。

---

## Warnings（建议修复）

### 1. Secret 缺失时的兜底路径破坏了「跨 run 签名一致」的契约

**位置**: [build.yml#L36-L47](../../.github/workflows/build.yml#L36-L47)（impact 视角）

**Problem**：旧的 `actions/cache` 方案至少在首次生成后保持稳定；新的 else 分支在每次 run 都用 `keytool` 生成**全新的随机密钥对**，且不会写回缓存或 Secret。一旦 `DEBUG_KEYSTORE_BASE64` Secret 未配置/被误删，每次构建的 APK 签名都不同，相邻构建无法互相覆盖安装，且构建照常成功、无任何失败信号——比旧方案的失败模式更隐蔽。

**Fix**（推荐 fail-fast，强制配置 Secret）：

```bash
else
  echo "::error::未设置 DEBUG_KEYSTORE_BASE64 Secret，拒绝使用临时签名（会导致每次构建签名不一致）"
  exit 1
fi
```

> 注意：如果改为 fail-fast，则需保留当前生成+上传的引导逻辑仅用于首次 bootstrap（即 Secret 配置前的过渡期），bootstrap 完成后再切换为 fail-fast；或者保持现状但接受此风险（Secret 已完成配置，实际风险很低）。

---

## Suggestions（可考虑）

### 2. keystore 上传步骤应使用 `always()` 而非 `success()`

**位置**: [build.yml#L71-L77](../../.github/workflows/build.yml#L71-L77)（correctness 视角）

**Problem**：`if: success() && ...` 导致首次引导 run 如果单测或打包失败，keystore 不会上传，用户无法下载配置 Secret；下次 run 又生成全新 keystore，引导闭环在构建修好之前无法完成。而 keystore 生成发生在构建之前，其上传并不依赖构建成功。

**Fix**：

```yaml
if: always() && steps.keystore.outputs.need_upload == 'true'
```

### 3. `Debug-Keystore` artifact 含签名私钥，建议缩短保留期

**位置**: [build.yml#L72-L77](../../.github/workflows/build.yml#L72-L77)（impact 视角）

**Problem**：上传的是完整 keystore（含私钥，密码为公开的 `android`），按默认策略最长保留 90 天，扩大了签名材料暴露窗口。

**Fix**：

```yaml
with:
  name: Debug-Keystore
  path: ~/.android/debug.keystore
  retention-days: 3
```

配置完 Secret 后也可手动在 Actions 页面删除该 artifact。

---

## Summary of Changes

- 移除了不可靠的 `actions/cache` 缓存 debug.keystore 方案（cache 过期会静默换 key），改为从 GitHub Secret `DEBUG_KEYSTORE_BASE64` 注入固定签名。
- 新增 keytool 兜底生成逻辑，参数与 AGP 默认 debug 签名完全一致（alias `androiddebugkey`、storepass/keypass `android`），Gradle 会直接使用 `~/.android/debug.keystore`。
- 新增 `Debug-Keystore` artifact 条件上传步骤（`need_upload` 输出门控），用于首次 bootstrap 配置 Secret。
- **完整性视角 PASSED**：改动完整达成「覆盖安装保留权限」的原始需求；首次切换签名需卸载一次旧版属预期的一次性迁移。
- 三个视角共发现 1 个 Warning + 2 个 Suggestion，均集中在 Secret 缺失的兜底路径上；Secret 已完成配置，实际运行风险很低。
