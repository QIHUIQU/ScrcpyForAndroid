package io.github.miuzarte.scrcpyforandroid.services.recovery

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import io.github.miuzarte.scrcpyforandroid.services.keepalive.CrashRecovery
import io.github.miuzarte.scrcpyforandroid.services.keepalive.RecoveryGate
import io.github.miuzarte.scrcpyforandroid.services.keepalive.WatchdogRecoveryState

/**
 * 崩溃恢复看门狗 —— 运行在独立进程 `:a11y`（与 [CrashRecoveryAccessibilityService] 同进程）。
 *
 * 为什么必须有它（本轮根因，见 findings-20261014-round16/android-mechanism-review.md）：
 * 崩溃对话框路径下 AMS 用 `allowRestart=false` 移除进程，同进程的粘性保活服务被 bringDown 且不再重启；
 * 同进程的无障碍服务也会随进程消失，且 AOSP 把「进程已死的无障碍服务」标进 `mCrashedServices` 并在
 * 重绑时跳过 —— `onServiceConnected` 不会在新进程里触发。**主进程一死，主进程里的一切触发源都不存在了**，
 * 只剩 15 分钟的持久化 job（实测迟到约 74 s）。
 *
 * 修法：让"主机制"的载体进程活过主进程——会话期间主进程 bind/start 本服务，主进程死亡时 AMS 会解绑并
 * 销毁本服务（`onUnbind`/`onDestroy`），这两个生命周期回调就是**事件驱动**的主进程死亡信号（无轮询、
 * 无定时器、无新权限，真机实测崩溃后 5 ms 触发，对比旧链约 74 s）。回调与其它触发源走完全相同的
 * 单一入口 [CrashRecovery.tryLaunchInWatchdog]：无障碍已启用 ⇒ 本进程持有无障碍的后台启动特权
 * （应用被系统服务绑定），直接 startActivity 回远控；未启用 ⇒ 如实降级为可点通知。
 *
 * 跨进程状态：门槛读 [RecoveryGate] 普通文件（每次读盘，跨进程新鲜——SharedPreferences 跨进程有缓存，
 * 真机实测会让已划掉的会话被错误恢复）；去重/限流用本进程内存态，每次会话开始复位。
 *
 * 隐私：不监听输入/屏幕/数据，不读窗口内容；只做进程存活判定 + 转交共享恢复入口。
 */
class RecoveryWatchdogService : Service() {

    private val state = WatchdogRecoveryState()

    override fun onBind(intent: Intent?): IBinder = BINDER

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_SESSION_STARTED, false) == true) {
            // 会话开始 = 上一次恢复成功, 或用户手动启动: 复位本进程的去重/额度, 历史不挡下一次恢复。
            state.reset()
            Log.i(TAG, "session started; per-session attempt budget reset")
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        recover("onUnbind")
        stopSelf()
        return false
    }

    override fun onDestroy() {
        recover("onDestroy")
        super.onDestroy()
    }

    /** 与其它触发源完全相同的单入口；是否真正恢复由 CrashRecovery 的共享判据决定。 */
    private fun recover(why: String) {
        val gate = RecoveryGate.read(applicationContext)
        if (!gate.isConsiderable) {
            Log.i(TAG, "watchdog $why; gate declined (active=${gate.active} removed=${gate.taskRemoved} enabled=${gate.enabled})")
            return
        }
        Log.i(TAG, "watchdog $why; evaluating crash recovery")
        CrashRecovery.tryLaunchInWatchdog(applicationContext, source = "watchdog", gate = gate, state = state)
    }

    companion object {
        private const val TAG = "CrashRecoveryWatchdog"
        private const val EXTRA_SESSION_STARTED = "watchdog_session_started"
        private const val REBIND_DELAY_MS = 2_000L

        private val BINDER: IBinder = Binder()
        private val HANDLER = Handler(Looper.getMainLooper())

        private var appContext: Context? = null
        private var armed = false

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = reArm()
            override fun onBindingDied(name: ComponentName?) = reArm()
        }

        /**
         * 会话开始：在 `:a11y` 进程启动并绑定看门狗。只尽力而为：失败只记录，不影响远控会话本身。
         */
        fun arm(context: Context) {
            if (armed) return
            appContext = context.applicationContext
            val intent = Intent(context, RecoveryWatchdogService::class.java)
                .putExtra(EXTRA_SESSION_STARTED, true)
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "watchdog start failed", it) }
            runCatching {
                if (context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                    armed = true
                    Log.i(TAG, "watchdog armed (dedicated process)")
                } else {
                    Log.w(TAG, "watchdog bind refused")
                }
            }.onFailure { Log.w(TAG, "watchdog bind failed", it) }
        }

        /** 会话结束：解绑并停掉看门狗（此时门槛已落盘为 active=false，恢复判据会自行拒绝）。 */
        fun disarm(context: Context) {
            if (!armed) return
            armed = false
            runCatching { context.unbindService(connection) }
            runCatching { context.stopService(Intent(context, RecoveryWatchdogService::class.java)) }
            Log.i(TAG, "watchdog disarmed")
        }

        /** 看门狗进程被系统回收时重新武装：否则本会话就再也没有崩溃触发源了。 */
        private fun reArm() {
            val context = appContext ?: return
            if (!armed) return
            armed = false
            Log.w(TAG, "watchdog connection lost; re-arming")
            HANDLER.postDelayed({ arm(context) }, REBIND_DELAY_MS)
        }
    }
}
