package io.github.miuzarte.scrcpyforandroid.services.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.miuzarte.scrcpyforandroid.MainActivity
import io.github.miuzarte.scrcpyforandroid.R
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.storage.Storage

internal object RecoveryPolicy {
    fun recentAttempts(now: Long, encoded: String): List<Long> = encoded.split(',')
        .mapNotNull(String::toLongOrNull).filter { now - it in 0L..600_000L }
}

/** Crash-recovery SharedPreferences keys (single source; MainActivity and the job service also use them). */
internal object RecoveryPrefs {
    const val FILE = "crash_recovery"
    const val ACTIVE = "active"
    const val TASK_REMOVED = "task_removed"
    const val HANDLED_EXIT = "handled"
    const val ATTEMPTS = "attempts"
}

/**
 * ProcessKeepAliveService
 *
 * Minimal foreground service that keeps the process alive while a scrcpy session is
 * active (port of EasyControl's keep-alive FGS, simplified: no M01 gating, no circuit
 * breakers). Started/stopped from the session lifecycle (see NativeCoreFacade).
 */
class ProcessKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppRuntime.init(applicationContext)
        createChannel()
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onFailure { Log.w(TAG, "startForeground failed", it) }
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert the notification if the process was revived with a null intent.
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
        running = true
        val prefs = getSharedPreferences(RecoveryPrefs.FILE, MODE_PRIVATE)
        // AppRuntime.init 已在 onCreate 执行; bundleState 的 initialValue 已同步读盘,
        // 这里再 loadBundle() 是重复的主线程读盘 (已删除)。
        if (AppRuntime.scrcpy?.isStarted() == true) {
            prefs.edit().putBoolean(RecoveryPrefs.ACTIVE, true).apply()
        } else if (prefs.getBoolean(RecoveryPrefs.ACTIVE, false) &&
            !prefs.getBoolean(RecoveryPrefs.TASK_REMOVED, false) &&
            Storage.appSettings.bundleState.value.crashRecoveryEnabled
        ) {
            tryRecover(prefs)
        }
        return START_STICKY
    }

    private fun tryRecover(prefs: android.content.SharedPreferences) {
        if (Build.VERSION.SDK_INT < 30) return
        val exit = (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
            .getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull() ?: return
        val reason = exit.reason
        val now = System.currentTimeMillis()
        if (reason != ApplicationExitInfo.REASON_CRASH &&
            reason != ApplicationExitInfo.REASON_CRASH_NATIVE &&
            reason != ApplicationExitInfo.REASON_ANR &&
            reason != ApplicationExitInfo.REASON_LOW_MEMORY) return
        if (exit.timestamp <= prefs.getLong(RecoveryPrefs.HANDLED_EXIT, 0L) ||
            now - exit.timestamp !in 0L..600_000L
        ) return
        val recent = RecoveryPolicy.recentAttempts(now, prefs.getString(RecoveryPrefs.ATTEMPTS, "").orEmpty())
        if (recent.size >= 3) {
            Log.w(TAG, "recovery suppressed: 3 attempts in 10 minutes")
            return
        }
        prefs.edit().putLong(RecoveryPrefs.HANDLED_EXIT, exit.timestamp)
            .putString(RecoveryPrefs.ATTEMPTS, (recent + now).joinToString(",")).apply()
        Log.i(TAG, "recovery attempt: reason=$reason; app may be blocked by BAL policy")
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }.onFailure { Log.w(TAG, "background launch blocked; tap foreground notification", it) }
        // The existing foreground notification remains a user-driven recovery path.
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        getSharedPreferences(RecoveryPrefs.FILE, MODE_PRIVATE).edit()
            .putBoolean(RecoveryPrefs.TASK_REMOVED, true).putBoolean(RecoveryPrefs.ACTIVE, false).apply()
        Log.i(TAG, "task removed by user: recovery disabled until next manual launch")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.keepalive_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.keepalive_notification_title))
            .setContentText(getString(R.string.keepalive_notification_text))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val TAG = "ProcessKeepAlive"
        const val NOTIFICATION_ID = 0xEC01
        const val CHANNEL_ID = "process_keep_alive"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProcessKeepAliveService::class.java),
            )
        }

        fun stop(context: Context) {
            context.getSharedPreferences(RecoveryPrefs.FILE, Context.MODE_PRIVATE).edit()
                .putBoolean(RecoveryPrefs.ACTIVE, false).apply()
            runCatching { context.stopService(Intent(context, ProcessKeepAliveService::class.java)) }
        }
    }
}
