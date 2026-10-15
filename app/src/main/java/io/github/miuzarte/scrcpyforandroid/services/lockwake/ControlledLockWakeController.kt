package io.github.miuzarte.scrcpyforandroid.services.lockwake

import android.util.Log
import io.github.miuzarte.scrcpyforandroid.R
import io.github.miuzarte.scrcpyforandroid.nativecore.NativeAdbService
import io.github.miuzarte.scrcpyforandroid.password.InjectionController
import io.github.miuzarte.scrcpyforandroid.password.PasswordAutofillPolicy
import io.github.miuzarte.scrcpyforandroid.password.PasswordRepository
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 控制端解锁 ⇒ 检测被控端锁屏态 ⇒ 自动唤醒（+条件解锁）。
 *
 * 触发：UnlockJumpController 的解锁触发面（USER_PRESENT / SCREEN_ON / 轮询兜底）；
 * 每回合至多一次（回合边界 = 控制端熄屏 / 会话结束，见 [resetEpisode]）。
 * 动作：只读检测（dumpsys window policy + dumpsys trust，两轮取样）→ LOCKED ⇒ 唤醒
 * （setDisplayPower(on=true) = 控制消息 7 STATE_ON）→ 判据 v3 授权时执行一次 `wm dismiss-keyguard`。
 * 安全边界：真安全锁不走 dismiss；仅用户显式开启、且密码库中有当前设备序列号唯一匹配的未认证密码条目时，尝试一次自动提交。
 * 日志：tag ControlledLockWake，全部可 grep。
 */
object ControlledLockWakeController {
    const val TAG = "ControlledLockWake"

    @Volatile
    private var attemptedThisEpisode = false

    @Volatile private var lastAttemptMs = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 控制端解锁触发（由 UnlockJumpController 三路触发面调用）。 */
    @Synchronized
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
        val now = android.os.SystemClock.elapsedRealtime()
        if (!ControlledLockWake.shouldAttemptEpisode(attemptedThisEpisode, now, lastAttemptMs)) {
            Log.i(TAG, "trigger=$source -> noop (same episode/debounce)")
            return
        }
        attemptedThisEpisode = true
        lastAttemptMs = now
        Log.i(TAG, "trigger=$source -> detecting controlled lock state")
        scope.launch {
            runCatching { runOnce(scrcpy) }
                .onFailure { Log.w(TAG, "runOnce failed (non-blocking)", it) }
        }
    }

    /** 回合边界复位（控制端熄屏 / 会话结束）⇒ 下一次解锁允许再检测一次。 */
    @Synchronized
    fun resetEpisode(reason: String) {
        Log.i(TAG, "episode reset ($reason) previousAttempt=$attemptedThisEpisode")
        attemptedThisEpisode = false
    }

    /**
     * 真安全锁的自动提交（安全边界：默认开启但需显式登记）：
     * 仅当设置开启、且密码库存在“名称与设备序列号完全相同、创建时未启用认证”的唯一条目时
     * 尝试一次输入 + 回车；无匹配一律降级为「已唤醒 + 提示手动输入」。凭据不落日志，
     * 提交后立即清零。提交结果控制端无法验证 ⇒ 仅记录“已提交”。
     */
    private suspend fun submitSavedCredential(scrcpy: Scrcpy) {
        val serial = AppRuntime.currentConnectedDevice?.serial.orEmpty()
        val settingEnabled = Storage.appSettings.bundleState.value.controlledLockAutoPasswordEnabled
        val password = if (settingEnabled && serial.isNotBlank()) runCatching {
            val entries = PasswordRepository.getAll()
            try {
                PasswordAutofillPolicy.forDevice(entries, serial)?.cipherText?.copyOf()
            } finally {
                entries.forEach { it.cipherText?.fill('\u0000') }
            }
        }.getOrNull() else null
        if (password == null) {
            Log.i(TAG, "secure lock: no enrolled password entry for serial=$serial -> manual input")
            if (serial.isNotBlank()) {
                AppRuntime.snackbar(R.string.controlled_lock_manual_input_needed, serial)
            } else {
                AppRuntime.snackbar(R.string.controlled_lock_manual_input_needed_no_device)
            }
            return
        }
        runCatching {
            try {
                NativeAdbService.shell("input keyevent 82") // KEYCODE_MENU: 令锁屏凭证输入界面可见（被忽略亦无害）
                // 850ms: 部分设备从 MENU 键到凭证界面真正可见需要 ~0.5-1s, 注入过早会丢字符
                // (旧值 350ms 在低性能/无动画场景偶发失败); 后台流程, 慢一点无感
                delay(850)
                // 自动解锁的注入不算「用户用完」: notifyBlackout=false, 否则 PB-1 会在解锁后
                // 立刻把被控端黑屏(默认 D=0 立即 fire) —— PB-1 与自动解锁互斥根因 (P0-3)。
                // 凭据在 finally 清零。
                check(InjectionController.inject(password, notifyBlackout = false)) { "Credential text not sent" }
                check(scrcpy.injectKeycode(0, 66, notifyBlackout = false)) { "Credential submit down not sent" }
                check(scrcpy.injectKeycode(1, 66, notifyBlackout = false)) { "Credential submit up not sent" }
            } finally {
                password.fill('\u0000')
            }
        }.onSuccess { Log.i(TAG, "secure lock: credential submitted for bound device (success unverified)") }
            .onFailure { Log.w(TAG, "secure lock: credential submission failed", it) }
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
        if (!gate.authorized) {
            if (gate.reason == ControlledLockWake.REASON_REAL_SECURE) {
                submitSavedCredential(scrcpy)
            }
            return
        }
        val sent = runCatching { NativeAdbService.shell(ControlledLockWake.DISMISS_KEYGUARD_CMD) }.isSuccess
        Log.i(TAG, "dismiss-keyguard sent=$sent (命令成功≠解锁成功; 真机效果以用户为准)")
    }
}
