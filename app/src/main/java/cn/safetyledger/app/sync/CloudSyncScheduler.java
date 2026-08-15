package cn.safetyledger.app.sync;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

public final class CloudSyncScheduler {
    public static final int PERIODIC_JOB_ID = 1142026;
    public static final int CHANGE_JOB_ID = 1142027;
    public static final int DEVICE_JOB_ID = 1142028;
    public static final int TRASH_PERIODIC_JOB_ID = 1142029;
    public static final int TRASH_SOON_JOB_ID = 1142030;
    public static final int IMMEDIATE_CONTENT_JOB_ID = 1142031;
    public static final int FOLLOWUP_CONTENT_JOB_ID = 1142032;
    private static final long TWO_HOURS = 2L * 60L * 60L * 1000L;
    private static final long DEVICE_INTERVAL = 30L * 60L * 1000L;
    private static final long TRASH_INTERVAL = 15L * 60L * 1000L;
    private static final long CHANGE_DEBOUNCE = 90L * 1000L;
    private static final long CHANGE_DEADLINE = 5L * 60L * 1000L;
    private static final long IMMEDIATE_LATENCY = 1_000L;
    private static final long IMMEDIATE_DEADLINE = 10_000L;
    private static final long FOLLOWUP_LATENCY = 20_000L;
    private static final long FOLLOWUP_DEADLINE = 60_000L;

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
        JobInfo devices = new JobInfo.Builder(DEVICE_JOB_ID,
                new ComponentName(context, CloudSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(DEVICE_INTERVAL)
                .setPersisted(true)
                .build();
        scheduler.schedule(devices);
        JobInfo trash = new JobInfo.Builder(TRASH_PERIODIC_JOB_ID,
                new ComponentName(context, CloudSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TRASH_INTERVAL)
                .setPersisted(true)
                .build();
        scheduler.schedule(trash);
    }

    public static void scheduleImmediate(Context context) {
        if (CloudSyncService.isContentSyncRunning()) { schedulePeerRefresh(context); return; }
        JobScheduler scheduler=context.getSystemService(JobScheduler.class); if(scheduler==null)return;
        scheduler.schedule(new JobInfo.Builder(IMMEDIATE_CONTENT_JOB_ID,new ComponentName(context,CloudSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(IMMEDIATE_LATENCY)
                .setOverrideDeadline(IMMEDIATE_DEADLINE).build());
    }

    public static void schedulePeerRefresh(Context context) {
        JobScheduler scheduler=context.getSystemService(JobScheduler.class); if(scheduler==null)return;
        scheduler.schedule(new JobInfo.Builder(FOLLOWUP_CONTENT_JOB_ID,new ComponentName(context,CloudSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(FOLLOWUP_LATENCY)
                .setOverrideDeadline(FOLLOWUP_DEADLINE).build());
    }

    public static void scheduleTrashSoon(Context context) {
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);if(scheduler==null)return;
        JobInfo job=new JobInfo.Builder(TRASH_SOON_JOB_ID,new ComponentName(context,CloudSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(2_000L).setOverrideDeadline(30_000L).build();
        scheduler.schedule(job);
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
        scheduler.cancel(DEVICE_JOB_ID);
        scheduler.cancel(TRASH_PERIODIC_JOB_ID);
        scheduler.cancel(TRASH_SOON_JOB_ID);
        scheduler.cancel(IMMEDIATE_CONTENT_JOB_ID);
        scheduler.cancel(FOLLOWUP_CONTENT_JOB_ID);
    }
}
