from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'pattern not found in {path}: {old[:160]!r}')
    p.write_text(text.replace(old, new, count), encoding='utf-8')


# Version: this is the first patch after the permanent-signature 1.2.9 baseline.
replace('app/build.gradle', "versionCode 12\n        versionName '1.2.9'",
        "versionCode 13\n        versionName '1.2.10'")
replace('.github/workflows/android-build.yml', '安全检查台账-1.2.9-debug', '安全检查台账-1.2.10-debug')
replace('.github/workflows/android-release.yml', '安全检查台账-1.2.9-正式签名', '安全检查台账-1.2.10-正式签名')


# WebDAV transport: allow the app to explicitly remove stale cloud device snapshots.
replace('app/src/main/java/cn/safetyledger/app/sync/WebDavClient.java',
'''    public void upload(String space, String name, File source) throws Exception {
        RequestBody body = RequestBody.create(BINARY, source);
        try (Response response = http.newCall(request(fileUrl(space, name)).put(body).build()).execute()) {
            if (!response.isSuccessful()) throw failure("上传本机快照失败", response);
        }
    }
''',
'''    public void upload(String space, String name, File source) throws Exception {
        RequestBody body = RequestBody.create(BINARY, source);
        try (Response response = http.newCall(request(fileUrl(space, name)).put(body).build()).execute()) {
            if (!response.isSuccessful()) throw failure("上传本机快照失败", response);
        }
    }

    public void deleteSnapshot(String space, String name) throws Exception {
        delete(fileUrl(space, name));
    }
''')


# Robust cloud synchronization. Important changes:
# - one sync at a time (manual/background cannot corrupt each other)
# - publish the current device before downloading peers so it is discoverable even when a stale peer is bad
# - a single stale/corrupt old test snapshot no longer blocks every other device
# - progress callbacks make it clear what the app is doing
# - explicit reset of the current cloud space for retiring old test devices
Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java').write_text(r'''package cn.safetyledger.app.sync;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import cn.safetyledger.app.backup.BackupService;
import cn.safetyledger.app.data.LedgerRepository;
import cn.safetyledger.app.media.MediaService;
import cn.safetyledger.app.security.SecretStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provider-neutral snapshot synchronization. Every device owns one encrypted snapshot;
 * a sync downloads and merges all peer snapshots before uploading its new aggregate view.
 */
public final class CloudSyncService {
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    public interface ProgressListener {
        void onProgress(String message);
    }

    private final Context context;
    private final LedgerRepository repo;

    public CloudSyncService(Context context) {
        this.context = context.getApplicationContext();
        this.repo = new LedgerRepository(this.context);
    }

    public Result syncNow() throws Exception {
        return syncNow(null);
    }

    public Result syncNow(ProgressListener listener) throws Exception {
        if (!RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("已有同步任务正在运行，请等待当前同步完成后再试");
        }
        Config config = null;
        try {
            config = requireConfig();
            progress(listener, "正在连接云端…");
            WebDavClient client = client(config);
            prepare(client, config);

            String deviceId = ensureDeviceId();
            progress(listener, "正在读取云端设备列表…");
            List<String> snapshots = client.listSnapshots(config.space);
            boolean emptyCloud = snapshots.isEmpty();

            // Register locally before touching peer snapshots. A new device joining a non-empty
            // space is FIELD until an existing OWNER/ADMIN explicitly promotes it.
            registerCurrentDevice(deviceId, emptyCloud);

            // Publish presence first. Previously a bad/stale peer snapshot could fail before the
            // new device ever uploaded anything, so the administrator could never see/manage it.
            BackupService backup = new BackupService(context);
            progress(listener, "正在登记本机设备…");
            uploadSnapshot(backup, client, config, deviceId);

            int peers = 0;
            int changed = 0;
            int skipped = 0;
            List<String> warnings = new ArrayList<>();
            int peerTotal = 0;
            for (String name : snapshots) if (!name.equals(deviceId + ".safetydata")) peerTotal++;
            int peerIndex = 0;

            for (String name : snapshots) {
                if (name.equals(deviceId + ".safetydata")) continue;
                peerIndex++;
                progress(listener, "正在接收设备 " + peerIndex + "/" + peerTotal + "…");
                File remote = File.createTempFile("safety-cloud-in-", ".safetydata", context.getCacheDir());
                try {
                    client.download(config.space, name, remote);
                    try (FileInputStream input = new FileInputStream(remote)) {
                        BackupService.RestorePackage restore = backup.decryptAndValidate(input,
                                config.spacePassword.clone());
                        changed += backup.mergeRestore(restore);
                    }
                    peers++;
                } catch (Exception peerError) {
                    skipped++;
                    String detail = readable(peerError);
                    if (detail.length() > 90) detail = detail.substring(0, 90) + "…";
                    warnings.add(shortDevice(name) + "：" + detail);
                } finally {
                    remote.delete();
                }
            }

            // Merged role data may contain an administrator's change for this device.
            registerCurrentDevice(deviceId, emptyCloud);
            applyTombstones();

            progress(listener, "正在上传本机最新数据…");
            uploadSnapshot(backup, client, config, deviceId);

            long now = System.currentTimeMillis();
            repo.raw().execSQL("DELETE FROM sync_queue");
            repo.raw().execSQL("UPDATE tombstones SET synced_at=? WHERE synced_at IS NULL",
                    new Object[]{now});
            repo.putSetting("last_sync_at", String.valueOf(now));
            repo.putSetting("last_sync_error", "");
            String warning = warnings.isEmpty() ? "" : String.join("；", warnings);
            repo.putSetting("last_sync_warning", warning);
            progress(listener, skipped == 0 ? "同步完成" : "同步完成，但有旧设备快照被跳过");
            return new Result(peers, changed, skipped, deviceRole(deviceId), now, warning);
        } finally {
            if (config != null) Arrays.fill(config.spacePassword, '\0');
            RUNNING.set(false);
        }
    }

    /**
     * Deletes only device snapshot files in the currently configured sync space. It does not
     * delete any local inspection record/photo. The current phone then becomes the first owner
     * and uploads a clean snapshot. This is intentionally explicit for retiring test devices.
     */
    public ResetResult resetCloudSpace(ProgressListener listener) throws Exception {
        if (!RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("已有同步任务正在运行，请等待当前同步完成后再试");
        }
        Config config = null;
        try {
            config = requireConfig();
            progress(listener, "正在连接云端…");
            WebDavClient client = client(config);
            prepare(client, config);
            progress(listener, "正在读取旧设备快照…");
            List<String> snapshots = client.listSnapshots(config.space);
            int deleted = 0;
            for (int i = 0; i < snapshots.size(); i++) {
                progress(listener, "正在清理旧设备 " + (i + 1) + "/" + snapshots.size() + "…");
                client.deleteSnapshot(config.space, snapshots.get(i));
                deleted++;
            }

            SQLiteDatabase database = repo.raw();
            database.delete("sync_devices", null, null);
            repo.putSetting("cloud_role", "");
            repo.putSetting("device_role", "PRIMARY");
            repo.putSetting("last_sync_error", "");
            repo.putSetting("last_sync_warning", "");

            String deviceId = ensureDeviceId();
            registerCurrentDevice(deviceId, true);
            progress(listener, "正在建立新的管理员设备…");
            uploadSnapshot(new BackupService(context), client, config, deviceId);
            long now = System.currentTimeMillis();
            repo.putSetting("last_sync_at", String.valueOf(now));
            progress(listener, "云端同步空间已重新建立");
            return new ResetResult(deleted, deviceId, now);
        } finally {
            if (config != null) Arrays.fill(config.spacePassword, '\0');
            RUNNING.set(false);
        }
    }

    private Config requireConfig() throws Exception {
        Config config = loadConfig();
        if (config == null) throw new IllegalStateException("请先保存并启用云同步配置");
        if (!(config.type.contains("WebDAV") || "Cloudflare".equals(config.type)
                || "自定义 HTTP 服务器".equals(config.type))) {
            throw new IllegalStateException(config.type + " 尚未接入授权协议；当前可真实同步 WebDAV、飞牛 WebDAV及兼容 WebDAV 的 Cloudflare Worker");
        }
        if (config.spacePassword.length < 8) {
            throw new IllegalStateException("同步空间密码至少 8 位，请重新保存配置");
        }
        return config;
    }

    private WebDavClient client(Config config) {
        return new WebDavClient(config.endpoint, config.username,
                config.serverPassword, config.token,
                "Cloudflare".equals(config.type) ? config.space : "",
                "Cloudflare".equals(config.type) ? new String(config.spacePassword) : "");
    }

    private void prepare(WebDavClient client, Config config) throws Exception {
        try {
            client.prepare(config.space);
        } catch (Exception error) {
            String message = readable(error);
            if ("Cloudflare".equals(config.type)) {
                if (message.contains("需要设备授权") || message.contains("HTTP 401")) {
                    message = "Cloudflare 自动配对被拒绝，请确认同步空间名称和密码一致。原始响应：" + message;
                } else if (message.contains("HTTP 404") || message.contains("HTTP 405")
                        || message.contains("HTTP 500") || message.contains("HTTP 503")
                        || message.contains("不是可读的 WebDAV")) {
                    message = "Cloudflare R2 Worker 未通过 WebDAV 目录准备：" + message;
                }
            }
            throw new java.io.IOException(message, error);
        }
    }

    private String ensureDeviceId() {
        String deviceId = repo.setting("device_id", "");
        if (deviceId.isBlank()) {
            deviceId = UUID.randomUUID().toString();
            repo.putSetting("device_id", deviceId);
        }
        return deviceId;
    }

    private void uploadSnapshot(BackupService backup, WebDavClient client,
                                Config config, String deviceId) throws Exception {
        File outgoing = File.createTempFile("safety-cloud-out-", ".safetydata", context.getCacheDir());
        try {
            try (FileOutputStream output = new FileOutputStream(outgoing)) {
                backup.exportData(output, config.spacePassword.clone());
            }
            client.upload(config.space, deviceId + ".safetydata", outgoing);
        } finally {
            outgoing.delete();
        }
    }

    private Config loadConfig() throws Exception {
        try (Cursor cursor = repo.raw().rawQuery(
                "SELECT provider_type,endpoint,username,encrypted_secret,token_ciphertext,sync_space,encryption_secret FROM sync_providers WHERE enabled=1 ORDER BY updated_at DESC LIMIT 1",
                null)) {
            if (!cursor.moveToFirst()) return null;
            SecretStore store = new SecretStore();
            return new Config(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    store.decrypt(cursor.getString(3)), store.decrypt(cursor.getString(4)),
                    cursor.getString(5), store.decrypt(cursor.getString(6)).toCharArray());
        }
    }

    private void registerCurrentDevice(String deviceId, boolean emptyCloud) {
        SQLiteDatabase database = repo.raw();
        long now = System.currentTimeMillis();
        String name = repo.setting("device_name", android.os.Build.MANUFACTURER + " "
                + android.os.Build.MODEL);
        String currentRole = deviceRole(deviceId);
        if (currentRole == null) {
            String owner = firstOwner();
            currentRole = owner == null && emptyCloud ? "OWNER" : "FIELD";
            if (owner == null && !emptyCloud && repo.setting("cloud_role", "").equals("OWNER")) {
                currentRole = "OWNER";
            }
            database.execSQL("INSERT OR IGNORE INTO sync_devices(device_id,display_name,role,first_seen_at,last_seen_at,updated_at) VALUES(?,?,?,?,?,?)",
                    new Object[]{deviceId, name, currentRole, now, now, now});
        } else {
            database.execSQL("UPDATE sync_devices SET display_name=?,last_seen_at=? WHERE device_id=?",
                    new Object[]{name, now, deviceId});
        }
        currentRole = deviceRole(deviceId);
        repo.putSetting("cloud_role", currentRole == null ? "FIELD" : currentRole);
        repo.putSetting("device_role", "OWNER".equals(currentRole) || "ADMIN".equals(currentRole)
                ? "PRIMARY" : "FIELD");
    }

    private String firstOwner() {
        try (Cursor cursor = repo.raw().rawQuery(
                "SELECT device_id FROM sync_devices WHERE role='OWNER' ORDER BY first_seen_at LIMIT 1", null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private String deviceRole(String deviceId) {
        try (Cursor cursor = repo.raw().rawQuery(
                "SELECT role FROM sync_devices WHERE device_id=?", new String[]{deviceId})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private void applyTombstones() {
        List<String[]> removals = new ArrayList<>();
        try (Cursor cursor = repo.raw().rawQuery(
                "SELECT entity_type,entity_id FROM tombstones", null)) {
            while (cursor.moveToNext()) removals.add(new String[]{cursor.getString(0), cursor.getString(1)});
        }
        SQLiteDatabase database = repo.raw();
        for (String[] value : removals) {
            String type = value[0], id = value[1];
            if ("inspection".equals(type)) {
                new MediaService(context).deleteInspectionMedia(id);
                database.delete("inspections", "id=?", new String[]{id});
            } else if ("template".equals(type)) {
                database.delete("templates", "id=?", new String[]{id});
            } else if ("template_item".equals(type)) {
                database.delete("template_items", "id=?", new String[]{id});
            }
        }
    }

    private static void progress(ProgressListener listener, String message) {
        if (listener != null) listener.onProgress(message);
    }

    private static String shortDevice(String name) {
        if (name == null) return "未知设备";
        String value = name.endsWith(".safetydata")
                ? name.substring(0, name.length() - ".safetydata".length()) : name;
        return value.length() > 8 ? value.substring(0, 8) : value;
    }

    private static String readable(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    public record Result(int peerDevices, int changedRows, int skippedSnapshots,
                         String role, long completedAt, String warning) {}
    public record ResetResult(int deletedSnapshots, String ownerDeviceId, long completedAt) {}

    private static final class Config {
        final String type, endpoint, username, serverPassword, token, space;
        final char[] spacePassword;
        Config(String type, String endpoint, String username, String serverPassword,
               String token, String space, char[] spacePassword) {
            this.type = type; this.endpoint = endpoint; this.username = username;
            this.serverPassword = serverPassword; this.token = token; this.space = space;
            this.spacePassword = spacePassword;
        }
    }
}
''', encoding='utf-8')


# Device management should refresh from cloud before presenting the list.
replace('app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''        Button manage = Ui.secondaryButton(this, "管理已配对设备 / 设置角色");
        manage.setOnClickListener(view -> manageDevices());
        card.addView(manage);''',
'''        Button manage = Ui.secondaryButton(this, "刷新并管理已配对设备 / 设置角色");
        manage.setOnClickListener(view -> refreshAndManageDevices());
        card.addView(manage);''')

# Add an explicit cloud reset tool for the user's current transition from test builds to the
# permanent-signature production baseline. It deletes cloud snapshots only, never local records.
replace('app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''        actions.addView(now, Ui.weight(1));
        card.addView(actions);
        return card;''',
'''        actions.addView(now, Ui.weight(1));
        card.addView(actions);
        card.addView(Ui.gap(this, 7));
        Button resetCloud = Ui.dangerButton(this, "清空云端旧测试设备 / 重新建立同步空间");
        resetCloud.setOnClickListener(view -> confirmResetCloudSpace());
        card.addView(resetCloud);
        TextView resetNote = Ui.text(this,
                "仅在准备废弃旧测试设备时使用：删除当前同步空间里的设备快照，但不会删除本机检查记录、照片、签名或模板。重建后本机成为首位管理员，其他正式设备再次同步后会重新加入。",
                12, false);
        resetNote.setTextColor(Ui.MUTED);
        card.addView(resetNote);
        return card;''')

# Replace manual sync with progress-aware single-flight synchronization, then add a refresh-before-manage path.
old_sync = '''    private void syncNow() {
        syncStatus.setText("同步状态：正在下载、合并并上传…");
        new Thread(() -> {
            try {
                CloudSyncService.Result result = new CloudSyncService(this).syncNow();
                runOnUiThread(() -> {
                    String role = "FIELD".equals(result.role()) ? "工作人员" : "管理员";
                    String type = (String) provider.getSelectedItem();
                    syncEnabledStatus.setText("云同步：已启用 · " + type);
                    syncEnabledStatus.setTextColor(Color.rgb(22, 128, 57));
                    if (syncSaveButton != null) syncSaveButton.setText("已启用 · 保存修改");
                    String time = DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(result.completedAt()));
                    syncStatus.setText("同步状态：成功 · " + time + " · 本机角色 " + role);
                    Ui.toast(this, "同步完成：接收 " + result.peerDevices()
                            + " 台设备，合并 " + result.changedRows() + " 项数据");
                    deviceRole.setSelection("FIELD".equals(result.role()) ? 1 : 0);
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName()
                        : error.getMessage();
                repo.putSetting("last_sync_error", message);
                runOnUiThread(() -> {
                    syncStatus.setText("同步状态：失败 · " + message);
                    syncNotification(message);
                    new AlertDialog.Builder(this).setTitle("同步失败")
                            .setMessage(message).setPositiveButton("确定", null).show();
                });
            }
        }, "manual-cloud-sync").start();
    }

    private void manageDevices() {'''

new_sync = '''    private void syncNow() {
        runSync(false);
    }

    private void refreshAndManageDevices() {
        syncStatus.setText("同步状态：正在刷新云端设备列表…");
        runSync(true);
    }

    private void runSync(boolean openDevicesAfter) {
        new Thread(() -> {
            try {
                CloudSyncService.Result result = new CloudSyncService(this).syncNow(message ->
                        runOnUiThread(() -> syncStatus.setText("同步状态：" + message)));
                runOnUiThread(() -> {
                    String role = "FIELD".equals(result.role()) ? "工作人员" : "管理员";
                    String type = (String) provider.getSelectedItem();
                    syncEnabledStatus.setText("云同步：已启用 · " + type);
                    syncEnabledStatus.setTextColor(Color.rgb(22, 128, 57));
                    if (syncSaveButton != null) syncSaveButton.setText("已启用 · 保存修改");
                    String time = DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(result.completedAt()));
                    String suffix = result.skippedSnapshots() > 0
                            ? " · 跳过旧快照 " + result.skippedSnapshots() + " 个" : "";
                    syncStatus.setText("同步状态：成功 · " + time + " · 本机角色 " + role + suffix);
                    Ui.toast(this, "同步完成：接收 " + result.peerDevices()
                            + " 台设备，合并 " + result.changedRows() + " 项数据" + suffix);
                    deviceRole.setSelection("FIELD".equals(result.role()) ? 1 : 0);
                    if (result.skippedSnapshots() > 0 && !result.warning().isBlank()) {
                        new AlertDialog.Builder(this)
                                .setTitle("同步完成，但发现旧设备快照")
                                .setMessage("其他可用设备已经正常同步；以下旧/损坏快照已跳过，不再阻塞同步：\n\n"
                                        + result.warning()
                                        + "\n\n如果这些都是之前测试版留下的，可使用下方“清空云端旧测试设备 / 重新建立同步空间”。")
                                .setPositiveButton("知道了", null).show();
                    }
                    if (openDevicesAfter) manageDevices();
                });
            } catch (Exception error) {
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
        }, openDevicesAfter ? "refresh-paired-devices" : "manual-cloud-sync").start();
    }

    private void confirmResetCloudSpace() {
        EditText confirmation = Ui.input(this, "请输入：清空云端");
        confirmation.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("重新建立云端同步空间")
                .setMessage("这会删除当前同步空间中所有设备上传的 .safetydata 云端快照，并清空本机的旧配对设备列表。\n\n不会删除本机检查记录、照片、签名或模板。\n\n适合正式投入使用前清理旧测试版设备。其他仍需使用的正式手机之后再次“立即同步”即可重新加入。")
                .setView(confirmation)
                .setPositiveButton("确认清空", (dialog, which) -> {
                    if (!"清空云端".equals(confirmation.getText().toString().trim())) {
                        Ui.toast(this, "未输入“清空云端”，已取消");
                        return;
                    }
                    resetCloudSpace();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void resetCloudSpace() {
        syncStatus.setText("同步状态：正在重新建立云端同步空间…");
        new Thread(() -> {
            try {
                CloudSyncService.ResetResult result = new CloudSyncService(this).resetCloudSpace(message ->
                        runOnUiThread(() -> syncStatus.setText("同步状态：" + message)));
                runOnUiThread(() -> {
                    deviceRole.setSelection(0);
                    String time = DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(result.completedAt()));
                    syncStatus.setText("同步状态：云端已重建 · " + time + " · 本机角色 管理员");
                    new AlertDialog.Builder(this)
                            .setTitle("云端同步空间已重新建立")
                            .setMessage("已清理 " + result.deletedSnapshots()
                                    + " 个旧设备快照。\n\n本机已成为首位管理员。现在让另一台正式手机使用完全相同的同步空间名称和同步密码点击“立即同步”；随后回到本机点“刷新并管理已配对设备 / 设置角色”，即可看到并管理它。")
                            .setPositiveButton("知道了", null).show();
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName()
                        : error.getMessage();
                runOnUiThread(() -> {
                    syncStatus.setText("同步状态：云端重建失败 · " + message);
                    new AlertDialog.Builder(this).setTitle("云端重建失败")
                            .setMessage(message).setPositiveButton("确定", null).show();
                });
            }
        }, "reset-cloud-space").start();
    }

    private void manageDevices() {'''
replace('app/src/main/java/cn/safetyledger/app/SettingsActivity.java', old_sync, new_sync)

# Make the device list easier to understand and show which row is this phone.
replace('app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''                labels.add(cursor.getString(1) + "\\n" + roleName(cursor.getString(2))
                        + " · 最后同步 " + seen);''',
'''                String currentMark = cursor.getString(0).equals(repo.setting("device_id", ""))
                        ? "（本机）" : "";
                labels.add(cursor.getString(1) + currentMark + "\\n" + roleName(cursor.getString(2))
                        + " · 最后同步 " + seen);''')

print('v1.2.10 patch applied')
