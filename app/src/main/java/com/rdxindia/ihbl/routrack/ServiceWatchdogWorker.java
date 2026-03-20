package com.rdxindia.ihbl.routrack;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * @deprecated Moved to {@link com.rdxindia.ihbl.routrack.workers.ServiceWatchdogWorker}.
 * This stub prevents WorkManager from crashing on stale persisted jobs that
 * still reference the old class name. Safe to delete once WorkManager has
 * cleared all old "ServiceWatchdog" entries (typically after first run post-update).
 */
@Deprecated
public class ServiceWatchdogWorker extends Worker {

    public ServiceWatchdogWorker(@NonNull android.content.Context context,
                                  @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        return Result.success(); // no-op — real logic is in workers.ServiceWatchdogWorker
    }
}
