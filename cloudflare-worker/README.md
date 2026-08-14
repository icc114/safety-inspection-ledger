# Cloudflare 同步网关

本目录是 Android 端 Cloudflare provider 所要求的实际同步协议实现。普通网页、仅返回 HTTP 204 的 Worker 或 R2 公共地址并不是同步服务，APP 会拒绝把“地址可达”误报为“同步可用”。

部署步骤：

1. 创建私有 R2 bucket。
2. 复制 `wrangler.toml.example` 为 `wrangler.toml`，填写 bucket 名称。
3. 使用 `wrangler secret put SYNC_TOKEN` 设置 Token；也可以分别设置 `SYNC_USERNAME` 与 `SYNC_PASSWORD`。
4. 部署 Worker，把 Worker 的 HTTPS 根地址填入 APP 的“服务地址”。
5. APP 中选择 Cloudflare；Token 或用户名/登录密码必须与 Worker secret 一致。
6. 每台设备填写相同的“同步空间名称”和“同步空间密码”。同步空间密码只在设备端用于 AES-256-GCM 加密，不发送给 Worker。

APP 的“测试连接”会真实执行 `PROPFIND → MKCOL → PUT → GET → DELETE`，五步均成功才允许保存。
