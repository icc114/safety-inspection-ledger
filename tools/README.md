# 安全检查台账 PC 数据迁移工具

此工具用于识别 Android“导出数据”产生的 `.safetydata` 文件。它不是完整 Windows 图形客户端，但可以真实完成数据包查看、完整性校验、解包和重新打包。

Windows 需要安装 JDK 17 或更高版本，然后在本目录打开命令提示符：

```bat
SafetyDataTool.bat info 安全检查台账.safetydata
SafetyDataTool.bat verify 安全检查台账.safetydata
SafetyDataTool.bat extract 安全检查台账.safetydata 解包目录
SafetyDataTool.bat pack 解包目录 重新打包.safetydata
```

解包后：

- `database.sqlite` 是完整业务数据库，可用 SQLite 工具读取。
- `business_media` 包含原始业务照片和三方签名。
- `manifest.properties` 包含格式版本、数据库 schema 版本、创建时间和 SHA-256。

重新打包会更新数据库 SHA-256，生成的 `.safetydata` 可以通过 Android 设置页“导入数据”重新导入。
