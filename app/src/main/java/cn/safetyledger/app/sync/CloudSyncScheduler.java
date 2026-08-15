package cn.safetyledger.app.sync;

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
