# `.safetydata` 跨端数据包规范

本规范描述 Android 1.2.4 起“导出数据”产生的便携数据包。目标是让 Android、未来 Windows 客户端及独立迁移工具使用同一份数据，而不是把 PDF 当作数据库备份。

## 容器结构

文件按以下顺序组成：

| 字段 | 长度 | 说明 |
|---|---:|---|
| magic | 12 bytes | ASCII `SAFETYLOCAL2` |
| containerVersion | 1 byte | 当前为 `1` |
| PBKDF2 salt | 16 bytes | 每次导出随机生成 |
| AES-GCM IV | 12 bytes | 每次导出随机生成 |
| ciphertext + tag | 其余 | 加密后的 ZIP，GCM tag 为 128 bit |

密钥派生使用 PBKDF2-HMAC-SHA256、310,000 次、256 bit。便携迁移包不要求用户输入密码，使用跨端格式密钥 `safety-ledger-portable-backup-v2`。因此 AES-GCM 在此主要提供损坏和篡改检测，不应把便携包当作只有用户密码才能打开的保密容器。云同步快照使用另一魔数及用户同步密码，不属于本规范。

## 加密 ZIP 内容

```text
manifest.properties
database.sqlite
business_media/
  <inspection UUID>/
    <media UUID>.jpg
    signature-INSPECTOR1.png
    signature-INSPECTOR2.png
    signature-INSPECTEE.png
```

`manifest.properties` 至少包含：

- `format=safetydata`
- `formatVersion=1`
- `schemaVersion=<Android LedgerDatabase.VERSION>`
- `createdAt=<Unix epoch milliseconds>`
- `databaseSha256=<database.sqlite SHA-256>`

数据库主键为 UUID。跨设备合并以 UUID、`updated_at`、`revision` 与 tombstone 为基础；模板快照、检查项目、照片、签名、整改及复查资料均保存在数据库和 `business_media` 中。

## PC 校验、读取与重新打包

仓库提供无第三方依赖的 JDK 17 工具：

```powershell
java tools/SafetyDataTool.java info 安全检查台账.safetydata
java tools/SafetyDataTool.java verify 安全检查台账.safetydata
java tools/SafetyDataTool.java extract 安全检查台账.safetydata 解包目录
java tools/SafetyDataTool.java pack 解包目录 重新打包.safetydata
```

`extract` 后 PC 可使用 SQLite 工具读取 `database.sqlite`，照片和签名保持原文件。`pack` 会重新计算数据库 SHA-256，生成可被 Android“导入数据”识别的数据包。Android 导入仍会验证 schema migration 元数据，错误数据库不会被接受。

## 兼容策略

- Android 新版先检测 `SAFETYLOCAL2`，无需询问密码。
- 旧版 `SAFETYDATA` 密码备份仍可通过兼容入口导入。
- PDF、普通 ZIP、截断文件、AES-GCM 校验失败文件和数据库 SHA-256 不一致文件均拒绝导入。
- 完整恢复到新设备后，会清除只属于旧设备 Android Keystore 的云端凭据和设备 ID；业务数据与本机永久删除密码校验值保留。
