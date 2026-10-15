package io.github.miuzarte.scrcpyforandroid.services.keepalive

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.storage.Storage

/**
 * KeepAliveJobService
 *
 * Periodic (>= 15 min) recovery check:
 * - session active but the foreground service is not running -> start it again;
 * - an abnormal exit is pending (crash-recovery `active` and not user-removed) -> start the
 *   foreground service, whose onStartCommand performs the actual recovery (exit-reason gate,
 *   attempt cap, relaunch). This is the slow backstop when the system does not restart the
 *   sticky service; the fastest path is the system's own START_STICKY restart.
 *
 * The job is scheduled by [ProcessKeepAliveScheduler] and persisted across reboots.
 */
class KeepAliveJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        AppRuntime.init(applicationContext)
        // Cold process resurrection: the settings StateFlow still holds defaults until loaded.
        runCatching { kotlinx.coroutines.runBlocking { Storage.appSettings.loadBundle() } }
            .onFailure { Log.w(TAG, "job: settings unavailable", it) }
        val enabled = Storage.appSettings.bundleState.value.processKeepAliveEnabled
        val sessionActive = AppRuntime.scrcpy?.isStarted() == true
        val prefs = getSharedPreferences(RecoveryPrefs.FILE, MODE_PRIVATE)
        val recoveryPending = prefs.getBoolean(RecoveryPrefs.ACTIVE, false) &&
            !prefs.getBoolean(RecoveryPrefs.TASK_REMOVED, false) &&
            Storage.appSettings.bundleState.value.crashRecoveryEnabled
        Log.i(
            TAG,
            "job run: enabled=$enabled sessionActive=$sessionActive " +
                "fgs=${ProcessKeepAliveService.running} recoveryPending=$recoveryPending",
        )
        if (ProcessKeepAliveRules.shouldRunService(enabled, sessionActive, recoveryPending) &&
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
