# 1.2.8 云同步入口整理

当前实际可工作的同步架构只有两类：

1. **Cloudflare R2 网关**：安全检查台账专用 Worker，底层存储为私有 R2；APP 使用同步空间名称 + 同步密码进行自动设备配对。
2. **WebDAV / NAS**：统一覆盖标准 WebDAV、飞牛 NAS、群晖 WebDAV、Nextcloud 等。用户名/密码放在高级服务器认证中。

Google Drive、OneDrive 目前尚未完成官方 OAuth/API 接入，因此不应继续显示为可选服务商，避免误导。

兼容策略：数据库中历史 provider_type 的 `Cloudflare` 保持兼容；`WebDAV`、`飞牛 NAS / WebDAV`、`自定义 HTTP 服务器` 统一映射为 WebDAV 类。界面只显示协议架构，不再按厂商重复列项。

Cloudflare 的 `workers.dev` 地址应自动识别为 Cloudflare R2 网关，避免用户误选 WebDAV。测试 Cloudflare 时先检查 `/health`：新版仓库 Worker 应公开返回 `protocol=safety-ledger-webdav-v1`、`storage=R2`、`binding=SAFETY_LEDGER_BUCKET`。如果 `/health` 不存在或仍要求旧 Token，则直接提示“云端仍为旧 Worker，需要重新部署”，不再让用户从 HTTP 401 猜原因。
