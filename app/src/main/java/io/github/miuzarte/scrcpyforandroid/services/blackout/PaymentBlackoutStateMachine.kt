package io.github.miuzarte.scrcpyforandroid.services.blackout

/**
 * PaymentBlackoutStateMachine
 *
 * PB-1 "payment / temporary-use blackout", ported from EasyControl's Java state machine
 * (trigger changed from a countdown to lock + control-op events; the old countdown is
 * kept as a default-on fallback):
 *
 * 1. Enter: controlled device locked -> ARMED ("temporary use in progress").
 * 2. Exit: a control-side user operation afterwards -> complete.
 * 3. Action: the ONLY action is APPLY_BLACKOUT (setDisplayPower(false)); delay D is
 *    0..30 s (default 0 = immediate).
 * 4. Fallback: a single timer from the lock moment; if T_f elapses without the new
 *    logic triggering a blackout -> fallback blackout once (T_f >= 30 s, default 30).
 *
 * Boundaries: control ops before the lock are no-ops; a controlled re-wake returns to
 * ARMED (cancels a pending blackout); one blackout per round (blacked gate); config is
 * re-read on every decision; clock is injected via nowMs (pure logic, JVM-testable).
 */
class PaymentBlackoutStateMachine(config: ConfigSource?) {

    enum class State { IDLE, ARMED, PENDING }

    enum class Action { APPLY_BLACKOUT }

    enum class LockObservation { UNKNOWN, LOCKED, UNLOCKED }

    /** Config read-back source (host-injected; re-read on every decision, never cached). */
    interface ConfigSource {
        fun delaySecRaw(): Int
        fun fallbackEnabledRaw(): Boolean
        fun fallbackThresholdSecRaw(): Int
    }

    /** One decision result (actions + event label + snapshot; for host logs / test asserts). */
    class Result internal constructor(
        private val actions: List<Action>,
        private val event: String,
        private val state: State,
        private val blacked: Boolean,
        private val delaySec: Int,
        private val fallbackThresholdSec: Int,
    ) {
        fun actions(): List<Action> = actions
        fun applied(): Boolean = actions.isNotEmpty()
        fun event(): String = event
        fun state(): State = state
        fun blacked(): Boolean = blacked
        fun delaySec(): Int = delaySec
        fun fallbackThresholdSec(): Int = fallbackThresholdSec
        fun describe(): String =
            "event=$event state=$state blacked=$blacked D=$delaySec Tf=$fallbackThresholdSec"
    }

    private val config: ConfigSource = config ?: DEFAULT_CONFIG

    private var state: State = State.IDLE
    private var blacked = false
    private var pendingFireAtMs = 0L
    private var fallbackStartMs = 0L
    private var fallbackArmed = false
    private var lastObs = LockObservation.UNKNOWN
    private var fireCount = 0
    private var deferCount = 0

    // ================= read-only observation =================

    @Synchronized fun state(): State = state
    @Synchronized fun blacked(): Boolean = blacked
    @Synchronized fun lastObservation(): LockObservation = lastObs
    @Synchronized fun fireCount(): Int = fireCount
    @Synchronized fun deferCount(): Int = deferCount
    @Synchronized fun active(): Boolean = state != State.IDLE

    @Synchronized fun statusLine(): String =
        "state=$state blacked=$blacked obs=$lastObs fires=$fireCount defers=$deferCount"

    // ================= events & transitions =================

    /** CONTROLLED_LOCKED: the controlled device locked. PENDING -> no-op; else ARMED + fallback (re)start. */
    @Synchronized fun onControlledLocked(nowMs: Long): Result {
        val tf = fallbackThresholdSec()
        if (state == State.PENDING) {
            return noop("locked_pending_noop", tf)
        }
        state = State.ARMED
        blacked = false
        lastObs = LockObservation.LOCKED
        fallbackArmed = true
        fallbackStartMs = nowMs
        return result(EMPTY, "arm", tf)
    }

    /** CONTROLLED_REWAKE: the controlled device woke again (user went back). IDLE -> no-op; else ARMED, pending cancelled, fallback restart. */
    @Synchronized fun onControlledRewake(nowMs: Long): Result {
        val tf = fallbackThresholdSec()
        if (state == State.IDLE) {
            return noop("rewake_idle_noop", tf)
        }
        state = State.ARMED
        blacked = false
        lastObs = LockObservation.UNLOCKED
        pendingFireAtMs = 0L
        fallbackArmed = true
        fallbackStartMs = nowMs
        return result(EMPTY, "rewake", tf)
    }

    /** CONTROL_OP: a control-side user operation ("temporary use finished"). ARMED -> complete (fire when D==0); others -> no-op. */
    @Synchronized fun onControlOp(nowMs: Long): Result {
        val d = delaySec()
        val tf = fallbackThresholdSec()
        if (state != State.ARMED || blacked) {
            return noop(
                when {
                    state == State.IDLE -> "op_idle_noop"
                    blacked -> "op_blacked_noop"
                    else -> "op_pending_noop"
                },
                tf,
            )
        }
        if (d == 0) {
            return applyBlackout("fire", d, tf)
        }
        state = State.PENDING
        pendingFireAtMs = nowMs + d.toLong() * 1000L
        return result(EMPTY, "complete", tf)
    }

    /** onTick(now): 1s host tick. PENDING due -> fire; ARMED fallback due -> fire when last observation is LOCKED, else defer one T_f. */
    @Synchronized fun onTick(nowMs: Long): Result {
        val d = delaySec()
        val tf = fallbackThresholdSec()
        if (state == State.PENDING) {
            if (nowMs >= pendingFireAtMs) {
                return applyBlackout("fire", d, tf)
            }
            return noop("pending_wait", tf)
        }
        if (state == State.ARMED && fallbackEnabled() && fallbackArmed &&
            nowMs - fallbackStartMs >= tf.toLong() * 1000L
        ) {
            if (blacked) {
                return noop("fallback_blacked", tf)
            }
            if (lastObs == LockObservation.LOCKED) {
                return applyBlackout("fallback", d, tf)
            }
            fallbackStartMs = nowMs
            deferCount++
            return result(EMPTY, "defer", tf)
        }
        return noop("tick_noop", tf)
    }

    /** SESSION_LOST: link lost -> IDLE (no revive, no retry). */
    @Synchronized fun onSessionLost(): Result {
        state = State.IDLE
        blacked = false
        fallbackArmed = false
        pendingFireAtMs = 0L
        return result(EMPTY, "session_lost", fallbackThresholdSec())
    }

    // ================= internal =================

    private fun applyBlackout(event: String, d: Int, tf: Int): Result {
        state = State.ARMED
        blacked = true
        pendingFireAtMs = 0L
        fireCount++
        return Result(listOf(Action.APPLY_BLACKOUT), event, state, blacked, d, tf)
    }

    private fun noop(reason: String, tf: Int): Result = result(EMPTY, "noop:$reason", tf)

    private fun result(actions: List<Action>, event: String, tf: Int): Result =
        Result(actions, event, state, blacked, delaySec(), tf)

    private fun delaySec(): Int = PaymentBlackoutConfig.normalizeDelaySec(config.delaySecRaw())

    private fun fallbackThresholdSec(): Int =
        PaymentBlackoutConfig.normalizeFallbackSec(config.fallbackThresholdSecRaw())

    private fun fallbackEnabled(): Boolean =
        PaymentBlackoutConfig.normalizeFallbackEnabled(config.fallbackEnabledRaw())

    private companion object {
        private val EMPTY = emptyList<Action>()

        /** Default config when none injected (delay 0, fallback on, threshold 30) — never crash. */
        private val DEFAULT_CONFIG = object : ConfigSource {
            override fun delaySecRaw(): Int = PaymentBlackoutConfig.DELAY_DEFAULT_SEC
            override fun fallbackEnabledRaw(): Boolean = PaymentBlackoutConfig.FALLBACK_ENABLED_DEFAULT
            override fun fallbackThresholdSecRaw(): Int = PaymentBlackoutConfig.FALLBACK_THRESHOLD_DEFAULT_SEC
        }
    }
}
