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
        try (android.database.Cursor cursor = new LedgerRepository(this).raw().rawQuery(
                "SELECT 1 FROM sync_providers WHERE enabled=1 LIMIT 1", null)) {
            if (!cursor.moveToFirst()) {
                return false;
            }
        }
        new Thread(() -> {
            boolean retry = false;
            try {
                new CloudSyncService(this).syncNow();
            } catch (Exception error) {
                retry = true;
                String message = error.getMessage() == null ? error.getClass().getSimpleName()
                        : error.getMessage();
                new LedgerRepository(this).putSetting("last_sync_error", message);
                notifyFailure(message);
            }
            jobFinished(params, retry);
        }, "safety-ledger-cloud-sync").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }

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
