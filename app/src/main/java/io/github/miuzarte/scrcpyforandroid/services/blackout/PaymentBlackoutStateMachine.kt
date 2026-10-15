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
 * ARMED (cancels a not-yet-committed blackout) — but once an op has committed a delayed
 * blackout (PENDING), a re-wake observation does NOT cancel the countdown (v8: only
 * session loss cancels; lock observations in the window are routinely stale or caused by
 * the user's own remote usage — see flow/踩坑记录.md 2026-10-14); one blackout per round
 * (blacked gate); config is re-read on every decision; clock is injected via nowMs (pure
 * logic, JVM-testable).
 *
 * v15 boundary: the [blacked] gate is also cleared by the controlled device's REAL panel power
 * (see [onDisplayObserved]) — a lock-state observation alone left it stuck whenever the user used
 * the phone directly and left it unlocked (the payment flow), silently killing every later
 * blackout for the rest of the session.
 */
class PaymentBlackoutStateMachine(config: ConfigSource?) {

    enum class State { IDLE, ARMED, PENDING }

    enum class Action { APPLY_BLACKOUT }

    enum class LockObservation { UNKNOWN, LOCKED, UNLOCKED }

    /** Controlled device's real panel power (SurfaceFlinger `powerMode`), see [onDisplayObserved]. */
    enum class DisplayObservation { UNKNOWN, ON, OFF }

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
    private var blackoutConfirmed = false
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
        blackoutConfirmed = false
        lastObs = LockObservation.LOCKED
        fallbackArmed = true
        fallbackStartMs = nowMs
        return result(EMPTY, "arm", tf)
    }

    /**
     * CONTROLLED_REWAKE: the controlled device woke again (user went back). 
     * IDLE -> no-op; PENDING -> no-op (v8: a scheduled blackout is a commitment — lock-state
     * observations during the countdown must not cancel it; they are routinely stale (5 s poll)
     * or caused by the user's own remote usage, which silently voided every delay > 0);
     * else -> ARMED, blacked reset, fallback restart.
     */
    @Synchronized fun onControlledRewake(nowMs: Long): Result {
        val tf = fallbackThresholdSec()
        if (state == State.IDLE) {
            return noop("rewake_idle_noop", tf)
        }
        if (state == State.PENDING) {
            // v8 commit semantics: keep the countdown (fires on tick). Only session_lost cancels.
            return noop("rewake_pending_keep", tf)
        }
        state = State.ARMED
        blacked = false
        blackoutConfirmed = false
        lastObs = LockObservation.UNLOCKED
        pendingFireAtMs = 0L
        fallbackArmed = true
        fallbackStartMs = nowMs
        return result(EMPTY, "rewake", tf)
    }

    /**
     * DISPLAY: the controlled device's real panel power (SurfaceFlinger `powerMode` — the only
     * authoritative blackout signal; framework screen state / `dumpsys display` / screenshots do
     * NOT change on a blackout, see flow/踩坑记录.md 2026-10-14/v10).
     *
     * v15: the [blacked] latch — "one blackout per round" — used to be clearable only through a
     * lock-state transition observation, so it stayed stuck for the rest of the session whenever
     * the user used the phone directly and left it unlocked (the payment flow): every later
     * control op was `op_blacked_noop` and the feature silently died (see flow/round15-*.md).
     * The physical signal is the panel itself:
     *   Off while blacked            -> the blackout is confirmed in effect;
     *   On  while blacked+confirmed  -> the user brought the screen back (external use) = the round
     *                                   is over: clear the latch so the next op may black out again;
     *   On  before any Off was seen  -> the pre-command/stale sample of the round that just fired
     *                                   (the setDisplayPower command is still in flight): never a
     *                                   reset, otherwise every op would re-fire on a stale sample;
     *   UNKNOWN                      -> never acted upon (never reset on a doubtful dump).
     */
    @Synchronized fun onDisplayObserved(obs: DisplayObservation, nowMs: Long): Result {
        val tf = fallbackThresholdSec()
        return when {
            obs == DisplayObservation.UNKNOWN -> noop("display_unknown_noop", tf)
            !blacked -> noop(
                if (obs == DisplayObservation.ON) "display_on_noop" else "display_off_noop",
                tf,
            )
            obs == DisplayObservation.OFF -> {
                if (blackoutConfirmed) {
                    noop("blackout_off_still", tf)
                } else {
                    blackoutConfirmed = true
                    result(EMPTY, "blackout_confirmed", tf)
                }
            }
            !blackoutConfirmed -> noop("display_on_preconfirm_noop", tf)
            else -> {
                blacked = false
                blackoutConfirmed = false
                fallbackArmed = true
                fallbackStartMs = nowMs
                result(EMPTY, "rewake_display_on", tf)
            }
        }
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
        blackoutConfirmed = false
        fallbackArmed = false
        pendingFireAtMs = 0L
        return result(EMPTY, "session_lost", fallbackThresholdSec())
    }

    // ================= internal =================

    private fun applyBlackout(event: String, d: Int, tf: Int): Result {
        state = State.ARMED
        blacked = true
        blackoutConfirmed = false
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
