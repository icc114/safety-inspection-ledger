package cn.safetyledger.app.sync;

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
