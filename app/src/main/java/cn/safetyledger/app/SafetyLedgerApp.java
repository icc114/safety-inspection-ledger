package cn.safetyledger.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import cn.safetyledger.app.data.LedgerDatabase;

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
    }
    public LedgerDatabase db() { return database; }
}
