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
        // 冷进程拉起: 必须先初始化运行时, 否则 Storage.appSettings (lazy 读 AppRuntime.context)
        // 会在第二次访问时抛 UninitializedPropertyAccessException 崩掉进程 (与本服务同款修法)。
        // bundleState 的 initialValue 已同步读盘, 无需再显式 loadBundle()。
        AppRuntime.init(applicationContext)
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
