from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'pattern not found in {path}: {old[:180]!r}')
    p.write_text(text.replace(old, new, count), encoding='utf-8')


# Version bump.
replace('app/build.gradle', "versionCode 13\n        versionName '1.2.10'",
        "versionCode 14\n        versionName '1.2.11'")


# Adaptive scheduler: local changes are debounced into a one-off sync; passive remote polling is much less frequent.
Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncScheduler.java').write_text(r'''package cn.safetyledger.app.sync;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

public final class CloudSyncScheduler {
    public static final int PERIODIC_JOB_ID = 1142026;
    public static final int CHANGE_JOB_ID = 1142027;
    private static final long TWO_HOURS = 2L * 60L * 60L * 1000L;
    private static final long CHANGE_DEBOUNCE = 90L * 1000L;
    private static final long CHANGE_DEADLINE = 5L * 60L * 1000L;

    private CloudSyncScheduler() {}

    public static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        JobInfo periodic = new JobInfo.Builder(PERIODIC_JOB_ID,
                new ComponentName(context, CloudSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TWO_HOURS)
                .setPersisted(true)
                .build();
        scheduler.schedule(periodic);
    }

    /**
     * Debounces multiple edits/photos/signatures into one background upload. Scheduling the same
     * job id replaces the prior pending one, so filling a form does not start many full snapshots.
     */
    public static void scheduleSoon(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        JobInfo change = new JobInfo.Builder(CHANGE_JOB_ID,
                new ComponentName(context, CloudSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(CHANGE_DEBOUNCE)
                .setOverrideDeadline(CHANGE_DEADLINE)
                .setBackoffCriteria(30L * 60L * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        scheduler.schedule(change);
    }

    public static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        scheduler.cancel(PERIODIC_JOB_ID);
        scheduler.cancel(CHANGE_JOB_ID);
    }
}
''', encoding='utf-8')


# Background job: no duplicate retry storm, and a debounced change job is skipped if a manual sync already cleared the queue.
Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncJobService.java').write_text(r'''package cn.safetyledger.app.sync;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.pm.PackageManager;
import android.os.Build;

import cn.safetyledger.app.R;
import cn.safetyledger.app.SafetyLedgerApp;
import cn.safetyledger.app.data.LedgerRepository;

public final class CloudSyncJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        LedgerRepository repo = new LedgerRepository(this);
        try (android.database.Cursor cursor = repo.raw().rawQuery(
                "SELECT 1 FROM sync_providers WHERE enabled=1 LIMIT 1", null)) {
            if (!cursor.moveToFirst()) return false;
        }

        if (params.getJobId() == CloudSyncScheduler.CHANGE_JOB_ID && !hasPendingLocalChanges(repo)) {
            return false;
        }

        new Thread(() -> {
            boolean retry = false;
            try {
                new CloudSyncService(this).syncNow();
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
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }

    private boolean hasPendingLocalChanges(LedgerRepository repo) {
        try (android.database.Cursor cursor = repo.raw().rawQuery(
                "SELECT 1 FROM sync_queue LIMIT 1", null)) {
            return cursor.moveToFirst();
        }
    }

    private void notifyFailure(String message) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        Notification notification = new Notification.Builder(this, SafetyLedgerApp.SYNC_CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("安全检查台账同步失败")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(1001, notification);
    }
}
''', encoding='utf-8')


# Repository: every actual local mutation schedules one debounced sync instead of waiting for a 15-minute full snapshot loop.
replace('app/src/main/java/cn/safetyledger/app/data/LedgerRepository.java',
'''import cn.safetyledger.app.data.Entities.*;\nimport java.time.*;''',
'''import cn.safetyledger.app.data.Entities.*;\nimport cn.safetyledger.app.sync.CloudSyncScheduler;\nimport java.time.*;''')
replace('app/src/main/java/cn/safetyledger/app/data/LedgerRepository.java',
'''public final class LedgerRepository {\n    private final LedgerDatabase helper;\n    public LedgerRepository(Context c){helper=((cn.safetyledger.app.SafetyLedgerApp)c.getApplicationContext()).db();}''',
'''public final class LedgerRepository {\n    private final LedgerDatabase helper;\n    private final Context context;\n    public LedgerRepository(Context c){context=c.getApplicationContext();helper=((cn.safetyledger.app.SafetyLedgerApp)context).db();}''')
replace('app/src/main/java/cn/safetyledger/app/data/LedgerRepository.java',
'''    private void queue(String type,String id,String op){long n=System.currentTimeMillis();raw().insertWithOnConflict("sync_queue",null,LedgerDatabase.values("id",UUID.randomUUID().toString(),"entity_type",type,"entity_id",id,"operation",op,"attempts",0,"next_attempt_at",n,"created_at",n),SQLiteDatabase.CONFLICT_REPLACE);}\n''',
'''    private void queue(String type,String id,String op){long n=System.currentTimeMillis();raw().insertWithOnConflict("sync_queue",null,LedgerDatabase.values("id",UUID.randomUUID().toString(),"entity_type",type,"entity_id",id,"operation",op,"attempts",0,"next_attempt_at",n,"created_at",n),SQLiteDatabase.CONFLICT_REPLACE);CloudSyncScheduler.scheduleSoon(context);}\n    public void queueDeviceRole(String deviceId){queue("sync_device",deviceId,"UPSERT");}\n''')


# Cloud sync: add a cheap device discovery path (PROPFIND only, no record/photo snapshot download).
cloud = Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java')
text = cloud.read_text(encoding='utf-8')
needle = '''    /**\n     * Deletes only device snapshot files in the currently configured sync space. It does not\n'''
insert = r'''    /**
     * Lightweight paired-device discovery. It only reads the WebDAV/R2 device directory and
     * registers unknown snapshot owners locally. It deliberately does not download/decrypt any
     * inspection/photo snapshot, so opening device management stays fast even with large data.
     */
    public DiscoveryResult discoverDevices() throws Exception {
        Config config = null;
        try {
            config = requireConfig();
            WebDavClient client = client(config);
            prepare(client, config);
            String currentId = ensureDeviceId();
            List<String> snapshots = client.listSnapshots(config.space);
            registerCurrentDevice(currentId, snapshots.isEmpty());
            long now = System.currentTimeMillis();
            int remoteDevices = 0;
            for (String name : snapshots) {
                if (!name.endsWith(".safetydata")) continue;
                String id = name.substring(0, name.length() - ".safetydata".length());
                if (id.equals(currentId)) continue;
                registerDiscoveredDevice(id, now);
                remoteDevices++;
            }
            return new DiscoveryResult(remoteDevices, now);
        } finally {
            if (config != null) Arrays.fill(config.spacePassword, '\0');
        }
    }

'''
if needle not in text:
    raise SystemExit('CloudSyncService insertion point not found')
text = text.replace(needle, insert + needle, 1)
helper_needle = '''    private String firstOwner() {\n'''
helper = r'''    private void registerDiscoveredDevice(String deviceId, long now) {
        SQLiteDatabase database = repo.raw();
        String fallbackName = "设备 " + shortDevice(deviceId);
        database.execSQL("INSERT OR IGNORE INTO sync_devices(device_id,display_name,role,first_seen_at,last_seen_at,updated_at) VALUES(?,?,?,?,?,?)",
                new Object[]{deviceId, fallbackName, "FIELD", now, now, now});
        database.execSQL("UPDATE sync_devices SET last_seen_at=? WHERE device_id=?",
                new Object[]{now, deviceId});
    }

'''
if helper_needle not in text:
    raise SystemExit('CloudSyncService helper insertion point not found')
text = text.replace(helper_needle, helper + helper_needle, 1)
text = text.replace(
'''    public record Result(int peerDevices, int changedRows, int skippedSnapshots,\n                         String role, long completedAt, String warning) {}\n    public record ResetResult(int deletedSnapshots, String ownerDeviceId, long completedAt) {}''',
'''    public record Result(int peerDevices, int changedRows, int skippedSnapshots,\n                         String role, long completedAt, String warning) {}\n    public record DiscoveryResult(int remoteDevices, long completedAt) {}\n    public record ResetResult(int deletedSnapshots, String ownerDeviceId, long completedAt) {}''', 1)
cloud.write_text(text, encoding='utf-8')


# Settings UX: device management performs only lightweight discovery; role changes are queued rather than forcing another full sync.
settings = Path('app/src/main/java/cn/safetyledger/app/SettingsActivity.java')
text = settings.read_text(encoding='utf-8')
text = text.replace('Button manage = Ui.secondaryButton(this, "刷新并管理已配对设备 / 设置角色");',
                    'Button manage = Ui.secondaryButton(this, "管理已配对设备 / 快速刷新");', 1)
text = text.replace(
'''        explanation.setTextColor(Ui.MUTED);\n        card.addView(explanation);\n        syncEnabledStatus = Ui.text(this, "云同步：未启用", 14, true);''',
'''        explanation.setTextColor(Ui.MUTED);\n        card.addView(explanation);\n        TextView syncStrategy = Ui.text(this,\n                "自动同步策略：本机记录、照片、签名等有变更后约 2–5 分钟合并后台同步；无本地变更时约每 2 小时检查一次云端。设备列表刷新只读取云端设备目录，不再执行全量记录/照片同步。",\n                12, false);\n        syncStrategy.setTextColor(Ui.MUTED);\n        card.addView(syncStrategy);\n        syncEnabledStatus = Ui.text(this, "云同步：未启用", 14, true);''', 1)
old_refresh = '''    private void refreshAndManageDevices() {\n        syncStatus.setText("同步状态：正在刷新云端设备列表…");\n        runSync(true);\n    }\n'''
new_refresh = '''    private void refreshAndManageDevices() {\n        syncStatus.setText("同步状态：正在快速读取云端设备列表…");\n        new Thread(() -> {\n            try {\n                CloudSyncService.DiscoveryResult result = new CloudSyncService(this).discoverDevices();\n                runOnUiThread(() -> {\n                    String time = DateFormat.getTimeInstance(DateFormat.SHORT)\n                            .format(new Date(result.completedAt()));\n                    syncStatus.setText("同步状态：设备列表已刷新 · " + time\n                            + " · 云端其他设备 " + result.remoteDevices() + " 台 · 未执行全量同步");\n                    manageDevices();\n                });\n            } catch (Exception error) {\n                String message = error.getMessage() == null ? error.getClass().getSimpleName()\n                        : error.getMessage();\n                runOnUiThread(() -> {\n                    syncStatus.setText("同步状态：设备列表刷新失败 · " + message);\n                    new AlertDialog.Builder(this).setTitle("刷新设备列表失败")\n                            .setMessage(message).setPositiveButton("确定", null).show();\n                });\n            }\n        }, "quick-device-discovery").start();\n    }\n'''
if old_refresh not in text:
    raise SystemExit('Settings refresh method not found')
text = text.replace(old_refresh, new_refresh, 1)
text = text.replace(
'''                    Ui.toast(this, "设备已设为" + roleName(role) + "，正在同步角色变更");\n                    syncNow();''',
'''                    repo.queueDeviceRole(deviceId);\n                    Ui.toast(this, "设备已设为" + roleName(role) + "，已加入后台同步队列");''', 1)
text = text.replace('随后回到本机点“刷新并管理已配对设备 / 设置角色”，即可看到并管理它。',
                    '随后回到本机点“管理已配对设备 / 快速刷新”，即可看到并管理它。', 1)
settings.write_text(text, encoding='utf-8')

print('v1.2.11 sync efficiency patch applied')
