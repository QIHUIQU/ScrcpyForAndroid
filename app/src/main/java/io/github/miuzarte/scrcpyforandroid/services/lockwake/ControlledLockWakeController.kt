package io.github.miuzarte.scrcpyforandroid.services.lockwake

import android.util.Log
import io.github.miuzarte.scrcpyforandroid.nativecore.NativeAdbService
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 控制端解锁 ⇒ 检测被控端锁屏态 ⇒ 自动唤醒（+条件解锁）。
 *
 * 触发：UnlockJumpController 的解锁触发面（USER_PRESENT / SCREEN_ON / 轮询兜底）；
 * 每回合至多一次（回合边界 = 控制端熄屏 / 会话结束，见 [resetEpisode]）。
 * 动作：只读检测（dumpsys window policy + dumpsys trust，两轮取样）→ LOCKED ⇒ 唤醒
 * （setDisplayPower(on=true) = 控制消息 7 STATE_ON）→ 判据 v3 授权时执行一次 `wm dismiss-keyguard`。
 * 安全边界：真安全锁（deviceLocked=1 / strongAuth≠0x0 / mSimSecure=true）不 dismiss；唤醒未送达不 dismiss；
 * 失败/不确定一律不动作、不重试；不使用目标项目密码库（其注入路径为生物认证门控，不绕过）。
 * 日志：tag ControlledLockWake，全部可 grep。
 */
object ControlledLockWakeController {
    const val TAG = "ControlledLockWake"

    @Volatile
    private var attemptedThisEpisode = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 控制端解锁触发（由 UnlockJumpController 三路触发面调用）。 */
    fun onControlUnlocked(source: String) {
        if (!Storage.appSettings.bundleState.value.controlledLockWakeEnabled) {
            Log.i(TAG, "trigger=$source -> noop (disabled)")
            return
        }
        val scrcpy = AppRuntime.scrcpy
        if (scrcpy == null || !scrcpy.isStarted()) {
            Log.i(TAG, "trigger=$source -> noop (no active session)")
            return
        }
        if (attemptedThisEpisode) {
            Log.i(TAG, "trigger=$source -> noop (same episode)")
            return
        }
        attemptedThisEpisode = true
        Log.i(TAG, "trigger=$source -> detecting controlled lock state")
        scope.launch {
            runCatching { runOnce(scrcpy) }
                .onFailure { Log.w(TAG, "runOnce failed (non-blocking)", it) }
        }
    }

    /** 回合边界复位（控制端熄屏 / 会话结束）⇒ 下一次解锁允许再检测一次。 */
    fun resetEpisode(reason: String) {
        if (attemptedThisEpisode) {
            Log.i(TAG, "episode reset ($reason)")
        }
        attemptedThisEpisode = false
    }

    private suspend fun runOnce(scrcpy: Scrcpy) {
        // 两轮只读取样（v3 稳定化：取值不一致 ⇒ 保守不动作）
        val windows = ArrayList<String?>(2)
        val trusts = ArrayList<String?>(2)
        repeat(2) {
            windows += runCatching { NativeAdbService.shell(ControlledLockWake.LOCK_STATE_CMD) }.getOrNull()
            trusts += runCatching { NativeAdbService.shell(ControlledLockWake.TRUST_STATE_CMD) }.getOrNull()
        }
        val samples = listOf(
            ControlledLockWake.parseSample(windows[0], trusts[0]),
            ControlledLockWake.parseSample(windows[1], trusts[1]),
        )
        Log.i(TAG, "detect [${samples[0].describe()}] [${samples[1].describe()}]")
        if (samples[0].state != ControlledLockWake.LockState.LOCKED) {
            Log.i(TAG, "state=${samples[0].state.name} -> noop (not locked)")
            return
        }
        // 唤醒：控制消息 7 STATE_ON（非 POWER/SLEEP/WAKEUP 语义）
        val wakeSent = runCatching { scrcpy.setDisplayPower(on = true) }.isSuccess
        Log.i(TAG, "wake setDisplayPower(on=true) sent=$wakeSent")
        if (!wakeSent) {
            Log.i(TAG, "wake not sent -> skip dismiss")
            return
        }
        // 解锁：判据 v3（真安全锁 / 取样不一致 ⇒ 不动作）
        val gate = ControlledLockWake.dismissGate(samples, sentSessions = 1)
        Log.i(TAG, "dismiss gate -> authorized=${gate.authorized} reason=${gate.reason}")
        if (!gate.authorized) return
        val sent = runCatching { NativeAdbService.shell(ControlledLockWake.DISMISS_KEYGUARD_CMD) }.isSuccess
        Log.i(TAG, "dismiss-keyguard sent=$sent (命令成功≠解锁成功; 真机效果以用户为准)")
    }
}
