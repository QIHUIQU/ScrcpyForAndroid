package io.github.miuzarte.scrcpyforandroid.services.blackout

import android.os.SystemClock
import android.util.Log
import io.github.miuzarte.scrcpyforandroid.nativecore.NativeAdbService
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.storage.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * PaymentBlackoutCoordinator
 *
 * Host wiring for PB-1 (single-session variant of EasyControl's host wiring): observes
 * the controlled device's lock state (~5s polling over the existing adb shell channel,
 * plus a re-check when a control op arrives), feeds the state machine, and applies the
 * only action — setDisplayPower(false) — through the active scrcpy session.
 *
 * All logs carry the "PB-1 " prefix for on-device verification.
 */
object PaymentBlackoutCoordinator {
    private const val TAG = "PaymentBlackout"
    private const val TICK_MS = 1_000L
    private const val POLL_EVERY_TICKS = 5L
    private const val OP_THROTTLE_MS = 500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var machine: PaymentBlackoutStateMachine? = null

    @Volatile
    private var lastDispatched: PaymentBlackoutStateMachine.LockObservation =
        PaymentBlackoutStateMachine.LockObservation.UNKNOWN

    private var pollJob: Job? = null
    private val lastOpDispatchMs = AtomicLong(0L)

    private val configSource = object : PaymentBlackoutStateMachine.ConfigSource {
        override fun delaySecRaw(): Int =
            Storage.appSettings.bundleState.value.payBlackoutDelaySec

        override fun fallbackEnabledRaw(): Boolean =
            Storage.appSettings.bundleState.value.payBlackoutFallbackEnabled

        override fun fallbackThresholdSecRaw(): Int =
            Storage.appSettings.bundleState.value.payBlackoutFallbackSec
    }

    fun onSessionStarted() {
        synchronized(lock) {
            pollJob?.cancel()
            machine = PaymentBlackoutStateMachine(configSource)
            lastDispatched = PaymentBlackoutStateMachine.LockObservation.UNKNOWN
            lastOpDispatchMs.set(0L)
            pollJob = scope.launch { pollLoop() }
            Log.i(
                TAG,
                PaymentBlackoutConfig.LOG_PREFIX + "session started: " + PaymentBlackoutConfig.describe() +
                    " | effective: delaySec=${PaymentBlackoutConfig.normalizeDelaySec(configSource.delaySecRaw())}" +
                    " fallback=${PaymentBlackoutConfig.normalizeFallbackEnabled(configSource.fallbackEnabledRaw())}" +
                    " thresholdSec=${PaymentBlackoutConfig.normalizeFallbackSec(configSource.fallbackThresholdSecRaw())}",
            )
        }
    }

    fun onSessionStopped() {
        synchronized(lock) {
            pollJob?.cancel()
            pollJob = null
            machine?.onSessionLost()
            machine = null
            Log.i(TAG, PaymentBlackoutConfig.LOG_PREFIX + "session stopped")
        }
    }

    /**
     * Control-side user operation (touch / key / text / scroll injection).
     * Fast-path no-op when no round is in progress; throttled to <=1 dispatch / 500ms.
     */
    fun onControlOp() {
        val m = machine ?: return
        if (!m.active()) return
        val now = SystemClock.elapsedRealtime()
        val last = lastOpDispatchMs.get()
        if (now - last < OP_THROTTLE_MS) return
        if (!lastOpDispatchMs.compareAndSet(last, now)) return
        scope.launch { dispatchControlOp(m) }
    }

    private suspend fun dispatchControlOp(m: PaymentBlackoutStateMachine) {
        val now = SystemClock.elapsedRealtime()
        // A latched blackout may already be over: the user used the phone directly and brought the
        // panel back. Sample the real panel power before the op so the "returned to the control
        // side" operation can black out again right away instead of waiting for the next poll.
        if (m.blacked()) {
            applyResult(m.onDisplayObserved(readDisplayObservation(), now))
        }
        if (m.state() == PaymentBlackoutStateMachine.State.ARMED &&
            lastDispatched == PaymentBlackoutStateMachine.LockObservation.LOCKED
        ) {
            // Re-check at op time so a stale LOCKED observation cannot mis-trigger a blackout.
            val fresh = readLockState()
            if (fresh == PaymentBlackoutStateMachine.LockObservation.UNLOCKED) {
                lastDispatched = fresh
                applyResult(m.onControlledRewake(now))
            }
        }
        applyResult(m.onControlOp(now))
    }

    private suspend fun pollLoop() {
        var tick = 0L
        while (currentCoroutineContext().isActive) {
            val m = machine ?: return
            val now = SystemClock.elapsedRealtime()
            applyResult(m.onTick(now))
            if (tick % POLL_EVERY_TICKS == 0L) {
                val state = readLockState()
                if (state != PaymentBlackoutStateMachine.LockObservation.UNKNOWN &&
                    state != lastDispatched
                ) {
                    lastDispatched = state
                    if (state == PaymentBlackoutStateMachine.LockObservation.LOCKED) {
                        applyResult(m.onControlledLocked(now))
                    } else {
                        applyResult(m.onControlledRewake(now))
                    }
                }
                // While a blackout is latched, watch the real panel power (same poll, no new timer):
                // an external wake must clear the latch, otherwise the blackout never comes back.
                if (m.blacked()) {
                    applyResult(m.onDisplayObserved(readDisplayObservation(), now))
                }
            }
            tick++
            delay(TICK_MS)
        }
    }

    private suspend fun readLockState(): PaymentBlackoutStateMachine.LockObservation {
        return try {
            ControlledLockReader.parse(NativeAdbService.shell(ControlledLockReader.LOCK_STATE_CMD))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            PaymentBlackoutStateMachine.LockObservation.UNKNOWN
        }
    }

    private suspend fun readDisplayObservation(): PaymentBlackoutStateMachine.DisplayObservation {
        return try {
            ControlledLockReader.parseDisplayPower(
                NativeAdbService.shell(ControlledLockReader.DISPLAY_POWER_CMD),
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            PaymentBlackoutStateMachine.DisplayObservation.UNKNOWN
        }
    }

    private suspend fun applyResult(result: PaymentBlackoutStateMachine.Result) {
        val event = result.event()
        if (event != "noop:tick_noop" && event != "noop:pending_wait" && event != "noop:blackout_off_still") {
            Log.i(TAG, PaymentBlackoutConfig.LOG_PREFIX + result.describe())
        }
        if (result.applied()) {
            runCatching { AppRuntime.scrcpy?.setDisplayPower(on = false) }
                .onFailure { Log.w(TAG, PaymentBlackoutConfig.LOG_PREFIX + "blackout failed", it) }
        }
    }
}
