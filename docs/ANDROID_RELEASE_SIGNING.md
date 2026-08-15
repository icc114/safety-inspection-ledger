# Android 正式签名基线

从 `1.2.9` 正式版开始，`cn.safetyledger.app` 必须始终使用同一把 Android 正式签名密钥，后续版本只能增加 `versionCode` 后使用该密钥构建，才能在手机上直接覆盖升级。

## 固定证书

- Alias: `safety-ledger-release`
- Subject: `CN=Safety Inspection Ledger, OU=Android, O=Safety Ledger, L=Beijing, ST=Beijing, C=CN`
- SHA-256: `0E:D4:37:B3:AB:D1:2F:BC:62:88:91:C8:1C:FF:15:06:72:D0:AD:A7:8C:C6:6D:11:72:08:59:EC:C9:F5:28:15`

私钥 **不得提交到仓库**。仓库只记录公开证书指纹，用于阻止将来误用另一把密钥。

## GitHub Actions

`.github/workflows/android-release.yml` 使用以下 Actions Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

工作流在构建前会检查 JKS 证书指纹，在构建后再次检查 APK 签名证书；任一指纹不一致都会终止发布。

`tools/setup_android_signing.ps1` 只负责把已经存在的永久签名备份上传到 GitHub Secrets，**不会再创建新密钥**。

## 升级规则

1. 包名保持 `cn.safetyledger.app`。
2. 每次发布必须增加 `versionCode`。
3. 正式发布必须使用上述固定证书。
4. Debug APK 仅用于测试，不应作为长期正式版本安装。
5. 永久签名 JKS 与恢复信息至少离线备份两份；丢失私钥后无法继续覆盖升级现有正式安装。
