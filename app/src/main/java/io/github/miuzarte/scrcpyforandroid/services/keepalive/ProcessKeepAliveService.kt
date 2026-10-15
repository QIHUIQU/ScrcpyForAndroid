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
import io.github.miuzarte.scrcpyforandroid.constants.Defaults
import io.github.miuzarte.scrcpyforandroid.models.ConnectionTarget
import io.github.miuzarte.scrcpyforandroid.models.DeviceConnectionType
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.storage.ScrcpyOptions
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
    const val TARGET_HOST = "target_host"
    const val TARGET_PORT = "target_port"
    const val TARGET_TYPE = "target_type"
    const val TARGET_PROFILE = "profile"
    const val LAUNCH_PENDING = "launch_pending"
}

/** 崩溃前活跃会话目标 (恢复重连复用; 与 ACTIVE/TASK_REMOVED 同一 prefs 文件)。 */
internal data class RecoverySession(
    val target: ConnectionTarget,
    val profileId: String,
)

/**
 * Persists the active session target so an abnormal-exit self-heal can reconnect to the
 * same device without inventing a second session framework. Written on session start,
 * cleared on user stop / swipe-away (never on the internal stop→start reconnect gap).
 */
internal object RecoveryIntent {
    private fun prefs(context: Context) =
        context.getSharedPreferences(RecoveryPrefs.FILE, Context.MODE_PRIVATE)

    fun record(context: Context, target: ConnectionTarget, profileId: String) {
        prefs(context).edit()
            .putBoolean(RecoveryPrefs.ACTIVE, true)
            .putString(RecoveryPrefs.TARGET_HOST, target.host)
            .putInt(RecoveryPrefs.TARGET_PORT, target.port)
            .putString(RecoveryPrefs.TARGET_TYPE, target.connectionType.name)
            .putString(RecoveryPrefs.TARGET_PROFILE, profileId)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .putBoolean(RecoveryPrefs.ACTIVE, false)
            .putBoolean(RecoveryPrefs.LAUNCH_PENDING, false)
            .remove(RecoveryPrefs.TARGET_HOST)
            .remove(RecoveryPrefs.TARGET_PORT)
            .remove(RecoveryPrefs.TARGET_TYPE)
            .remove(RecoveryPrefs.TARGET_PROFILE)
            .apply()
    }

    fun load(context: Context): RecoverySession? {
        val p = prefs(context)
        if (!p.getBoolean(RecoveryPrefs.ACTIVE, false) || p.getBoolean(RecoveryPrefs.TASK_REMOVED, false)) return null
        val port = p.getInt(RecoveryPrefs.TARGET_PORT, Defaults.ADB_PORT)
        if (port !in 1..65535) return null
        val host = p.getString(RecoveryPrefs.TARGET_HOST, null)?.takeIf { it.isNotBlank() } ?: return null
        val type = runCatching {
            DeviceConnectionType.valueOf(p.getString(RecoveryPrefs.TARGET_TYPE, null).orEmpty())
        }.getOrNull() ?: return null
        return RecoverySession(
            target = ConnectionTarget(
                host = host,
                port = port,
                connectionType = type,
            ),
            profileId = p.getString(RecoveryPrefs.TARGET_PROFILE, null)
                ?: ScrcpyOptions.GLOBAL_PROFILE_ID,
        )
    }
}

/**
 * Pure recovery-intent gate (JVM-testable). Auto-reconnect happens ONLY when this launch
 * carried an armed recovery intent (sticky-service abnormal-exit relaunch or a keep-alive
 * notification tap). A plain launcher start never arms it. The intent is single-shot, and a
 * mismatch on any precondition (already running / no target / user swiped away / toggle off)
 * declines the restore instead of connecting to the wrong device.
 */
internal object RecoveryIntentPolicy {
    private val armed = kotlinx.coroutines.flow.MutableStateFlow(false)
    val requests: kotlinx.coroutines.flow.StateFlow<Boolean> = armed

    fun arm() {
        armed.value = true
    }

    fun consumeIfRestorable(
        sessionActive: Boolean,
        crashRecoveryEnabled: Boolean,
        taskRemoved: Boolean,
        targetRestorable: Boolean,
    ): Boolean {
        if (!armed.value) return false
        armed.value = false
        return !sessionActive && crashRecoveryEnabled && !taskRemoved && targetRestorable
    }
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
        // On a cold START_STICKY resurrection the settings StateFlow still has defaults.
        runCatching { kotlinx.coroutines.runBlocking { Storage.appSettings.loadBundle() } }
            .onFailure { Log.w(TAG, "recovery settings unavailable; skip", it); return START_STICKY }
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
            .putString(RecoveryPrefs.ATTEMPTS, (recent + now).joinToString(","))
            .putBoolean(RecoveryPrefs.LAUNCH_PENDING, true).apply()
        Log.i(TAG, "recovery attempt: reason=$reason (${recent.size + 1}/3); app may be blocked by BAL policy")
        // Fullscreen recovery intent is handed over as an extra; a returned startActivity is
        // NOT proof of arrival (BAL can silently block), so log dispatch and arrival separately.
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_RECOVER_SESSION, true),
            )
        }.onSuccess {
            Log.i(TAG, "recovery launch dispatched; awaiting MainActivity arrival")
        }.onFailure { Log.w(TAG, "recovery launch failed; tap foreground notification", it) }
        // BAL may silently reject startActivity. Check arrival once, never report dispatch as success.
        android.os.Handler(mainLooper).postDelayed({
            if (prefs.getBoolean(RecoveryPrefs.LAUNCH_PENDING, false)) {
                Log.w(TAG, "recovery MainActivity arrival not observed (possibly BAL); tap foreground notification")
            }
        }, 3_000L)
        // The existing foreground notification remains a user-driven recovery path.
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户主动划掉: 清除活跃会话目标并禁用自动拉起
        RecoveryIntent.clear(this)
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
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_RECOVER_SESSION, true),
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

        /** MainActivity extra: this launch is an abnormal-exit recovery (arm the restore intent). */
        const val EXTRA_RECOVER_SESSION = "recover_session"

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
