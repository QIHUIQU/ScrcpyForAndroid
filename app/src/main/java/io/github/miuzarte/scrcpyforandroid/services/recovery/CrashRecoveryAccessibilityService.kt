package io.github.miuzarte.scrcpyforandroid.services.recovery

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import io.github.miuzarte.scrcpyforandroid.services.keepalive.CrashRecovery
import io.github.miuzarte.scrcpyforandroid.services.keepalive.RecoveryPrefs

/**
 * 崩溃后自动拉起回远控的无障碍服务（用户本轮指定的主机制）。**运行在独立进程 `:a11y`**（见 manifest）。
 *
 * 为什么必须独立进程（本轮根因复核结论）：主进程崩溃后，同进程的无障碍服务也随之消失，而 AOSP 会把
 * 「进程已死的无障碍服务」标进 `mCrashedServices` 并在重绑时跳过 —— `onServiceConnected` 不会在
 * 新进程里触发。承载它的进程必须活过主进程崩溃，同进程的 [RecoveryWatchdogService]（绑定死亡回调）
 * 才是确定性的崩溃触发源。本类在系统把服务（重新）连接起来时也走一次共享恢复入口：进程刚起，
 * 恢复判据读的是磁盘最新值。
 *
 * 隐私：不声明任何 eventTypes/feedbackType/flags，窗口内容检索保持关闭（见 res/xml 配置），不监听输入、
 * 不读屏幕、不采集任何数据。首次必须用户在系统设置手动开启，应用不代授权。
 */
class CrashRecoveryAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        publishConnected(true)
        Log.i(TAG, "accessibility connected; evaluating crash recovery")
        CrashRecovery.tryLaunch(applicationContext, source = "accessibility")
    }

    /** 未声明任何事件类型：此回调只为满足 API，永不消费事件。 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        publishConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        publishConnected(false)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 无障碍服务由系统保活, 划掉任务后系统仍会复生本服务; 必须同样落 TASK_REMOVED,
        // 否则会违反"用户主动划掉不拉起"(保活 FGS 的 onTaskRemoved 在无会话时不一定被调用)。
        CrashRecovery.onUserSwipedAway(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    /** 连接状态由本进程(`:a11y`)写入, 主进程(设置页)读回; 单布尔, 尽力而为, 不参与恢复判据。 */
    private fun publishConnected(value: Boolean) {
        runCatching {
            applicationContext.getSharedPreferences(RecoveryPrefs.FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(RecoveryPrefs.A11Y_CONNECTED, value).apply()
        }.onFailure { Log.w(TAG, "publish connection state failed", it) }
    }

    companion object {
        private const val TAG = "CrashRecoveryA11y"
        private const val SERVICE_SIMPLE_NAME = "CrashRecoveryAccessibilityService"

        /** 系统无障碍设置里是否已启用本服务 (启用 ≠ 已连接)。 */
        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.id.endsWith(SERVICE_SIMPLE_NAME) }
        }

        /** 实际连接状态(设置页展示用)；由 `:a11y` 进程发布, 主进程读回, 不作为恢复判据。 */
        fun isConnected(context: Context): Boolean =
            context.getSharedPreferences(RecoveryPrefs.FILE, Context.MODE_PRIVATE)
                .getBoolean(RecoveryPrefs.A11Y_CONNECTED, false)
    }
}
