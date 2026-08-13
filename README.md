# 安全检查台账 Android

独立的 Android 原生离线优先应用，包名 `cn.safetyledger.app`，版本 `1.0.0`。工程从空仓库创建，不包含 APK、DEX、WebView、旧 Manifest、旧数据库或旧软件代码。

## 已完成

- Kotlin + Jetpack Compose 原生工程及首页、记录列表、检查填报、模板管理、回收站业务入口。
- Room v1：模板、模板项目、检查记录、检查项目、媒体、设置、同步队列、tombstone；全部业务主键采用 UUID，导出 schema 为后续 migration 留基线。
- 离线持久化的数据模型；整改状态覆盖待整改、整改中、已整改完成、已完成。
- 原图纳入应用私有媒体体系；相机/相册所需权限和 FileProvider；水印服务支持日期、时间、地点及成功取得时的经纬度。
- 三方签名媒体类别和横竖屏兼容的 Activity 配置。
- A4 PDF 生成服务、按日期重置页码分组的基础实现。
- `.safetydata` 格式头、ZIP 容器、PBKDF2-HMAC-SHA256（210,000 次）派生和 AES-256-GCM 认证加密；导入验证拒绝 PDF/错误格式/错误密码。
- provider-neutral 云接口、同步队列、tombstone、冲突兼容时间戳、远端媒体键和约 180 天本地媒体保留候选策略。
- GitHub Actions（Java 17、Gradle 8.9、Android 构建、中文命名 APK artifact）。

## 尚未完成（不冒充实现）

当前提交建立了可编译的端到端工程与关键数据/安全/扩展边界，但以下生产功能仍需后续迭代：完整月历交互与所有筛选分页动作；模板项目完整编辑器；CameraX/系统相册及定位权限交互；签名画布与签名回显；检查项目逐项编辑及详情页；含跨页表格、全部照片和签名的完整正式 PDF 排版；备份合并/完整恢复的事务式写回和设备迁移界面；回收站永久删除密码界面及媒体擦除；各云 provider、后台双向同步、冲突副本、通知和旧媒体按需下载。

这些未完成项的数据结构均已预留，但在完成 UI、错误处理和自动化测试之前不应视作可投产功能。

## 构建

需要 JDK 17 与 Android SDK 35：

```shell
gradle :app:assembleDebug
```

CI 会将结果命名为 `安全检查台账-1.0.0.apk` 并上传为 artifact。
