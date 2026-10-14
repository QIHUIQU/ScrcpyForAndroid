package io.github.miuzarte.scrcpyforandroid.services.keepalive

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.storage.Storage

/**
 * KeepAliveJobService
 *
 * Periodic (>= 15 min) recovery check: if keep-alive is enabled and a session is active
 * but the foreground service is not running, start it again. The job is scheduled by
 * [ProcessKeepAliveScheduler] and persisted across reboots.
 */
class KeepAliveJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val enabled = Storage.appSettings.bundleState.value.processKeepAliveEnabled
        val sessionActive = AppRuntime.scrcpy?.isStarted() == true
        Log.i(
            TAG,
            "job run: enabled=$enabled sessionActive=$sessionActive fgs=${ProcessKeepAliveService.running}",
        )
        if (ProcessKeepAliveRules.shouldRunService(enabled, sessionActive) &&
            !ProcessKeepAliveService.running
        ) {
            runCatching { ProcessKeepAliveService.start(this) }
                .onFailure { Log.w(TAG, "job: FGS start blocked", it) }
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    private companion object {
        private const val TAG = "KeepAliveJob"
    }
}
