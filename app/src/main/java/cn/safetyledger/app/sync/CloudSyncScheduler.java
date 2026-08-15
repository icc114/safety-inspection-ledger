package cn.safetyledger.app.sync;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

public final class CloudSyncScheduler {
    private static final int JOB_ID = 1142026;
    private CloudSyncScheduler() {}

    public static void schedule(Context context) {
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, CloudSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }

    public static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(JOB_ID);
    }
}
