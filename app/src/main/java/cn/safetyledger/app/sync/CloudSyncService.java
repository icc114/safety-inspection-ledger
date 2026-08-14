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

/**
 * Provider-neutral snapshot synchronization. Every device owns one encrypted snapshot;
 * a sync downloads and merges all peer snapshots before uploading its new aggregate view.
 */
public final class CloudSyncService {
    private final Context context;
    private final LedgerRepository repo;

    public CloudSyncService(Context context) {
        this.context = context.getApplicationContext();
        this.repo = new LedgerRepository(this.context);
    }

    public Result syncNow() throws Exception {
        Config config = loadConfig();
        if (config == null) throw new IllegalStateException("请先保存并启用云同步配置");
        if (!(config.type.contains("WebDAV") || "Cloudflare".equals(config.type)
                || "自定义 HTTP 服务器".equals(config.type))) {
            throw new IllegalStateException(config.type + " 尚未接入授权协议；当前可真实同步 WebDAV、飞牛 WebDAV及兼容 WebDAV 的 Cloudflare Worker");
        }
        if (config.spacePassword.length < 8) {
            throw new IllegalStateException("同步空间密码至少 8 位，请重新保存配置");
        }

        WebDavClient client = new WebDavClient(config.endpoint, config.username,
                config.serverPassword, config.token,
                "Cloudflare".equals(config.type) ? config.space : "",
                "Cloudflare".equals(config.type) ? new String(config.spacePassword) : "");
        SyncProvider.ConnectionResult probe = client.testReadWrite(config.space);
        if (!probe.success()) {
            String message = probe.message();
            if ("Cloudflare".equals(config.type)) {
                if (message.contains("需要设备授权") || message.contains("HTTP 401")) {
                    message = "Cloudflare 自动配对被拒绝：当前地址不是本版兼容网关，或仍使用旧私有授权协议。请重新部署仓库 cloudflare-worker；若云端提供设备 Token，也可在高级认证中填写。原始响应："
                            + message;
                } else if (message.contains("HTTP 404") || message.contains("HTTP 405")
                        || message.contains("HTTP 500") || message.contains("HTTP 503")
                        || message.contains("不是可读的 WebDAV")) {
                    message = "Cloudflare 服务与当前 APK 协议不匹配。1.2.6 需要仓库 cloudflare-worker 的 WebDAV 兼容 Worker，并绑定私有 R2 为 SAFETY_LEDGER_BUCKET；旧版 D1/env.DB Worker 不能直接使用。原始响应："
                            + message;
                }
            }
            throw new java.io.IOException(message);
        }

        String deviceId = repo.setting("device_id", "");
        if (deviceId.isBlank()) {
            deviceId = UUID.randomUUID().toString();
            repo.putSetting("device_id", deviceId);
        }
        List<String> snapshots = client.listSnapshots(config.space);
        int peers = 0;
        int changed = 0;
        BackupService backup = new BackupService(context);
        for (String name : snapshots) {
            if (name.equals(deviceId + ".safetydata")) continue;
            File remote = File.createTempFile("safety-cloud-in-", ".safetydata", context.getCacheDir());
            try {
                client.download(config.space, name, remote);
                try (FileInputStream input = new FileInputStream(remote)) {
                    BackupService.RestorePackage restore = backup.decryptAndValidate(input,
                            config.spacePassword.clone());
                    changed += backup.mergeRestore(restore);
                }
                peers++;
            } finally {
                remote.delete();
            }
        }

        registerCurrentDevice(deviceId, snapshots.isEmpty());
        applyTombstones();

        File outgoing = File.createTempFile("safety-cloud-out-", ".safetydata", context.getCacheDir());
        try {
            try (FileOutputStream output = new FileOutputStream(outgoing)) {
                backup.exportData(output, config.spacePassword.clone());
            }
            client.upload(config.space, deviceId + ".safetydata", outgoing);
        } finally {
            outgoing.delete();
            Arrays.fill(config.spacePassword, '\0');
        }

        long now = System.currentTimeMillis();
        repo.raw().execSQL("DELETE FROM sync_queue");
        repo.raw().execSQL("UPDATE tombstones SET synced_at=? WHERE synced_at IS NULL",
                new Object[]{now});
        repo.putSetting("last_sync_at", String.valueOf(now));
        repo.putSetting("last_sync_error", "");
        return new Result(peers, changed, deviceRole(deviceId), now);
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

    public record Result(int peerDevices, int changedRows, String role, long completedAt) {}

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
