from pathlib import Path

# Patch generated CloudSyncService source after apply_v1218_sync_trash.py.
p=Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java')
t=p.read_text(encoding='utf-8')
t=t.replace('meta.put("inspectionType",inspection.inspectionType)','meta.put("inspectionType",inspection.type)')
p.write_text(t,encoding='utf-8')

# Keep periodic trash polling and immediate trash sync as separate JobScheduler IDs.
p=Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncScheduler.java')
t=p.read_text(encoding='utf-8')
t=t.replace('public static final int TRASH_JOB_ID = 1142029;',
'''public static final int TRASH_PERIODIC_JOB_ID = 1142029;
    public static final int TRASH_SOON_JOB_ID = 1142030;''')
t=t.replace('new JobInfo.Builder(TRASH_JOB_ID,', 'new JobInfo.Builder(TRASH_PERIODIC_JOB_ID,', 1)
# The second builder is scheduleTrashSoon; make it distinct.
needle='JobInfo job=new JobInfo.Builder(TRASH_PERIODIC_JOB_ID,new ComponentName(context,CloudSyncJobService.class))'
if needle in t:
    t=t.replace(needle,'JobInfo job=new JobInfo.Builder(TRASH_SOON_JOB_ID,new ComponentName(context,CloudSyncJobService.class))',1)
else:
    t=t.replace('JobInfo job=new JobInfo.Builder(TRASH_JOB_ID,new ComponentName(context,CloudSyncJobService.class))',
                'JobInfo job=new JobInfo.Builder(TRASH_SOON_JOB_ID,new ComponentName(context,CloudSyncJobService.class))',1)
t=t.replace('scheduler.cancel(TRASH_JOB_ID);', 'scheduler.cancel(TRASH_PERIODIC_JOB_ID);\n        scheduler.cancel(TRASH_SOON_JOB_ID);')
p.write_text(t,encoding='utf-8')

p=Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncJobService.java')
t=p.read_text(encoding='utf-8')
t=t.replace('boolean trashJob = params.getJobId() == CloudSyncScheduler.TRASH_JOB_ID;',
'''boolean trashJob = params.getJobId() == CloudSyncScheduler.TRASH_PERIODIC_JOB_ID
                    || params.getJobId() == CloudSyncScheduler.TRASH_SOON_JOB_ID;''')
t=t.replace('params.getJobId() == CloudSyncScheduler.TRASH_JOB_ID ? "safety-ledger-trash-sync"',
            'trashJob ? "safety-ledger-trash-sync"')
# OOM branch still used the old notify signature in first CI.
t=t.replace('''                repo.putSetting(deviceJob ? "last_device_sync_error" : "last_sync_error", message);
                if (!deviceJob) notifyFailure(message);''',
'''                repo.putSetting(deviceJob ? "last_device_sync_error" : trashJob ? "last_trash_sync_error" : "last_sync_error", message);
                if (!deviceJob && !trashJob) notifyFailure(error, message);''')
p.write_text(t,encoding='utf-8')

print('Fixed Android 1.2.18 compile issues and split trash scheduler IDs')
