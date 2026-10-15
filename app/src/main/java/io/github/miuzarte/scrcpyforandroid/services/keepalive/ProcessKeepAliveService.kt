package io.github.miuzarte.scrcpyforandroid.services.keepalive

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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

    /**
     * 崩溃恢复开关的镜像 (主进程在会话开始时写入)。触发源可能运行在独立 `:a11y` 进程,
     * 那里不能安全访问 DataStore, 所以恢复判据读这个布尔而不是 DataStore。
     */
    const val ENABLED_MIRROR = "recovery_enabled"

    /** 无障碍服务(运行在 `:a11y`)发布的连接状态; 仅设置页展示用, 不参与恢复判据。 */
    const val A11Y_CONNECTED = "a11y_connected"
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
            // 镜像当前开关: 崩溃恢复的触发源可能不在主进程, 不能在那边读 DataStore。
            .putBoolean(
                RecoveryPrefs.ENABLED_MIRROR,
                Storage.appSettings.bundleState.value.crashRecoveryEnabled,
            )
            .putString(RecoveryPrefs.TARGET_HOST, target.host)
            .putInt(RecoveryPrefs.TARGET_PORT, target.port)
            .putString(RecoveryPrefs.TARGET_TYPE, target.connectionType.name)
            .putString(RecoveryPrefs.TARGET_PROFILE, profileId)
            .apply()
        // 门槛变化后落盘: 独立 `:a11y` 进程靠读文件拿最新门槛 (SharedPreferences 跨进程不可见)。
        CrashRecovery.publishGate(context)
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
        CrashRecovery.publishGate(context)
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
 *
 * Its START_STICKY restart is now only a backstop trigger: the decision + relaunch live in
 * [CrashRecovery] (shared with the accessibility service, the primary trigger).
 */
class ProcessKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppRuntime.init(applicationContext)
        getSystemService(NotificationManager::class.java)?.let { CrashRecovery.ensureChannel(this, it) }
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
        } else {
            CrashRecovery.tryLaunch(this, source = "keepalive_service")
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户主动划掉: 清除活跃会话目标并禁用自动拉起
        CrashRecovery.onUserSwipedAway(this)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
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
