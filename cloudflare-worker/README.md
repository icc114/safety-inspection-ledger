# Cloudflare 同步网关

本目录是 Android 端 Cloudflare provider 所要求的实际同步协议实现。普通网页、仅返回 HTTP 204 的 Worker 或 R2 公共地址并不是同步服务，APP 会拒绝把“地址可达”误报为“同步可用”。

部署步骤：

1. 创建私有 R2 bucket。
2. 复制 `wrangler.toml.example` 为 `wrangler.toml`，填写 bucket 名称。
3. 部署 Worker，把 Worker 的 HTTPS 根地址填入 APP 的“服务地址”。
4. APP 中选择 Cloudflare；每台设备填写相同的“同步空间名称”和“同步密码”。首台设备会自动建立空间授权，之后的设备使用相同资料加入。原密码不会上传，客户端只发送限定到空间的 SHA-256 配对凭据。
5. 如需关闭自动建空间，可设置 Worker 变量 `DISABLE_SELF_PROVISION=true`，并使用 `wrangler secret put SYNC_TOKEN`；也可以分别设置 `SYNC_USERNAME` 与 `SYNC_PASSWORD`，然后在 APP 高级认证中填写。

APP 的“测试连接”会真实执行 `PROPFIND → MKCOL → PUT → GET → DELETE`，五步均成功才允许保存。旧版返回 `{\"error\":\"需要设备授权\"}` 的私有 Worker 不会自动获得兼容能力，必须重新部署本目录代码。
