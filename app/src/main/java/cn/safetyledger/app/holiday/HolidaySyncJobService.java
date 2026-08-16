package cn.safetyledger.app.holiday;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.time.LocalDate;

public final class HolidaySyncJobService extends JobService {
    private volatile boolean stopped;

    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                int year = LocalDate.now().getYear();
                if (!stopped) HolidaySyncService.syncYear(this, year, false);
                if (!stopped) HolidaySyncService.syncYear(this, year + 1, false);
            } catch (Exception ignored) {
                // Keep the local cache; background holiday refresh must never break offline use.
            } finally {
                jobFinished(params, false);
            }
        }, "holiday-sync-job").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        stopped = true;
        return true;
    }
}
