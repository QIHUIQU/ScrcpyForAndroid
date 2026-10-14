package io.github.miuzarte.scrcpyforandroid.services

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.miuzarte.scrcpyforandroid.StreamActivity
import io.github.miuzarte.scrcpyforandroid.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * UnlockJumpController —— "解锁/回前台自动跳回远控"（移植 EasyControl 的
 * autoJumpOnUnlock + MainAutoJumpPlan 口径）。
 *
 * 触发面（三路冗余，任一先到即触发；去抖 1.5s）：
 *  1. ACTION_USER_PRESENT（解锁广播）
 *  2. ACTION_SCREEN_ON（亮屏广播；有锁屏时 keyguard 解除后才跳）
 *  3. 前台轮询兜底（2s；屏幕"可用性" false→true 跳变时触发）——即使本设备把全部
 *     广播都吞掉，轮询也能工作。
 *
 * 注册生命周期：**进程级**（Application 上下文；AppRuntime.init 一次注册，永不反注册，
 * 重连/会话抖动不影响）。原项目结论（RecoveryDynamicReceiverRegistrar）：targetSdk ≥ 26
 * 时 USER_PRESENT/SCREEN_ON 不在隐式广播豁免清单内，必须动态注册；这里用 EXPORTED
 * （过滤器只含受保护广播，其他应用无法伪造发送；EXPORTED 最大化各 ROM 送达率）。
 *
 * 判据（等价 MainAutoJumpPlan 五条）：enabled && sessionActive && !streamForeground
 * ⇒ startActivity(StreamActivity)（NEW_TASK|REORDER_TO_FRONT：实例存在则 reorder 不重建，
 * 不存在才新建）。触发来源包括 MainActivity.onResume（主界面被拉起时立即转串流）。
 *
 * 日志（tag UnlockJump，全量可 grep）：注册、每次广播送达、每次触发与判定、跳转结果。
 */
object UnlockJumpController {
    const val TAG = "UnlockJump"

    private const val POLL_INTERVAL_MS = 2_000L
    private const val JUMP_DEBOUNCE_MS = 1_500L

    /** 纯判据（JVM-testable）。 */
    fun shouldJump(enabled: Boolean, sessionActive: Boolean, streamInForeground: Boolean): Boolean =
        enabled && sessionActive && !streamInForeground

    /** 轮询跳变判据（JVM-testable）：屏幕从"不可用"变为"可用"（亮屏且无锁屏）才触发。 */
    fun shouldTriggerOnWakeTransition(usable: Boolean, lastUsable: Boolean): Boolean =
        usable && !lastUsable

    @Volatile
    private var installed = false

    @Volatile
    private var lastJumpAttemptMs = 0L

    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val receiver: BroadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                Log.i(TAG, "broadcast: $action")
                when (action) {
                    Intent.ACTION_USER_PRESENT -> tryJump(source = "user_present")
                    Intent.ACTION_SCREEN_ON -> {
                        val keyguardLocked = keyguardManager()?.isKeyguardLocked == true
                        if (keyguardLocked) {
                            Log.i(TAG, "screen_on: keyguard locked -> wait for user_present/poll")
                        } else {
                            tryJump(source = "screen_on")
                        }
                    }
                }
            }
        }
    }

    /** 进程级安装（幂等）：注册接收器 + 启动轮询兜底。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
            Log.i(TAG, "receiver registered (process lifetime): USER_PRESENT, SCREEN_ON, flags=EXPORTED")
        }.onFailure {
            Log.w(TAG, "receiver register failed", it)
        }
        startWakePoll(context.applicationContext)
    }

    /**
     * 判定并尝试跳回串流界面。全部触发面（广播 / MainActivity.onResume / 轮询）都汇到这里。
     */
    fun tryJump(source: String) {
        val enabled = Storage.appSettings.bundleState.value.autoJumpOnUnlock
        val sessionActive = AppRuntime.scrcpy?.isStarted() == true
        val streamForeground = StreamActivity.isForeground()
        val jump = shouldJump(enabled, sessionActive, streamForeground)
        val now = SystemClock.elapsedRealtime()
        val debounced = jump && now - lastJumpAttemptMs < JUMP_DEBOUNCE_MS
        Log.i(
            TAG,
            "trigger=$source enabled=$enabled sessionActive=$sessionActive " +
                "streamForeground=$streamForeground debounced=$debounced -> " +
                if (jump && !debounced) "jump" else "noop",
        )
        if (!jump || debounced) return
        lastJumpAttemptMs = now
        val context = runCatching { AppRuntime.context }.getOrNull() ?: return
        runCatching {
            context.startActivity(
                StreamActivity.createIntent(context).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    )
                },
            )
            Log.i(TAG, "jump startActivity(StreamActivity) dispatched")
        }.onFailure {
            // BAL 可能静默拦截; 打开 App 时主界面 onResume 会再试一次
            Log.w(TAG, "jump startActivity failed", it)
        }
    }

    /** 屏幕可用性轮询兜底：广播全部被 ROM 吞掉时的最后防线。 */
    private fun startWakePoll(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return
        pollScope.launch {
            var lastUsable = isScreenUsable(powerManager, keyguardManager)
            Log.i(TAG, "wake poll started (interval=${POLL_INTERVAL_MS}ms), initialUsable=$lastUsable")
            while (true) {
                delay(POLL_INTERVAL_MS)
                val usable = isScreenUsable(powerManager, keyguardManager)
                if (shouldTriggerOnWakeTransition(usable, lastUsable)) {
                    tryJump(source = "poll_wake")
                }
                lastUsable = usable
            }
        }
    }

    private fun isScreenUsable(powerManager: PowerManager, keyguardManager: KeyguardManager): Boolean =
        powerManager.isInteractive && !keyguardManager.isKeyguardLocked

    private fun keyguardManager(): KeyguardManager? =
        runCatching {
            AppRuntime.context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        }.getOrNull()
}
