from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'pattern missing in {path}: {old[:160]!r}')
    p.write_text(text.replace(old, new, count), encoding='utf-8')

# Settings: one clean device-management action. Pressing it performs ONLY the tiny device sync,
# then opens the device list. No separate/ambiguous "quick refresh" or big content sync.
p = Path('app/src/main/java/cn/safetyledger/app/SettingsActivity.java')
text = p.read_text(encoding='utf-8')
text = text.replace('Ui.sectionTitle(this, "3", "多设备角色", "首台设备自动成为管理员，后加入设备默认为工作人员")',
                    'Ui.sectionTitle(this, "3", "设备管理", "设备信息同步与检查内容同步相互独立")', 1)
text = text.replace('Ui.toast(this, "设备名称已保存；点“同步设备信息”即可立即更新到其他设备")',
                    'Ui.toast(this, "设备名称已保存；进入“管理已配对设备”时会单独同步设备信息")', 1)
old = '''        LinearLayout deviceActions = Ui.row(this);
        Button syncDevices = Ui.compactButton(this, "同步设备信息", true);
        Button manage = Ui.compactButton(this, "管理已配对设备", false);
        syncDevices.setOnClickListener(view -> syncDeviceInfo(false));
        manage.setOnClickListener(view -> manageDevices());
        deviceActions.addView(syncDevices, Ui.weight(1));
        deviceActions.addView(Ui.horizontalGap(this, 5));
        deviceActions.addView(manage, Ui.weight(1));
        card.addView(deviceActions);
'''
new = '''        Button manage = Ui.secondaryButton(this, "管理已配对设备");
        manage.setOnClickListener(view -> syncDeviceInfo(true));
        card.addView(manage);
'''
if old not in text:
    raise SystemExit('device action block missing')
text = text.replace(old, new, 1)
# Make content status terminology explicit.
text = text.replace('syncStatus = Ui.text(this, "同步状态：未配置", 14, true);',
                    'syncStatus = Ui.text(this, "检查内容：未配置", 14, true);', 1)
text = text.replace('syncStatus.setText("同步状态：正在测试…")',
                    'syncStatus.setText("检查内容：正在测试连接…")')
# Device-only actions must use device status, not inspection-content status.
repls = {
    'syncStatus.setText("同步状态：正在登出设备…")': 'if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：正在登出设备…")',
    'syncStatus.setText("同步状态：设备已登出 · " + time)': 'if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：设备已登出 · " + time)',
    'syncStatus.setText("同步状态：设备登出失败 · " + message)': 'if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：设备登出失败 · " + message)',
    'syncStatus.setText("同步状态：正在允许设备重新加入…")': 'if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：正在允许重新加入…")',
    'syncStatus.setText("同步状态：已允许设备重新加入 · " + time)': 'if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：已允许重新加入 · " + time)',
    'syncStatus.setText("同步状态：正在退出当前同步空间…")': 'if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：正在退出当前同步空间…")',
    'syncStatus.setText("同步状态：本机已退出当前同步空间")': 'if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：本机已退出当前同步空间")',
}
for old_s, new_s in repls.items():
    if old_s not in text:
        raise SystemExit(f'Settings device status missing: {old_s}')
    text = text.replace(old_s, new_s, 1)
p.write_text(text, encoding='utf-8')

# Background scheduler: distinguish lightweight device sync errors from content sync errors,
# and do not retry merely because the corresponding channel is already busy.
p = Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncJobService.java')
text = p.read_text(encoding='utf-8')
old = '''        new Thread(() -> {
            boolean retry = false;
            try {
                CloudSyncService service = new CloudSyncService(this);
                if (params.getJobId() == CloudSyncScheduler.DEVICE_JOB_ID) service.syncDeviceManagement();
                else service.syncNow();
            } catch (OutOfMemoryError error) {
                retry = false;
                String message = "同步数据较大且当前系统内存不足，本次后台同步已安全停止。请升级所有设备到 1.2.14 后重试。";
                repo.putSetting("last_sync_error", message);
                notifyFailure(message);
            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName()
                        : error.getMessage();
                // Manual and background sync can meet. This is not a network failure and must not
                // trigger notifications/retry bursts; the active sync already owns the work.
                if (!message.contains("已有同步任务正在运行")) {
                    retry = true;
                    repo.putSetting("last_sync_error", message);
                    notifyFailure(message);
                }
            }
            jobFinished(params, retry);
        }, "safety-ledger-cloud-sync").start();
'''
new = '''        new Thread(() -> {
            boolean retry = false;
            boolean deviceJob = params.getJobId() == CloudSyncScheduler.DEVICE_JOB_ID;
            try {
                CloudSyncService service = new CloudSyncService(this);
                if (deviceJob) service.syncDeviceManagement();
                else service.syncNow();
            } catch (OutOfMemoryError error) {
                retry = false;
                String message = "检查内容较大且当前系统内存不足，本次后台同步已安全停止。请稍后重试。";
                repo.putSetting(deviceJob ? "last_device_sync_error" : "last_sync_error", message);
                if (!deviceJob) notifyFailure(message);
            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName()
                        : error.getMessage();
                // A manual operation may meet its own background channel. That is not a failure.
                if (!message.contains("同步正在运行")) {
                    retry = true;
                    repo.putSetting(deviceJob ? "last_device_sync_error" : "last_sync_error", message);
                    if (!deviceJob) notifyFailure(message);
                }
            }
            jobFinished(params, retry);
        }, params.getJobId() == CloudSyncScheduler.DEVICE_JOB_ID
                ? "safety-ledger-device-sync" : "safety-ledger-content-sync").start();
'''
if old not in text:
    raise SystemExit('CloudSyncJobService block missing')
text = text.replace(old, new, 1)
p.write_text(text, encoding='utf-8')

# Cloud service: content progress language + robust combined reset.
p = Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java')
text = p.read_text(encoding='utf-8')
text = text.replace('progress(listener, "正在读取云端设备列表…");',
                    'progress(listener, "正在读取其他设备的检查内容…");', 1)
text = text.replace('progress(listener, "正在登记本机设备…");',
                    'progress(listener, "正在准备本机检查内容…");', 1)
text = text.replace('"旧版云端快照过大，已跳过；请将该设备升级到 1.2.14 后重新同步"',
                    '"旧版云端快照过大，已跳过；请将该设备升级到 1.2.15 后重新同步"', 1)
old_start = '''    public ResetResult resetCloudSpace(ProgressListener listener) throws Exception {
        if (!DEVICE_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("设备信息同步正在运行，请稍后再试");
        }
        Config config = null;
'''
new_start = '''    public ResetResult resetCloudSpace(ProgressListener listener) throws Exception {
        if (!CONTENT_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("检查内容同步正在运行，请等待完成后再重建云端");
        }
        if (!DEVICE_RUNNING.compareAndSet(false, true)) {
            CONTENT_RUNNING.set(false);
            throw new IllegalStateException("设备信息同步正在运行，请等待完成后再重建云端");
        }
        Config config = null;
'''
if old_start not in text:
    raise SystemExit('reset start missing')
text = text.replace(old_start, new_start, 1)
old_mid = '''            List<String> snapshots = client.listSnapshots(config.space);
            int deleted = 0;
            for (int i = 0; i < snapshots.size(); i++) {
                progress(listener, "正在清理旧设备 " + (i + 1) + "/" + snapshots.size() + "…");
                client.deleteSnapshot(config.space, snapshots.get(i));
                deleted++;
            }

            SQLiteDatabase database = repo.raw();
'''
new_mid = '''            List<String> snapshots = client.listSnapshots(config.space);
            List<String> profiles = client.listDeviceProfiles(config.space);
            int deleted = 0;
            for (int i = 0; i < snapshots.size(); i++) {
                progress(listener, "正在清理旧检查内容 " + (i + 1) + "/" + snapshots.size() + "…");
                client.deleteSnapshot(config.space, snapshots.get(i));
                deleted++;
            }
            for (String file : profiles) {
                String id = file.substring(0, file.length() - ".device.json".length());
                client.deleteDeviceProfile(config.space, id);
                client.setDeviceLoggedOut(config.space, id, false);
            }

            SQLiteDatabase database = repo.raw();
'''
if old_mid not in text:
    raise SystemExit('reset middle missing')
text = text.replace(old_mid, new_mid, 1)
old_upload = '''            progress(listener, "正在建立新的管理员设备…");
            uploadSnapshot(new BackupService(context), client, config, deviceId);
            long now = System.currentTimeMillis();
'''
new_upload = '''            progress(listener, "正在建立新的管理员设备…");
            long now = System.currentTimeMillis();
            client.uploadDeviceProfile(config.space, deviceId,
                    deviceProfileJson(deviceId, deviceName(deviceId), "OWNER", now));
            progress(listener, "正在上传本机检查内容…");
            uploadSnapshot(new BackupService(context), client, config, deviceId);
'''
if old_upload not in text:
    raise SystemExit('reset upload missing')
text = text.replace(old_upload, new_upload, 1)
old_finally = '''        } finally {
            if (config != null) Arrays.fill(config.spacePassword, '\\0');
            DEVICE_RUNNING.set(false);
        }
    }

    private Config requireConfig()'''
new_finally = '''        } finally {
            if (config != null) Arrays.fill(config.spacePassword, '\\0');
            DEVICE_RUNNING.set(false);
            CONTENT_RUNNING.set(false);
        }
    }

    private Config requireConfig()'''
if old_finally not in text:
    raise SystemExit('reset finally missing')
text = text.replace(old_finally, new_finally, 1)
p.write_text(text, encoding='utf-8')

print('v1.2.15 refinements applied')
