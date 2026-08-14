# Cloudflare 同步网关

本目录是 Android 端 Cloudflare provider 所要求的实际同步协议实现。普通网页、仅返回 HTTP 204 的 Worker、旧版 D1 `env.DB` Worker 或 R2 公共地址都不是当前 APK 的同步服务，APP 会拒绝把“地址可达”误报为“同步可用”。

当前 Android 1.2.6 使用 WebDAV 兼容协议，并要求 Worker 绑定一个私有 R2 bucket，绑定名固定为 `SAFETY_LEDGER_BUCKET`。

部署步骤：

1. 在 Cloudflare 创建私有 R2 bucket，例如 `safety-ledger-sync`。
2. 复制 `wrangler.toml.example` 为 `wrangler.toml`，填写实际 bucket 名称。
3. 确认配置中存在 `[[r2_buckets]]`，且 `binding = "SAFETY_LEDGER_BUCKET"`。
4. 执行 `wrangler deploy` 部署 Worker。
5. 浏览器访问 `你的Worker地址/health`。正常应返回 `ok: true`、`storage: R2`、`binding: SAFETY_LEDGER_BUCKET`。如果返回 503 或提示未绑定 R2，说明云端部署还不完整。
6. 把 Worker 的 HTTPS 根地址填入 APP 的“服务地址”，服务提供商选择 Cloudflare。
7. 每台设备填写相同的“同步空间名称”和“同步密码”。首台设备会自动建立空间授权，之后的设备使用相同资料加入。原密码不会上传，客户端只发送限定到空间的 SHA-256 配对凭据。
8. 在 APP 点击“测试连接”。APP 会真实执行 `PROPFIND → MKCOL → PUT → GET → DELETE`，五步均成功才视为同步可用。

如需关闭自动建空间，可设置 Worker 变量 `DISABLE_SELF_PROVISION=true`，并使用 `wrangler secret put SYNC_TOKEN`；也可以分别设置 `SYNC_USERNAME` 与 `SYNC_PASSWORD`，然后在 APP 高级认证中填写。

如果你以前部署时日志显示的是 `env.DB (D1 Database)`，那是旧版云端，不适用于当前 1.2.6。不要只改 APP 地址；需要使用本目录 Worker 重新部署，并把存储切换为上述 R2 绑定。
