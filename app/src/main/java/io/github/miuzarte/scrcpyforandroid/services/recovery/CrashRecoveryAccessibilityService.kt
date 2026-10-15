package io.github.miuzarte.scrcpyforandroid.services.recovery

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.services.keepalive.CrashRecovery
import io.github.miuzarte.scrcpyforandroid.storage.Storage

/**
 * Crash-recovery auto-relaunch — the primary mechanism (user-directed this round).
 *
 * An enabled accessibility service is bound by the system, and while it is bound its host process
 * is granted the background-activity-start privilege that the BAL rules list as an exception
 * ("the app is bound by a service that has been granted permission to start background
 * activities"). This class relays [onServiceConnected] onto the shared decision + MainActivity
 * (restore intent) entry in [CrashRecovery] and is the intended FAST trigger.
 *
 * Whether the system re-binds an enabled accessibility service after the app process is killed by
 * a crash is OEM/OS dependent and must NOT be assumed: AOSP marks a service whose process died as
 * "crashed" and skips re-binding it (see the round16 independent mechanism review). The
 * deterministic surviving trigger stays the persisted backstop job, which lands on the same shared
 * path and degrades to the notification when no automatic relaunch is available.
 *
 * Privacy: requests no event types (empty callback), does not retrieve window content, does not
 * observe input, the screen, or any data. The user must enable it manually in system settings.
 */
class CrashRecoveryAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        AppRuntime.init(applicationContext)
        // Cold process: the settings StateFlow still holds defaults until loaded. Keep the
        // existing gate honest — if settings are unavailable, do not guess the user's toggle.
        runCatching { kotlinx.coroutines.runBlocking { Storage.appSettings.loadBundle() } }
            .onFailure { Log.w(TAG, "settings unavailable; skip recovery", it); return }
        Log.i(TAG, "accessibility connected; evaluating crash recovery")
        CrashRecovery.tryLaunch(applicationContext, source = "accessibility")
    }

    /** No event types are declared: this callback only ever exists to satisfy the API. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 无障碍服务由系统保活, 划掉任务后系统仍会复生本服务; 必须同样落 TASK_REMOVED,
        // 否则会违反"用户主动划掉不拉起"(保活 FGS 的 onTaskRemoved 在无会话时不一定被调用)。
        CrashRecovery.onUserSwipedAway(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val TAG = "CrashRecoveryA11y"
        private const val SERVICE_SIMPLE_NAME = "CrashRecoveryAccessibilityService"

        /** 系统无障碍设置里是否已启用本服务 (启用 ≠ 已连接)。 */
        @Volatile
        var connected: Boolean = false
            private set

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.id.endsWith(SERVICE_SIMPLE_NAME) }
        }
    }
}
