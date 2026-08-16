package cn.safetyledger.app.holiday;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

public final class HolidaySyncScheduler {
    private static final int JOB_ID = 1142040;
    private static final long ONE_DAY = 24L * 60L * 60L * 1000L;

    private HolidaySyncScheduler() {}

    public static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, HolidaySyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(ONE_DAY)
                .setPersisted(true)
                .build();
        scheduler.schedule(job);
    }
}
