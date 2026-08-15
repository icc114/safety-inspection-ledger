from pathlib import Path


def replace_required(path, old, new, count=1):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'pattern not found in {path}: {old[:180]!r}')
    p.write_text(text.replace(old, new, count), encoding='utf-8')


# Version bump.
replace_required('app/build.gradle', "versionCode 15\n        versionName '1.2.12'", "versionCode 16\n        versionName '1.2.13'")

# WebDAV small logout-control markers. These are tiny files and do not require a full snapshot upload.
p = Path('app/src/main/java/cn/safetyledger/app/sync/WebDavClient.java')
text = p.read_text(encoding='utf-8')
old = '''    public void deleteSnapshot(String space, String name) throws Exception {
        delete(fileUrl(space, name));
    }
'''
new = '''    public void deleteSnapshot(String space, String name) throws Exception {
        delete(fileUrl(space, name));
    }

    /** Small control marker used to make device logout immediate without uploading a full backup. */
    public void setDeviceLoggedOut(String space, String deviceId, boolean loggedOut) throws Exception {
        String name = deviceId + ".logout";
        if (loggedOut) {
            putBytes(fileUrl(space, name),
                    String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        } else {
            delete(fileUrl(space, name));
        }
    }

    public boolean isDeviceLoggedOut(String space, String deviceId) throws Exception {
        Request request = request(fileUrl(space, deviceId + ".logout")).head().build();
        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 404) return false;
            if (!response.isSuccessful()) throw failure("读取设备登出状态失败", response);
            return true;
        }
    }
'''
if old not in text:
    raise SystemExit('WebDavClient deleteSnapshot pattern not found')
text = text.replace(old, new, 1)
p.write_text(text, encoding='utf-8')

# Cloud sync: check logout marker before any snapshot upload, plus admin remote logout and local logout.
p = Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java')
text = p.read_text(encoding='utf-8')
old = '''            String deviceId = ensureDeviceId();
            progress(listener, "正在读取云端设备列表…");
            List<String> snapshots = client.listSnapshots(config.space);
'''
new = '''            String deviceId = ensureDeviceId();
            if (client.isDeviceLoggedOut(config.space, deviceId)) {
                return finishForcedLogout(client, config, deviceId, listener);
            }
            progress(listener, "正在读取云端设备列表…");
            List<String> snapshots = client.listSnapshots(config.space);
'''
if old not in text:
    raise SystemExit('CloudSyncService initial deviceId pattern not found')
text = text.replace(old, new, 1)

old = '''            // Merged role data may contain an administrator's change for this device.
            registerCurrentDevice(deviceId, emptyCloud);
            applyTombstones();

            progress(listener, "正在上传本机最新数据…");
'''
new = '''            // Merged role data may contain an administrator's change for this device.
            registerCurrentDevice(deviceId, emptyCloud);
            if (client.isDeviceLoggedOut(config.space, deviceId)
                    || "LOGGED_OUT".equals(deviceRole(deviceId))) {
                return finishForcedLogout(client, config, deviceId, listener);
            }
            applyTombstones();

            progress(listener, "正在上传本机最新数据…");
'''
if old not in text:
    raise SystemExit('CloudSyncService pre-upload pattern not found')
text = text.replace(old, new, 1)

insert_before = '''    /**
     * Deletes only device snapshot files in the currently configured sync space. It does not
'''
methods = r'''    /**
     * Administrator logout for another device. A tiny .logout marker is written first and the
     * target snapshot is removed. The target app checks the marker before every upload and clears
     * only its cloud credentials; local inspection records/photos remain untouched.
     */
    public DeviceLogoutResult logoutDevice(String targetDeviceId) throws Exception {
        if (!RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("已有同步任务正在运行，请等待当前同步完成后再试");
        }
        Config config = null;
        try {
            config = requireConfig();
            WebDavClient client = client(config);
            prepare(client, config);
            String currentId = ensureDeviceId();
            String currentRole = deviceRole(currentId);
            if (!("OWNER".equals(currentRole) || "ADMIN".equals(currentRole))) {
                throw new SecurityException("只有管理员可以登出其他设备");
            }
            if (targetDeviceId == null || targetDeviceId.isBlank() || targetDeviceId.equals(currentId)) {
                throw new IllegalArgumentException("请选择其他设备；本机请使用“退出当前同步空间”");
            }
            String targetRole = deviceRole(targetDeviceId);
            if ("OWNER".equals(targetRole)) {
                throw new SecurityException("首位管理员不能被其他设备登出");
            }
            client.setDeviceLoggedOut(config.space, targetDeviceId, true);
            client.deleteSnapshot(config.space, targetDeviceId + ".safetydata");
            long now = System.currentTimeMillis();
            repo.raw().execSQL("UPDATE sync_devices SET role='LOGGED_OUT',updated_at=? WHERE device_id=?",
                    new Object[]{now, targetDeviceId});
            repo.raw().delete("sync_queue", "entity_type='sync_device' AND entity_id=?",
                    new String[]{targetDeviceId});
            return new DeviceLogoutResult(targetDeviceId, now);
        } finally {
            if (config != null) Arrays.fill(config.spacePassword, '\0');
            RUNNING.set(false);
        }
    }

    /** Re-enable a previously logged-out device. This is intentionally explicit. */
    public DeviceLogoutResult allowDeviceRejoin(String targetDeviceId) throws Exception {
        if (!RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("已有同步任务正在运行，请等待当前同步完成后再试");
        }
        Config config = null;
        try {
            config = requireConfig();
            WebDavClient client = client(config);
            prepare(client, config);
            String currentId = ensureDeviceId();
            String currentRole = deviceRole(currentId);
            if (!("OWNER".equals(currentRole) || "ADMIN".equals(currentRole))) {
                throw new SecurityException("只有管理员可以允许设备重新加入");
            }
            if (targetDeviceId == null || targetDeviceId.isBlank() || targetDeviceId.equals(currentId)) {
                throw new IllegalArgumentException("设备选择无效");
            }
            client.setDeviceLoggedOut(config.space, targetDeviceId, false);
            long now = System.currentTimeMillis();
            repo.raw().execSQL("UPDATE sync_devices SET role='FIELD',updated_at=? WHERE device_id=?",
                    new Object[]{now, targetDeviceId});
            // Upload the administrator snapshot once so a previously logged-out client can learn
            // that its role is FIELD again after the marker is removed.
            uploadSnapshot(new BackupService(context), client, config, currentId);
            return new DeviceLogoutResult(targetDeviceId, now);
        } finally {
            if (config != null) Arrays.fill(config.spacePassword, '\0');
            RUNNING.set(false);
        }
    }

    /** Voluntary logout of the current phone. Local business data is preserved. */
    public CurrentLogoutResult logoutCurrentDevice() throws Exception {
        if (!RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("已有同步任务正在运行，请等待当前同步完成后再试");
        }
        Config config = null;
        try {
            config = requireConfig();
            WebDavClient client = client(config);
            prepare(client, config);
            String deviceId = ensureDeviceId();
            // A voluntary logout must remain reversible, so remove any stale forced-logout marker.
            client.setDeviceLoggedOut(config.space, deviceId, false);
            client.deleteSnapshot(config.space, deviceId + ".safetydata");
            disableLocalSync(false, deviceId);
            long now = System.currentTimeMillis();
            return new CurrentLogoutResult(deviceId, now);
        } finally {
            if (config != null) Arrays.fill(config.spacePassword, '\0');
            RUNNING.set(false);
        }
    }

    private Result finishForcedLogout(WebDavClient client, Config config, String deviceId,
                                      ProgressListener listener) throws Exception {
        progress(listener, "本设备已被管理员登出");
        try { client.deleteSnapshot(config.space, deviceId + ".safetydata"); }
        catch (Exception ignored) { /* marker already blocks the normal app before upload */ }
        long now = System.currentTimeMillis();
        repo.raw().execSQL("UPDATE sync_devices SET role='LOGGED_OUT',updated_at=? WHERE device_id=?",
                new Object[]{now, deviceId});
        disableLocalSync(true, deviceId);
        repo.putSetting("last_sync_at", String.valueOf(now));
        repo.putSetting("last_sync_error", "");
        return new Result(0, 0, 0, "LOGGED_OUT", now, "");
    }

    private void disableLocalSync(boolean forced, String deviceId) {
        repo.raw().execSQL("UPDATE sync_providers SET enabled=0,encrypted_secret='',token_ciphertext='',encryption_secret='' WHERE enabled=1");
        repo.putSetting("cloud_role", forced ? "LOGGED_OUT" : "");
        repo.putSetting("device_role", forced ? "FIELD" : "PRIMARY");
        repo.putSetting("last_sync_error", "");
        CloudSyncScheduler.cancel(context);
    }

'''
if insert_before not in text:
    raise SystemExit('CloudSyncService resetCloudSpace insertion marker not found')
text = text.replace(insert_before, methods + insert_before, 1)
text = text.replace('    public record DiscoveryResult(int remoteDevices, long completedAt) {}\n    public record ResetResult',
                    '    public record DiscoveryResult(int remoteDevices, long completedAt) {}\n    public record DeviceLogoutResult(String deviceId, long completedAt) {}\n    public record CurrentLogoutResult(String deviceId, long completedAt) {}\n    public record ResetResult', 1)
p.write_text(text, encoding='utf-8')

# Settings UI: clicking self offers local logout; clicking peers offers role + remote logout.
p = Path('app/src/main/java/cn/safetyledger/app/SettingsActivity.java')
text = p.read_text(encoding='utf-8')
old = '''                if (ids.get(index).equals(localId)) {
                    Ui.toast(this, "这是本机；请点击其他设备设置角色");
                    return;
                }
'''
new = '''                if (ids.get(index).equals(localId)) {
                    confirmCurrentDeviceLogout();
                    return;
                }
'''
if old not in text:
    raise SystemExit('SettingsActivity local device click pattern not found')
text = text.replace(old, new, 1)

start = text.index('    private void chooseDeviceRole(String deviceId, String label) {')
end = text.index('\n    private String roleName(String role) {', start)
new_methods = r'''    private void chooseDeviceRole(String deviceId, String label) {
        String currentRole = "FIELD";
        try (Cursor cursor = repo.raw().rawQuery(
                "SELECT role FROM sync_devices WHERE device_id=?", new String[]{deviceId})) {
            if (cursor.moveToFirst()) currentRole = cursor.getString(0);
        }
        if ("LOGGED_OUT".equals(currentRole)) {
            new AlertDialog.Builder(this)
                    .setTitle(label.replace("\n", " · "))
                    .setItems(new String[]{"允许重新加入为工作人员"}, (dialog, which) -> allowDeviceRejoin(deviceId))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(label.replace("\n", " · "))
                .setItems(new String[]{"设为管理员", "设为工作人员", "登出此设备"}, (dialog, which) -> {
                    if (which == 2) {
                        confirmRemoteDeviceLogout(deviceId, label);
                        return;
                    }
                    String role = which == 0 ? "ADMIN" : "FIELD";
                    repo.raw().execSQL("UPDATE sync_devices SET role=?,updated_at=? WHERE device_id=?",
                            new Object[]{role, System.currentTimeMillis(), deviceId});
                    repo.queueDeviceRole(deviceId);
                    Ui.toast(this, "设备已设为" + roleName(role) + "，已加入后台同步队列");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRemoteDeviceLogout(String deviceId, String label) {
        new AlertDialog.Builder(this)
                .setTitle("登出设备")
                .setMessage("确定让“" + label.replace("\n", " · ") + "”退出当前同步空间吗？\n\n"
                        + "云端会立即移除该设备快照，并写入轻量登出标记；该设备下次联网同步时会自动停用云同步并清除本机保存的同步密码。"
                        + "\n\n不会删除该设备本地的检查记录、照片、签名或模板。")
                .setPositiveButton("确认登出", (dialog, which) -> logoutRemoteDevice(deviceId))
                .setNegativeButton("取消", null)
                .show();
    }

    private void logoutRemoteDevice(String deviceId) {
        syncStatus.setText("同步状态：正在登出设备…");
        new Thread(() -> {
            try {
                CloudSyncService.DeviceLogoutResult result = new CloudSyncService(this).logoutDevice(deviceId);
                runOnUiThread(() -> {
                    String time = DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(result.completedAt()));
                    syncStatus.setText("同步状态：设备已登出 · " + time);
                    Ui.toast(this, "设备已登出；本地检查资料不会被删除");
                });
            } catch (Exception error) {
                String message = readableError(error);
                runOnUiThread(() -> {
                    syncStatus.setText("同步状态：设备登出失败 · " + message);
                    new AlertDialog.Builder(this).setTitle("设备登出失败")
                            .setMessage(message).setPositiveButton("确定", null).show();
                });
            }
        }, "logout-remote-device").start();
    }

    private void allowDeviceRejoin(String deviceId) {
        syncStatus.setText("同步状态：正在允许设备重新加入…");
        new Thread(() -> {
            try {
                CloudSyncService.DeviceLogoutResult result = new CloudSyncService(this).allowDeviceRejoin(deviceId);
                runOnUiThread(() -> {
                    String time = DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(result.completedAt()));
                    syncStatus.setText("同步状态：已允许设备重新加入 · " + time);
                    Ui.toast(this, "已允许重新加入；该设备需重新输入同步密码并启用云同步");
                });
            } catch (Exception error) {
                String message = readableError(error);
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("操作失败")
                        .setMessage(message).setPositiveButton("确定", null).show());
            }
        }, "allow-device-rejoin").start();
    }

    private void confirmCurrentDeviceLogout() {
        new AlertDialog.Builder(this)
                .setTitle("退出当前同步空间")
                .setMessage("确定让本机退出当前云同步吗？\n\n"
                        + "本机检查记录、照片、签名和模板全部保留；只会移除本机云端快照、停用云同步，并清除本机保存的同步密码。")
                .setPositiveButton("确认退出", (dialog, which) -> logoutCurrentDevice())
                .setNegativeButton("取消", null)
                .show();
    }

    private void logoutCurrentDevice() {
        syncStatus.setText("同步状态：正在退出当前同步空间…");
        new Thread(() -> {
            try {
                new CloudSyncService(this).logoutCurrentDevice();
                runOnUiThread(() -> {
                    syncEnabledStatus.setText("云同步：未启用");
                    syncEnabledStatus.setTextColor(Ui.TEXT);
                    syncStatus.setText("同步状态：本机已退出当前同步空间");
                    if (syncSaveButton != null) syncSaveButton.setText("保存并启用");
                    encryption.setText("");
                    encryption.setHint("同步密码（至少 8 位）");
                    new AlertDialog.Builder(this).setTitle("已退出")
                            .setMessage("本机已退出当前同步空间。本地检查资料全部保留；以后需要重新加入时，请重新填写云同步配置和同步密码。")
                            .setPositiveButton("确定", null).show();
                });
            } catch (Exception error) {
                String message = readableError(error);
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("退出失败")
                        .setMessage(message).setPositiveButton("确定", null).show());
            }
        }, "logout-current-device").start();
    }
'''
text = text[:start] + new_methods + text[end:]
text = text.replace('    private String roleName(String role) {\n        return "FIELD".equals(role) ? "工作人员" : "管理员";\n    }',
                    '    private String roleName(String role) {\n        if ("LOGGED_OUT".equals(role)) return "已登出";\n        return "FIELD".equals(role) ? "工作人员" : "管理员";\n    }', 1)

# Handle remote forced logout result cleanly instead of mis-labelling it as administrator.
old = '''                    String role = "FIELD".equals(result.role()) ? "工作人员" : "管理员";
                    String type = (String) provider.getSelectedItem();
'''
new = '''                    if ("LOGGED_OUT".equals(result.role())) {
                        syncEnabledStatus.setText("云同步：未启用");
                        syncEnabledStatus.setTextColor(Ui.TEXT);
                        syncStatus.setText("同步状态：本设备已被管理员登出");
                        if (syncSaveButton != null) syncSaveButton.setText("保存并启用");
                        encryption.setText("");
                        encryption.setHint("同步密码（至少 8 位）");
                        new AlertDialog.Builder(this).setTitle("本设备已被登出")
                                .setMessage("管理员已将本设备从当前同步空间登出。云同步已停用并清除了本机保存的同步密码；本地检查记录、照片、签名和模板全部保留。")
                                .setPositiveButton("确定", null).show();
                        return;
                    }
                    String role = "FIELD".equals(result.role()) ? "工作人员" : "管理员";
                    String type = (String) provider.getSelectedItem();
'''
if old not in text:
    raise SystemExit('SettingsActivity runSync role pattern not found')
text = text.replace(old, new, 1)
p.write_text(text, encoding='utf-8')
