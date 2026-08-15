package cn.safetyledger.app.sync;

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

import org.json.JSONObject;

/**
 * Provider-neutral snapshot synchronization. Every device owns one encrypted snapshot;
 * a sync downloads and merges all peer snapshots before uploading its new aggregate view.
 */
public final class CloudSyncService {
    private static final AtomicBoolean CONTENT_RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean DEVICE_RUNNING = new AtomicBoolean(false);

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
        if (!CONTENT_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("检查内容同步正在运行，请等待当前同步完成后再试");
        }
        Config config = null;
        try {
            config = requireConfig();
            progress(listener, "正在连接云端…");
            WebDavClient client = client(config);
            prepare(client, config);

            String deviceId = ensureDeviceId();
            if (client.isDeviceLoggedOut(config.space, deviceId)) {
                return finishForcedLogout(client, config, deviceId, listener);
            }
            progress(listener, "正在读取其他设备的检查内容…");
            List<String> snapshots = client.listSnapshots(config.space);
            boolean emptyCloud = snapshots.isEmpty();

            // Register locally before touching peer snapshots. A new device joining a non-empty
            // space is FIELD until an existing OWNER/ADMIN explicitly promotes it.
            registerCurrentDevice(deviceId, emptyCloud);

            // Publish presence first. Previously a bad/stale peer snapshot could fail before the
            // new device ever uploaded anything, so the administrator could never see/manage it.
            BackupService backup = new BackupService(context);
            progress(listener, "正在准备本机检查内容…");
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
                } catch (Throwable peerError) {
                    skipped++;
                    String detail = peerError instanceof OutOfMemoryError
                            ? "旧版云端快照过大，已跳过；请将该设备升级到 1.2.15 后重新同步"
                            : readable(peerError);
                    if (detail.length() > 90) detail = detail.substring(0, 90) + "…";
                    warnings.add(shortDevice(name) + "：" + detail);
                } finally {
                    remote.delete();
                }
            }

            // Merged role data may contain an administrator's change for this device.
            registerCurrentDevice(deviceId, emptyCloud);
            if (client.isDeviceLoggedOut(config.space, deviceId)
                    || "LOGGED_OUT".equals(deviceRole(deviceId))) {
                return finishForcedLogout(client, config, deviceId, listener);
            }
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
            CONTENT_RUNNING.set(false);
        }
    }

    /**
     * Independent device-management synchronization. Only small JSON metadata and logout markers
     * are transferred; no inspection database, photo, signature or .safetydata file is touched.
     */
    public DiscoveryResult syncDeviceManagement() throws Exception {
        if (!DEVICE_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("设备信息同步正在运行，请稍后再试");
        }
        Config config = null;
        try {
            config = requireConfig();
            WebDavClient client = client(config);
            prepare(client, config);
            String currentId = ensureDeviceId();
            if (client.isDeviceLoggedOut(config.space, currentId)) {
                disableLocalSync(true, currentId);
                long now = System.currentTimeMillis();
                repo.raw().execSQL("UPDATE sync_devices SET role='LOGGED_OUT',updated_at=? WHERE device_id=?",
                        new Object[]{now, currentId});
                return new DiscoveryResult(0, now, "LOGGED_OUT");
            }

            List<String> profiles = client.listDeviceProfiles(config.space);
            boolean ownProfile = profiles.contains(currentId + ".device.json");
            long now = System.currentTimeMillis();

            // Pull authoritative role/name metadata first so this device cannot overwrite an
            // administrator role change with stale local state.
            for (String file : profiles) {
                String id = file.substring(0, file.length() - ".device.json".length());
                try {
                    JSONObject json = new JSONObject(client.downloadDeviceProfile(config.space, id));
                    applyDeviceProfile(id, json, now);
                } catch (Exception ignored) {
                    // One malformed old control file must not block the rest of device management.
                }
            }

            if (!ownProfile && deviceRole(currentId) == null) {
                registerCurrentDevice(currentId, profiles.isEmpty());
            } else if (deviceRole(currentId) == null) {
                registerCurrentDevice(currentId, false);
            }
            String role = deviceRole(currentId);
            if ("LOGGED_OUT".equals(role)) {
                disableLocalSync(true, currentId);
                return new DiscoveryResult(Math.max(0, profiles.size() - 1), now, role);
            }

            // Publish only this phone's presence/name and its already-authorized role.
            String name = repo.setting("device_name", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            client.uploadDeviceProfile(config.space, currentId,
                    deviceProfileJson(currentId, name, role == null ? "FIELD" : role, now));
            repo.raw().execSQL("UPDATE sync_devices SET display_name=?,last_seen_at=?,updated_at=? WHERE device_id=?",
                    new Object[]{name, now, now, currentId});
            updateLocalRoleSettings(currentId);
            repo.putSetting("last_device_sync_at", String.valueOf(now));
            repo.putSetting("last_device_sync_error", "");

            int remote = 0;
            try (Cursor cursor = repo.raw().rawQuery("SELECT count(*) FROM sync_devices WHERE device_id<>?",
                    new String[]{currentId})) { if (cursor.moveToFirst()) remote = cursor.getInt(0); }
            return new DiscoveryResult(remote, now, deviceRole(currentId));
        } finally {
            if (config != null) Arrays.fill(config.spacePassword, '\0');
            DEVICE_RUNNING.set(false);
        }
    }

    /** Backward-compatible name used by the settings screen. */
    public DiscoveryResult discoverDevices() throws Exception { return syncDeviceManagement(); }

    public DeviceRoleResult updateDeviceRole(String targetDeviceId, String role) throws Exception {
        if (!("ADMIN".equals(role) || "FIELD".equals(role))) throw new IllegalArgumentException("设备角色无效");
        if (!DEVICE_RUNNING.compareAndSet(false, true)) throw new IllegalStateException("设备信息同步正在运行，请稍后再试");
        Config config = null;
        try {
            config = requireConfig(); WebDavClient client = client(config); prepare(client, config);
            String currentId = ensureDeviceId(); String currentRole = deviceRole(currentId);
            if (!("OWNER".equals(currentRole) || "ADMIN".equals(currentRole))) throw new SecurityException("只有管理员可以修改设备角色");
            if (targetDeviceId == null || targetDeviceId.isBlank() || targetDeviceId.equals(currentId)) throw new IllegalArgumentException("请选择其他设备");
            String targetRole = deviceRole(targetDeviceId);
            if ("OWNER".equals(targetRole)) throw new SecurityException("首位管理员不能降级");
            String name = deviceName(targetDeviceId); long now = System.currentTimeMillis();
            repo.raw().execSQL("UPDATE sync_devices SET role=?,updated_at=? WHERE device_id=?", new Object[]{role, now, targetDeviceId});
            client.uploadDeviceProfile(config.space, targetDeviceId, deviceProfileJson(targetDeviceId, name, role, now));
            return new DeviceRoleResult(targetDeviceId, role, now);
        } finally {
            if (config != null) Arrays.fill(config.spacePassword, '\0');
            DEVICE_RUNNING.set(false);
        }
    }

    /**
     * Administrator logout for another device. A tiny .logout marker is written first and the
     * target snapshot is removed. The target app checks the marker before every upload and clears
     * only its cloud credentials; local inspection records/photos remain untouched.
     */
    public DeviceLogoutResult logoutDevice(String targetDeviceId) throws Exception {
        if (!DEVICE_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("设备信息同步正在运行，请稍后再试");
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
            client.uploadDeviceProfile(config.space, targetDeviceId,
                    deviceProfileJson(targetDeviceId, deviceName(targetDeviceId), "LOGGED_OUT", now));
            return new DeviceLogoutResult(targetDeviceId, now);
        } finally {
            if (config != null) Arrays.fill(config.spacePassword, '\0');
            DEVICE_RUNNING.set(false);
        }
    }

    /** Re-enable a previously logged-out device. This is intentionally explicit. */
    public DeviceLogoutResult allowDeviceRejoin(String targetDeviceId) throws Exception {
        if (!DEVICE_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("设备信息同步正在运行，请稍后再试");
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
            client.uploadDeviceProfile(config.space, targetDeviceId,
                    deviceProfileJson(targetDeviceId, deviceName(targetDeviceId), "FIELD", now));
            return new DeviceLogoutResult(targetDeviceId, now);
        } finally {
            if (config != null) Arrays.fill(config.spacePassword, '\0');
            DEVICE_RUNNING.set(false);
        }
    }

    /** Voluntary logout of the current phone. Local business data is preserved. */
    public CurrentLogoutResult logoutCurrentDevice() throws Exception {
        if (!DEVICE_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("设备信息同步正在运行，请稍后再试");
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
            client.deleteDeviceProfile(config.space, deviceId);
            disableLocalSync(false, deviceId);
            long now = System.currentTimeMillis();
            return new CurrentLogoutResult(deviceId, now);
        } finally {
            if (config != null) Arrays.fill(config.spacePassword, '\0');
            DEVICE_RUNNING.set(false);
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

    /**
     * Deletes only device snapshot files in the currently configured sync space. It does not
     * delete any local inspection record/photo. The current phone then becomes the first owner
     * and uploads a clean snapshot. This is intentionally explicit for retiring test devices.
     */
    public ResetResult resetCloudSpace(ProgressListener listener) throws Exception {
        if (!CONTENT_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("检查内容同步正在运行，请等待完成后再重建云端");
        }
        if (!DEVICE_RUNNING.compareAndSet(false, true)) {
            CONTENT_RUNNING.set(false);
            throw new IllegalStateException("设备信息同步正在运行，请等待完成后再重建云端");
        }
        Config config = null;
        try {
            config = requireConfig();
            progress(listener, "正在连接云端…");
            WebDavClient client = client(config);
            prepare(client, config);
            progress(listener, "正在读取旧设备快照…");
            List<String> snapshots = client.listSnapshots(config.space);
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
            database.delete("sync_devices", null, null);
            repo.putSetting("cloud_role", "");
            repo.putSetting("device_role", "PRIMARY");
            repo.putSetting("last_sync_error", "");
            repo.putSetting("last_sync_warning", "");

            String deviceId = ensureDeviceId();
            registerCurrentDevice(deviceId, true);
            progress(listener, "正在建立新的管理员设备…");
            long now = System.currentTimeMillis();
            client.uploadDeviceProfile(config.space, deviceId,
                    deviceProfileJson(deviceId, deviceName(deviceId), "OWNER", now));
            progress(listener, "正在上传本机检查内容…");
            uploadSnapshot(new BackupService(context), client, config, deviceId);
            repo.putSetting("last_sync_at", String.valueOf(now));
            progress(listener, "云端同步空间已重新建立");
            return new ResetResult(deleted, deviceId, now);
        } finally {
            if (config != null) Arrays.fill(config.spacePassword, '\0');
            DEVICE_RUNNING.set(false);
            CONTENT_RUNNING.set(false);
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
                backup.exportCloudSnapshot(output, config.spacePassword.clone());
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
        updateLocalRoleSettings(deviceId);
    }

    private void registerDiscoveredDevice(String deviceId, long now) {
        SQLiteDatabase database = repo.raw();
        String fallbackName = "设备 " + shortDevice(deviceId);
        database.execSQL("INSERT OR IGNORE INTO sync_devices(device_id,display_name,role,first_seen_at,last_seen_at,updated_at) VALUES(?,?,?,?,?,?)",
                new Object[]{deviceId, fallbackName, "FIELD", now, now, now});
        database.execSQL("UPDATE sync_devices SET last_seen_at=? WHERE device_id=?",
                new Object[]{now, deviceId});
    }

    private void applyDeviceProfile(String deviceId, JSONObject json, long now) {
        String name = json.optString("displayName", "设备 " + shortDevice(deviceId));
        String role = json.optString("role", "FIELD");
        if (!("OWNER".equals(role) || "ADMIN".equals(role) || "FIELD".equals(role) || "LOGGED_OUT".equals(role))) role = "FIELD";
        long seen = json.optLong("lastSeenAt", now);
        long updated = json.optLong("updatedAt", seen);
        repo.raw().execSQL("INSERT OR IGNORE INTO sync_devices(device_id,display_name,role,first_seen_at,last_seen_at,updated_at) VALUES(?,?,?,?,?,?)",
                new Object[]{deviceId, name, role, now, seen, updated});
        repo.raw().execSQL("UPDATE sync_devices SET display_name=?,role=?,last_seen_at=?,updated_at=? WHERE device_id=?",
                new Object[]{name, role, seen, updated, deviceId});
        if (deviceId.equals(repo.setting("device_id", ""))) updateLocalRoleSettings(deviceId);
    }

    private String deviceProfileJson(String deviceId, String name, String role, long now) throws Exception {
        return new JSONObject().put("version", 1).put("deviceId", deviceId)
                .put("displayName", name == null ? "" : name).put("role", role)
                .put("lastSeenAt", now).put("updatedAt", now).toString();
    }

    private String deviceName(String deviceId) {
        try (Cursor cursor = repo.raw().rawQuery("SELECT display_name FROM sync_devices WHERE device_id=?", new String[]{deviceId})) {
            return cursor.moveToFirst() ? cursor.getString(0) : "设备 " + shortDevice(deviceId);
        }
    }

    private void updateLocalRoleSettings(String deviceId) {
        String role = deviceRole(deviceId);
        repo.putSetting("cloud_role", role == null ? "FIELD" : role);
        repo.putSetting("device_role", "OWNER".equals(role) || "ADMIN".equals(role) ? "PRIMARY" : "FIELD");
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
    public record DiscoveryResult(int remoteDevices, long completedAt, String role) {}
    public record DeviceRoleResult(String deviceId, String role, long completedAt) {}
    public record DeviceLogoutResult(String deviceId, long completedAt) {}
    public record CurrentLogoutResult(String deviceId, long completedAt) {}
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
