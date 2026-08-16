package cn.safetyledger.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import cn.safetyledger.app.data.LedgerDatabase;
import cn.safetyledger.app.sync.CloudSyncScheduler;
import cn.safetyledger.app.holiday.HolidaySyncScheduler;
import cn.safetyledger.app.holiday.HolidaySyncService;
import java.time.LocalDate;

public final class SafetyLedgerApp extends Application {
    public static final String SYNC_CHANNEL = "sync_failures";
    private LedgerDatabase database;

    @Override public void onCreate() {
        super.onCreate();
        database = new LedgerDatabase(this);
        database.getWritableDatabase();
        NotificationChannel c = new NotificationChannel(SYNC_CHANNEL, "同步失败", NotificationManager.IMPORTANCE_DEFAULT);
        c.setDescription("云同步连接或传输失败通知");
        getSystemService(NotificationManager.class).createNotificationChannel(c);
        CloudSyncScheduler.schedule(this);
        HolidaySyncScheduler.schedule(this);
        int year = LocalDate.now().getYear();
        HolidaySyncService.syncYearAsync(this, year, null);
        HolidaySyncService.syncYearAsync(this, year + 1, null);
    }
    public LedgerDatabase db() { return database; }
}
