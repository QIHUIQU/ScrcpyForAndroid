package io.github.miuzarte.scrcpyforandroid.services.keepalive

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.miuzarte.scrcpyforandroid.MainActivity
import io.github.miuzarte.scrcpyforandroid.R
import io.github.miuzarte.scrcpyforandroid.services.recovery.CrashRecoveryAccessibilityService
import io.github.miuzarte.scrcpyforandroid.storage.Storage

/**
 * Pure decision for whether an abnormal-exit recovery launch is due (JVM-testable, no Android
 * calls). `reason` uses the ApplicationExitInfo constants (compile-time ints).
 */
internal object CrashRecoveryPolicy {
    const val MAX_ATTEMPTS = 3
    const val RECENT_WINDOW_MS = 600_000L

    fun isAbnormalExit(reason: Int): Boolean =
        reason == ApplicationExitInfo.REASON_CRASH ||
            reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            reason == ApplicationExitInfo.REASON_ANR ||
            reason == ApplicationExitInfo.REASON_LOW_MEMORY

    /**
     * A relaunch is due only for a fresh, not-yet-handled abnormal exit that is still under the
     * attempt cap. Time alone never relaunches; a handled exit never repeats.
     */
    fun shouldAttempt(
        reason: Int,
        exitTimestampMs: Long,
        lastHandledExitMs: Long,
        nowMs: Long,
        recentAttemptCount: Int,
    ): Boolean =
        isAbnormalExit(reason) &&
            exitTimestampMs > lastHandledExitMs &&
            nowMs - exitTimestampMs in 0L..RECENT_WINDOW_MS &&
            recentAttemptCount < MAX_ATTEMPTS
}

/**
 * CrashRecovery: the single decision + relaunch entry reused by every trigger.
 *
 * Trigger sources (the difference is only *what still exists after the app process dies*):
 *  - [CrashRecoveryAccessibilityService] — primary. The system binds an enabled accessibility
 *    service and re-binds it after a crash, so `onServiceConnected` fires in the freshly started
 *    process even though MainActivity was gone. It also holds background-activity-launch
 *    privileges (the app is bound by a system service granted START_ACTIVITIES_FROM_BACKGROUND),
 *    so the relaunch is not silently BAL-blocked.
 *  - [ProcessKeepAliveService] — sticky foreground-service restart backstop.
 *  - [KeepAliveJobService] — periodic backstop (starts the FGS, which lands here).
 *
 * Dispatch, foreground arrival, session start and streaming are logged as separate stages; a
 * returned `startActivity` is never reported as a successful return to remote control.
 */
internal object CrashRecovery {
    private const val TAG = "CrashRecovery"
    private const val ARRIVAL_CHECK_MS = 3_000L
    private const val NOTIFICATION_ID = 0xEC03

    /** Returns true only when an automatic relaunch was actually dispatched. */
    fun tryLaunch(context: Context, source: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val prefs = context.getSharedPreferences(RecoveryPrefs.FILE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(RecoveryPrefs.ACTIVE, false) ||
            prefs.getBoolean(RecoveryPrefs.TASK_REMOVED, false)
        ) return false
        if (!Storage.appSettings.bundleState.value.crashRecoveryEnabled) return false

        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
            ?: return false
        val now = System.currentTimeMillis()
        val recent = RecoveryPolicy.recentAttempts(
            now, prefs.getString(RecoveryPrefs.ATTEMPTS, "").orEmpty(),
        )
        val due = CrashRecoveryPolicy.shouldAttempt(
            reason = exit.reason,
            exitTimestampMs = exit.timestamp,
            lastHandledExitMs = prefs.getLong(RecoveryPrefs.HANDLED_EXIT, 0L),
            nowMs = now,
            recentAttemptCount = recent.size,
        )
        if (!due) {
            if (CrashRecoveryPolicy.isAbnormalExit(exit.reason) &&
                recent.size >= CrashRecoveryPolicy.MAX_ATTEMPTS
            ) {
                Log.w(TAG, "recovery suppressed: ${CrashRecoveryPolicy.MAX_ATTEMPTS} attempts in 10 minutes")
            }
            return false
        }

        prefs.edit()
            .putLong(RecoveryPrefs.HANDLED_EXIT, exit.timestamp)
            .putString(RecoveryPrefs.ATTEMPTS, (recent + now).joinToString(","))
            .apply()
        Log.i(
            TAG,
            "recovery attempt ${recent.size + 1}/${CrashRecoveryPolicy.MAX_ATTEMPTS} " +
                "source=$source reason=${exit.reason}",
        )

        // 无障碍已启用 ⇒ 主机制: 自动拉起; 未启用/未授权 ⇒ 如实降级为可点通知, 不谎称已自动回远控。
        if (CrashRecoveryAccessibilityService.isEnabled(context)) {
            return dispatchAutoRelaunch(context, prefs, source)
        }
        val posted = postFallbackNotification(context)
        Log.w(
            TAG,
            "recovery degraded (source=$source): accessibility not enabled; " +
                "auto relaunch skipped, notification_posted=$posted",
        )
        return false
    }

    private fun dispatchAutoRelaunch(
        context: Context,
        prefs: SharedPreferences,
        source: String,
    ): Boolean {
        // 只有真的要拉起才置 launch_pending: 3 秒内未被 MainActivity 消费判定"未观察到到达"。
        prefs.edit().putBoolean(RecoveryPrefs.LAUNCH_PENDING, true).apply()
        val dispatched = runCatching {
            context.startActivity(recoveryActivityIntent(context))
        }.onFailure {
            Log.w(TAG, "recovery launch failed; tap notification", it)
        }.isSuccess
        if (!dispatched) {
            postFallbackNotification(context)
            return false
        }
        Log.i(TAG, "recovery launch dispatched (source=$source); awaiting MainActivity arrival")
        Handler(context.mainLooper).postDelayed({
            if (prefs.getBoolean(RecoveryPrefs.LAUNCH_PENDING, false)) {
                // 仅说明"未观察到到达", 不擅断就是 BAL; 给可点通知兜底。
                Log.w(TAG, "recovery MainActivity arrival not observed; posting notification fallback")
                postFallbackNotification(context)
            } else {
                Log.i(TAG, "recovery MainActivity arrival observed")
            }
        }, ARRIVAL_CHECK_MS)
        return true
    }

    /** 未授权/未到达时的真实降级入口: 一篇可点通知; 不可见时如实记录, 不谎报。 */
    fun postFallbackNotification(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        ensureChannel(context, manager)
        val notification = NotificationCompat.Builder(context, ProcessKeepAliveService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.recovery_notification_title))
            .setContentText(context.getString(R.string.recovery_notification_text))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, NOTIFICATION_ID, recoveryActivityIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        val visible = manager.areNotificationsEnabled()
        val posted = runCatching { manager.notify(NOTIFICATION_ID, notification) }.isSuccess
        Log.i(TAG, "recovery notification post=$posted visible=$visible")
        return posted
    }

    private fun recoveryActivityIntent(context: Context) =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(ProcessKeepAliveService.EXTRA_RECOVER_SESSION, true)

    /** 用户主动划掉 ⇒ 清除恢复目标并禁用自动拉起 (所有触发源共用)。 */
    fun onUserSwipedAway(context: Context) {
        RecoveryIntent.clear(context)
        context.getSharedPreferences(RecoveryPrefs.FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(RecoveryPrefs.TASK_REMOVED, true)
            .putBoolean(RecoveryPrefs.ACTIVE, false)
            .apply()
        Log.i(TAG, "task removed by user: recovery disabled until next manual launch")
    }

    fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(ProcessKeepAliveService.CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                ProcessKeepAliveService.CHANNEL_ID,
                context.getString(R.string.keepalive_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
}
