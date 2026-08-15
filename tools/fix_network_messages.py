from pathlib import Path

# Manual sync/test/reset must show an actionable network message instead of OkHttp's raw
# failed-to-connect/12000ms exception string.
p=Path('app/src/main/java/cn/safetyledger/app/SettingsActivity.java')
t=p.read_text(encoding='utf-8')
old='''            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName()
                        : error.getMessage();
                repo.putSetting("last_sync_error", message);
                runOnUiThread(() -> {
                    syncStatus.setText("同步状态：失败 · " + message);
                    if (!message.contains("已有同步任务正在运行")) syncNotification(message);
                    new AlertDialog.Builder(this).setTitle("同步失败")
                            .setMessage(message).setPositiveButton("确定", null).show();
                });
            }
'''
new='''            } catch (Exception error) {
                String message = readableError(error);
                repo.putSetting("last_sync_error", message);
                runOnUiThread(() -> {
                    syncStatus.setText("同步状态：失败 · " + message);
                    if (!message.contains("已有同步任务正在运行")) syncNotification(message);
                    new AlertDialog.Builder(this)
                            .setTitle(message.startsWith("网络连接问题：") ? "网络连接问题" : "同步失败")
                            .setMessage(message).setPositiveButton("确定", null).show();
                });
            }
'''
if old not in t: raise SystemExit('Settings runSync catch not found')
t=t.replace(old,new,1)
old='''            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName()
                        : error.getMessage();
                runOnUiThread(() -> {
                    syncStatus.setText("同步状态：云端重建失败 · " + message);
                    new AlertDialog.Builder(this).setTitle("云端重建失败")
                            .setMessage(message).setPositiveButton("确定", null).show();
                });
            }
'''
new='''            } catch (Exception error) {
                String message = readableError(error);
                runOnUiThread(() -> {
                    syncStatus.setText("同步状态：云端重建失败 · " + message);
                    new AlertDialog.Builder(this)
                            .setTitle(message.startsWith("网络连接问题：") ? "网络连接问题" : "云端重建失败")
                            .setMessage(message).setPositiveButton("确定", null).show();
                });
            }
'''
if old not in t: raise SystemExit('Settings reset catch not found')
t=t.replace(old,new,1)
# ConnectionResult has already lost the exception object; classify inside WebDavClient so this
# screen still gets the user-facing network text.
p.write_text(t,encoding='utf-8')

p=Path('app/src/main/java/cn/safetyledger/app/sync/WebDavClient.java')
t=p.read_text(encoding='utf-8')
t=t.replace('return new SyncProvider.ConnectionResult(false, readable(error));',
            'return new SyncProvider.ConnectionResult(false, SyncErrorFormatter.format(error));',1)
p.write_text(t,encoding='utf-8')

print('Classified connection timeouts/DNS/connect failures as explicit network problems')
