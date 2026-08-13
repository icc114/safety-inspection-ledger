# 安全检查台账

安全检查台账是一套完整开源、离线优先的检查记录系统。仓库同时保存 Android 原生客户端、Windows 离线客户端和可自行部署的同步服务源码，避免只留下无法维护的 APK。

## 仓库结构

| 目录 | 内容 |
| --- | --- |
| `app/` | Kotlin + Jetpack Compose Android 原生客户端 |
| `desktop/` | Windows 离线客户端（本地 HTML/CSS/JavaScript，不依赖在线网页） |
| `server/` | Cloudflare Workers + D1、飞牛 NAS Docker 同步服务 |
| `.github/workflows/` | 自动测试、编译 APK 和发布构建产物 |

## 核心规则

- 检查、拍照、签名、整改、查看、PDF 导出和数据备份均可离线完成。
- 记录、模板、照片、签名和删除墓碑使用 UUID，方便多手机和电脑同步。
- 云端可更换；客户端先保存本机，联网后再同步。
- 支持 Cloudflare、飞牛 NAS/兼容 WebDAV、Google Drive 同步网关和自定义 HTTP 服务。
- 删除先进入回收站；永久删除需要密码和二次确认。
- 最近六个月保留完整可编辑数据；完成且超过六个月的记录可生成 PDF 归档并释放本机空间。
- 年度、季度及多选导出合并为一个 A4 PDF，每个检查日期独立计算页码。
- `.csinspect` 加密备份与普通 PDF 导出严格分离。

## Android 构建

要求 JDK 17 和 Android SDK 35：

```shell
gradle :app:assembleDebug
```

GitHub Actions 会执行真实构建并上传 APK。公开仓库不保存正式签名私钥；正式覆盖升级应把 keystore 和密码配置为仓库 Actions Secrets。

## Windows 使用

下载仓库后进入 `desktop`，双击 `打开安全检查台账.bat`。数据保存在当前 Windows 用户的浏览器本地存储中，可通过应用内备份迁移。

## 同步服务

进入 `server` 后查看：

- `免费云同步部署说明.txt`
- `飞牛NAS部署说明.txt`
- `wrangler.jsonc`
- `docker-compose.fnos.yml`

仓库中的云配置只包含占位值，不包含任何真实账号、令牌或密码。

## 开源许可

本项目以 GPL-3.0-or-later 发布。第三方字体和依赖继续遵循各自许可证。
