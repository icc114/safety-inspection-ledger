from pathlib import Path

p=Path('app/src/main/java/cn/safetyledger/app/SettingsActivity.java')
t=p.read_text(encoding='utf-8')
old='''                if (!result.success() && "Cloudflare".equals(resolvedType)) {
                    String detail = result.message();
                    if (detail.contains("需要设备授权") || detail.contains("HTTP 401")) {
                        detail = "已使用同步空间名称和同步密码自动发起设备配对，但这个地址仍拒绝授权。它不是本版兼容网关，或仍使用旧私有授权协议。请重新部署仓库 cloudflare-worker；如果云端另外生成了设备 Token，也可在高级认证中填写。\\n\\n原始响应："
                                + detail;
                    } else {
                        detail = "Cloudflare 地址可访问，但未通过安全台账兼容网关的读写校验："
                                + detail;
                    }
                    result = new SyncProvider.ConnectionResult(false, detail);
                }
'''
new='''                if (!result.success() && "Cloudflare".equals(resolvedType)) {
                    String detail = result.message();
                    if (detail.startsWith("网络连接问题：")) {
                        // Preserve the transport diagnosis. A timeout does not prove the Worker is reachable.
                    } else if (detail.contains("需要设备授权") || detail.contains("HTTP 401")) {
                        detail = "已使用同步空间名称和同步密码自动发起设备配对，但这个地址仍拒绝授权。它不是本版兼容网关，或仍使用旧私有授权协议。请重新部署仓库 cloudflare-worker；如果云端另外生成了设备 Token，也可在高级认证中填写。\\n\\n原始响应："
                                + detail;
                    } else {
                        detail = "Cloudflare 地址可访问，但未通过安全台账兼容网关的读写校验："
                                + detail;
                    }
                    result = new SyncProvider.ConnectionResult(false, detail);
                }
'''
if old not in t: raise SystemExit('Cloudflare test classification block not found')
t=t.replace(old,new,1)
old='''                    new AlertDialog.Builder(this).setTitle("连接失败")
                            .setMessage(checked.message()).setPositiveButton("确定", null).show();
'''
new='''                    new AlertDialog.Builder(this)
                            .setTitle(checked.message().startsWith("网络连接问题：") ? "网络连接问题" : "连接失败")
                            .setMessage(checked.message()).setPositiveButton("确定", null).show();
'''
if old not in t: raise SystemExit('connection failure dialog block not found')
t=t.replace(old,new,1)
p.write_text(t,encoding='utf-8')
print('Patched connection-test popup to preserve explicit network diagnosis')
