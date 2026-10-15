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
import java.io.File

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
     * 恢复前的前置门槛 (纯判据, JVM 可测): 只有"崩溃前有活跃会话、未被用户划掉、崩溃恢复开关开着"才考虑。
     */
    fun shouldConsider(active: Boolean, taskRemoved: Boolean, recoveryEnabled: Boolean): Boolean =
        active && !taskRemoved && recoveryEnabled

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
 * 跨进程恢复状态文件 (门槛 + 已处理的退出时间戳)。
 *
 * 触发源可能运行在独立 `:a11y` 进程, 而 Android 的 `SharedPreferences` **不支持跨进程**
 * (每个进程各持一份内存缓存, 看不到对方的写入)。真机实测两处后果:
 *  - 用户划掉任务后 `:a11y` 仍读到旧的 `active=true`, 错误地恢复了本该跳过的会话;
 *  - 同一次崩溃被看门狗和粘性保活服务各算一次, 同次退出没有被跨触发源去重。
 * 所以门槛与 `handled` 都走这个**普通文件**: 任意进程每次读盘都是最新值; 崩溃当下只有一方在写,
 * 不存在并发覆盖。会话开始/停止/用户划掉触发主进程 `publish`。
 */
internal data class RecoveryGate(
    val active: Boolean,
    val taskRemoved: Boolean,
    val enabled: Boolean,
    val handledExitMs: Long,
) {
    val isConsiderable: Boolean get() = CrashRecoveryPolicy.shouldConsider(active, taskRemoved, enabled)

    companion object {
        private const val TAG = "CrashRecovery"
        private const val GATE_FILE = "recovery_gate"

        /** 主进程调用: 从权威 prefs 读出门槛并落盘 (每次门槛变化后调用)。handled 取较新者, 不回退。 */
        fun publish(context: Context) {
            val prefs = context.getSharedPreferences(RecoveryPrefs.FILE, Context.MODE_PRIVATE)
            write(
                context,
                RecoveryGate(
                    active = prefs.getBoolean(RecoveryPrefs.ACTIVE, false),
                    taskRemoved = prefs.getBoolean(RecoveryPrefs.TASK_REMOVED, false),
                    enabled = prefs.getBoolean(RecoveryPrefs.ENABLED_MIRROR, false),
                    handledExitMs = maxOf(
                        prefs.getLong(RecoveryPrefs.HANDLED_EXIT, 0L),
                        read(context).handledExitMs,
                    ),
                ),
            )
        }

        /** 任一方派发/处理后登记这次死亡; 单调不回退, 保证同次退出跨触发源只算一次。 */
        fun recordHandled(context: Context, exitTimestampMs: Long) {
            val current = read(context)
            if (exitTimestampMs <= current.handledExitMs) return
            write(context, current.copy(handledExitMs = exitTimestampMs))
        }

        fun write(context: Context, gate: RecoveryGate) {
            runCatching {
                File(context.filesDir, GATE_FILE).writeText(
                    gate.active.asFlag().toString() + gate.taskRemoved.asFlag() +
                        gate.enabled.asFlag() + gate.handledExitMs.toString(),
                )
            }.onFailure { Log.w(TAG, "gate write failed", it) }
        }

        /** 任意进程调用: 每次读盘, 因此看到的一定是最新值。缺失/损坏按最保守(不恢复)处理。 */
        fun read(context: Context): RecoveryGate = runCatching {
            val text = File(context.filesDir, GATE_FILE).readText()
            RecoveryGate(
                active = text.getOrNull(0) == '1',
                taskRemoved = text.getOrNull(1) == '1',
                enabled = text.getOrNull(2) == '1',
                handledExitMs = text.drop(3).toLongOrNull() ?: 0L,
            )
        }.getOrElse { RecoveryGate(active = false, taskRemoved = true, enabled = false, handledExitMs = 0L) }

        private fun Boolean.asFlag(): Char = if (this) '1' else '0'
    }
}

/**
 * 跨进程"已到达"信号（round19 真机缺陷修复）。
 *
 * 原实现把 `LAUNCH_PENDING` 写在 SharedPreferences 里：派发发生在 `:a11y` 进程、到达消费在**主进程**，
 * 而 SharedPreferences 不支持跨进程（每个进程各持一份内存缓存）。真机实测（2026-10-15 16:08 PDT）：
 * 主进程 16:08:54 已 `recovery launch arrived`，但 `:a11y` 仍在 16:08:55 读到旧的 pending ⇒
 * 误判 `arrival not observed` 并多发一条降级通知。改为普通文件（与 [RecoveryGate] 同一跨进程原理）：
 * 任意进程每次读盘都是最新值。纯文本格式 `P<dispatchToken>`=已派发未到达，`A<ms>`=已到达。
 */
internal object RecoveryArrival {
    private const val TAG = "CrashRecovery"
    private const val FILE = "recovery_arrival"

    /** `:a11y` 派发拉起前登记本次令牌。 */
    fun markPending(context: Context, token: Long) =
        runCatching { File(context.filesDir, FILE).writeText("P$token") }
            .onFailure { Log.w(TAG, "arrival mark pending failed", it) }

    /** 主进程 MainActivity 真正消费恢复意图时登记到达（`:a11y` 读盘即可见）。 */
    fun markArrived(context: Context) =
        runCatching { File(context.filesDir, FILE).writeText("A${System.currentTimeMillis()}") }
            .onFailure { Log.w(TAG, "arrival mark arrived failed", it) }

    /** 会话真正结束/用户划掉时清账，避免陈旧值干扰下一次。 */
    fun clear(context: Context) =
        runCatching { File(context.filesDir, FILE).writeText("A0") }
            .onFailure { Log.w(TAG, "arrival clear failed", it) }

    fun isPending(context: Context, token: Long): Boolean =
        arrivalPending(runCatching { File(context.filesDir, FILE).readText() }.getOrNull(), token)

    /** 纯判据（JVM-testable）：文件内容仍是本次派发令牌 ⇒ 尚未观察到到达。 */
    fun arrivalPending(content: String?, token: Long): Boolean = content == "P$token"
}

/**
 * 某个触发进程自己的"同一死亡去重 + 10 分钟最多三次"状态。
 *
 * 主进用共享 prefs; `:a11y` 看门狗用本进程内存, 两者都用 [RecoveryGate] 里的 `handledExitMs` 做
 * 跨触发源去重。看门狗的内存态在每次会话开始时复位 (会话开始 = 上一次恢复成功或用户手动启动),
 * 所以历史额度不会挡住下一次正常恢复。
 */
internal interface RecoveryStore {
    fun lastHandledExitMs(): Long
    fun recentAttempts(now: Long): List<Long>
    fun recordAttempt(exitTimestampMs: Long, now: Long)
}

private class PrefsRecoveryStore(private val prefs: SharedPreferences) : RecoveryStore {
    override fun lastHandledExitMs(): Long = prefs.getLong(RecoveryPrefs.HANDLED_EXIT, 0L)

    override fun recentAttempts(now: Long): List<Long> =
        RecoveryPolicy.recentAttempts(now, prefs.getString(RecoveryPrefs.ATTEMPTS, "").orEmpty())

    override fun recordAttempt(exitTimestampMs: Long, now: Long) {
        prefs.edit()
            .putLong(RecoveryPrefs.HANDLED_EXIT, exitTimestampMs)
            .putString(RecoveryPrefs.ATTEMPTS, (recentAttempts(now) + now).joinToString(","))
            .apply()
    }
}

/** `:a11y` 看门狗的内存态; 每次会话开始即复位。 */
internal class WatchdogRecoveryState : RecoveryStore {
    private var lastHandled = 0L

    private val attempts = mutableListOf<Long>()

    fun reset() {
        lastHandled = 0L
        attempts.clear()
    }

    override fun lastHandledExitMs(): Long = lastHandled

    override fun recentAttempts(now: Long): List<Long> =
        attempts.filter { now - it in 0L..CrashRecoveryPolicy.RECENT_WINDOW_MS }

    override fun recordAttempt(exitTimestampMs: Long, now: Long) {
        lastHandled = exitTimestampMs
        attempts += now
    }
}

/**
 * CrashRecovery: the single decision + relaunch entry reused by every trigger.
 *
 * Trigger sources (the difference is only *what still exists after the app process dies*):
 *  - [io.github.miuzarte.scrcpyforandroid.services.recovery.RecoveryWatchdogService] — primary,
 *    the only trigger that provably survives the app process. It lives in the dedicated `:a11y`
 *    process with [CrashRecoveryAccessibilityService]; the main process binds it while a session
 *    is active, so when the main process dies AMS unbinds/destroys the watchdog and its
 *    `onUnbind`/`onDestroy` callbacks fire **in the still-live `:a11y` process** (device-verified;
 *    a binder-death token was tried first and did not fire). That process already holds the
 *    background-activity-launch privilege while the accessibility service is bound there, so the
 *    relaunch is not BAL-blocked.
 *  - [CrashRecoveryAccessibilityService] — fast path only WHEN the OS (re)connects the enabled
 *    service. A same-process service would never fire after a crash: AOSP marks a service whose
 *    process died as `crashed` and skips re-binding it (see the round16 mechanism review), which
 *    is exactly why both live in `:a11y`.
 *  - [ProcessKeepAliveService] — sticky foreground-service restart backstop.
 *  - [KeepAliveJobService] — periodic backstop (starts the FGS, which lands here).
 *
 * Cross-process state lives in [RecoveryGate] (a plain file) because SharedPreferences is not
 * shared between processes; it carries the gate and the handled-exit timestamp so the same death is
 * never handled twice across trigger sources.
 *
 * Dispatch, foreground arrival, session start and streaming are logged as separate stages; a
 * returned `startActivity` is never reported as a successful return to remote control.
 */
internal object CrashRecovery {
    private const val TAG = "CrashRecovery"
    private const val ARRIVAL_CHECK_MS = 3_000L
    private const val NOTIFICATION_ID = 0xEC03

    /** 主进程触发源 (保活 FGS / 无障碍服务 / job): 同进程读共享 prefs + 跨进程状态文件。 */
    fun tryLaunch(context: Context, source: String): Boolean {
        val prefs = context.getSharedPreferences(RecoveryPrefs.FILE, Context.MODE_PRIVATE)
        val file = RecoveryGate.read(context)
        val gate = RecoveryGate(
            active = prefs.getBoolean(RecoveryPrefs.ACTIVE, false),
            taskRemoved = prefs.getBoolean(RecoveryPrefs.TASK_REMOVED, false),
            enabled = prefs.getBoolean(RecoveryPrefs.ENABLED_MIRROR, false),
            handledExitMs = file.handledExitMs,
        )
        return launch(context, source, gate, PrefsRecoveryStore(prefs))
    }

    /** `:a11y` 看门狗触发源: 门槛/去重读盘(跨进程新鲜), 限流用本进程内存态。 */
    fun tryLaunchInWatchdog(
        context: Context,
        source: String,
        gate: RecoveryGate,
        state: WatchdogRecoveryState,
    ): Boolean = launch(context, source, gate, state)

    /** 门槛变化后由主进程落盘 (会话开始/停止/用户划掉)。 */
    fun publishGate(context: Context) = RecoveryGate.publish(context)

    /** Returns true only when an automatic relaunch was actually dispatched. */
    private fun launch(
        context: Context,
        source: String,
        gate: RecoveryGate,
        store: RecoveryStore,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (!gate.isConsiderable) return false

        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
            ?: return false
        val now = System.currentTimeMillis()
        val recent = store.recentAttempts(now)
        val due = CrashRecoveryPolicy.shouldAttempt(
            reason = exit.reason,
            exitTimestampMs = exit.timestamp,
            lastHandledExitMs = maxOf(store.lastHandledExitMs(), gate.handledExitMs),
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

        store.recordAttempt(exit.timestamp, now)
        RecoveryGate.recordHandled(context, exit.timestamp)
        Log.i(
            TAG,
            "recovery attempt ${recent.size + 1}/${CrashRecoveryPolicy.MAX_ATTEMPTS} " +
                "source=$source reason=${exit.reason}",
        )

        // 无障碍已启用 ⇒ 主机制: 自动拉起; 未启用/未授权 ⇒ 如实降级为可点通知, 不谎称已自动回远控。
        if (CrashRecoveryAccessibilityService.isEnabled(context)) {
            return dispatchAutoRelaunch(context, source)
        }
        val posted = postFallbackNotification(context)
        Log.w(
            TAG,
            "recovery degraded (source=$source): accessibility not enabled; " +
                "auto relaunch skipped, notification_posted=$posted",
        )
        return false
    }

    private fun dispatchAutoRelaunch(context: Context, source: String): Boolean {
        // 只有真的要拉起才登记 token: 3 秒内未被 MainActivity 消费判定"未观察到到达"。
        // 用跨进程普通文件而非 SharedPreferences (后者 `:a11y` 与主进程各持缓存, 真机实测会误报)。
        val token = System.currentTimeMillis()
        RecoveryArrival.markPending(context, token)
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
            if (RecoveryArrival.isPending(context, token)) {
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
        publishGate(context)
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
