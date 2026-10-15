package io.github.miuzarte.scrcpyforandroid.services.blackout

import io.github.miuzarte.scrcpyforandroid.services.blackout.PaymentBlackoutStateMachine.DisplayObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentBlackoutStateMachineTest {

    private class TestConfig(
        var delay: Int = 0,
        var fallbackEnabled: Boolean = true,
        var fallbackSec: Int = 30,
    ) : PaymentBlackoutStateMachine.ConfigSource {
        override fun delaySecRaw(): Int = delay
        override fun fallbackEnabledRaw(): Boolean = fallbackEnabled
        override fun fallbackThresholdSecRaw(): Int = fallbackSec
    }

    @Test
    fun opBeforeLockIsNoop() {
        val m = PaymentBlackoutStateMachine(TestConfig())
        val r = m.onControlOp(1_000)
        assertEquals("noop:op_idle_noop", r.event())
        assertFalse(r.applied())
        assertEquals(0, m.fireCount())
    }

    @Test
    fun lockThenOpImmediateFiresOnce() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 0))
        m.onControlledLocked(0)
        val r = m.onControlOp(1_000)
        assertEquals("fire", r.event())
        assertTrue(r.applied())
        assertEquals(1, m.fireCount())
        assertTrue(m.blacked())
        val r2 = m.onControlOp(2_000)
        assertFalse(r2.applied())
        assertEquals(1, m.fireCount())
    }

    @Test
    fun delayedFireViaTick() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 10))
        m.onControlledLocked(0)
        val r = m.onControlOp(1_000)
        assertEquals("complete", r.event())
        assertFalse(r.applied())
        assertFalse(m.onTick(10_999).applied())
        val r2 = m.onTick(11_000)
        assertEquals("fire", r2.event())
        assertEquals(1, m.fireCount())
        assertFalse(m.onTick(12_000).applied())
    }

    /**
     * v8 semantics: a scheduled blackout is a commitment — a lock-state rewake
     * observation during PENDING must NOT cancel the countdown (it fires when due).
     * (Pre-v8 this cancelled the pending; that made any delay > 0 lose its
     * blackout whenever the 5s poll observed UNLOCKED inside the window —
     * e.g. stale observations or the user's own remote usage — while delay 0
     * never had a window to lose. See flow/踩坑记录.md 2026-10-14.)
     */
    @Test
    fun rewakeDuringPendingKeepsCountdown() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 10))
        m.onControlledLocked(0)
        m.onControlOp(1_000)
        val r = m.onControlledRewake(2_000)
        assertEquals("noop:rewake_pending_keep", r.event())
        assertEquals(PaymentBlackoutStateMachine.State.PENDING, m.state())
        assertFalse(m.onTick(10_999).applied())
        val r2 = m.onTick(11_000)
        assertEquals("fire", r2.event())
        assertTrue(r2.applied())
        assertEquals(1, m.fireCount())
    }

    @Test
    fun rewakeAfterFireStillResetsGateInArmed() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 0))
        m.onControlledLocked(0)
        m.onControlOp(1_000)
        assertEquals(1, m.fireCount())
        assertTrue(m.blacked())
        m.onControlledRewake(2_000)
        assertFalse(m.blacked())
        m.onControlOp(3_000)
        assertEquals(2, m.fireCount())
    }

    @Test
    fun sessionLostDuringPendingCancelsCountdown() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 10))
        m.onControlledLocked(0)
        m.onControlOp(1_000)
        m.onSessionLost()
        assertEquals(PaymentBlackoutStateMachine.State.IDLE, m.state())
        assertFalse(m.onTick(11_000).applied())
        assertEquals(0, m.fireCount())
    }

    @Test
    fun lockedDuringPendingIsNoop() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 10))
        m.onControlledLocked(0)
        m.onControlOp(1_000)
        val r = m.onControlledLocked(2_000)
        assertEquals("noop:locked_pending_noop", r.event())
    }

    @Test
    fun fallbackFiresWhenStillLocked() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 0, fallbackSec = 30))
        m.onControlledLocked(0)
        assertFalse(m.onTick(29_999).applied())
        val r = m.onTick(30_000)
        assertEquals("fallback", r.event())
        assertEquals(1, m.fireCount())
    }

    @Test
    fun fallbackDefersWhenUnlocked() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 0, fallbackSec = 30))
        m.onControlledLocked(0)
        m.onControlledRewake(1_000)
        assertFalse(m.onTick(31_000).applied())
        assertEquals(1, m.deferCount())
        assertFalse(m.onTick(61_000).applied())
        assertEquals(2, m.deferCount())
    }

    @Test
    fun fallbackDisabledNeverFires() {
        val m = PaymentBlackoutStateMachine(TestConfig(fallbackEnabled = false))
        m.onControlledLocked(0)
        assertFalse(m.onTick(60_000).applied())
        assertEquals(0, m.fireCount())
    }

    @Test
    fun newRoundAfterBlackoutResetsGate() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 0))
        m.onControlledLocked(0)
        m.onControlOp(1_000)
        assertEquals(1, m.fireCount())
        m.onControlledLocked(2_000)
        assertFalse(m.blacked())
        m.onControlOp(3_000)
        assertEquals(2, m.fireCount())
    }

    @Test
    fun sessionLostResetsToIdle() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 0))
        m.onControlledLocked(0)
        m.onSessionLost()
        assertEquals(PaymentBlackoutStateMachine.State.IDLE, m.state())
        assertFalse(m.onControlOp(1_000).applied())
    }

    @Test
    fun configNormalization() {
        assertEquals(0, PaymentBlackoutConfig.normalizeDelaySec(-1))
        assertEquals(30, PaymentBlackoutConfig.normalizeDelaySec(31))
        assertEquals(7, PaymentBlackoutConfig.normalizeDelaySec(7))
        assertEquals(30, PaymentBlackoutConfig.normalizeFallbackSec(15))
        assertEquals(60, PaymentBlackoutConfig.normalizeFallbackSec(60))
        assertEquals(300, PaymentBlackoutConfig.normalizeFallbackSec(300))
        assertTrue(PaymentBlackoutConfig.normalizeFallbackEnabled(null))
        assertFalse(PaymentBlackoutConfig.normalizeFallbackEnabled(false))
    }

    // ================= v15: the blacked gate follows the controlled device's real panel ==========

    /**
     * v15 regression (the reported payment scenario): once a blackout fired, the latch could only
     * be cleared by a lock-state *transition* observation, so a user who used the phone directly
     * (panel back on) and left it unlocked kept every later control op at `op_blacked_noop` — the
     * blackout never came back for the rest of the session. The real panel power clears it.
     */
    @Test
    fun displayBackOnAfterConfirmedBlackoutFiresAgain() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 0))
        m.onControlledLocked(0)
        assertEquals("fire", m.onControlOp(1_000).event())
        assertTrue(m.blacked())
        // the real panel went off (blackout in effect)
        assertEquals("blackout_confirmed", m.onDisplayObserved(DisplayObservation.OFF, 2_000).event())
        // the user brought the phone screen back (external use, no lock-state change at all)
        val r = m.onDisplayObserved(DisplayObservation.ON, 3_000)
        assertEquals("rewake_display_on", r.event())
        assertFalse(m.blacked())
        // ... and the next "returned to the control side" op blacks out again
        val r2 = m.onControlOp(4_000)
        assertEquals("fire", r2.event())
        assertTrue(r2.applied())
        assertEquals(2, m.fireCount())
    }

    /** "未变 off 不得重复 fire": while the panel is still off the latch must keep holding. */
    @Test
    fun opWhilePanelStillOffDoesNotFireAgain() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 0))
        m.onControlledLocked(0)
        m.onControlOp(1_000)
        m.onDisplayObserved(DisplayObservation.OFF, 2_000)
        m.onDisplayObserved(DisplayObservation.OFF, 3_000)
        val r = m.onControlOp(4_000)
        assertEquals("noop:op_blacked_noop", r.event())
        assertFalse(r.applied())
        assertEquals(1, m.fireCount())
    }

    /**
     * The setDisplayPower command is asynchronous: the first panel sample after a fire may still
     * read On (stale). It must NOT clear the latch (all clocks < 5000 ms => no time-based escape).
     */
    @Test
    fun panelOnBeforeBlackoutWasSeenOffNeverResets() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 0))
        m.onControlledLocked(0)
        m.onControlOp(1_000)
        val stale = m.onDisplayObserved(DisplayObservation.ON, 1_001)
        assertEquals("noop:display_on_preconfirm_noop", stale.event())
        assertTrue(m.blacked())
        val r = m.onControlOp(2_000)
        assertEquals("noop:op_blacked_noop", r.event())
        assertEquals(1, m.fireCount())
        // once the blackout is seen in effect the same On observation is a real external wake
        m.onDisplayObserved(DisplayObservation.OFF, 2_001)
        assertEquals("rewake_display_on", m.onDisplayObserved(DisplayObservation.ON, 2_002).event())
        assertFalse(m.blacked())
        assertEquals("fire", m.onControlOp(2_003).event())
    }

    /** 未知采样不重置: a doubtful dump must never clear the latch or repeat the blackout. */
    @Test
    fun unknownDisplayObservationNeverResets() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 0))
        m.onControlledLocked(0)
        m.onControlOp(1_000)
        m.onDisplayObserved(DisplayObservation.OFF, 2_000)
        assertEquals("noop:display_unknown_noop", m.onDisplayObserved(DisplayObservation.UNKNOWN, 3_000).event())
        assertTrue(m.blacked())
        assertEquals("noop:op_blacked_noop", m.onControlOp(4_000).event())
        assertEquals(1, m.fireCount())
    }

    /** Panel samples outside a latched blackout must stay inert (no arm/defer side effects). */
    @Test
    fun displayObservationsAreInertWhileNotBlacked() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 0))
        m.onControlledLocked(0)
        assertEquals("noop:display_on_noop", m.onDisplayObserved(DisplayObservation.ON, 1_000).event())
        assertEquals("noop:display_off_noop", m.onDisplayObserved(DisplayObservation.OFF, 1_001).event())
        assertEquals(0, m.fireCount())
        assertFalse(m.blacked())
        assertEquals(PaymentBlackoutStateMachine.State.ARMED, m.state())
        // the panel state never fires on its own; only a control op does
        assertEquals("fire", m.onControlOp(1_002).event())
        assertEquals(1, m.fireCount())
    }

    /** 会话代际: session loss clears the latch and the confirmation (fresh session, fresh round). */
    @Test
    fun sessionLostClearsBlackoutConfirmation() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 0))
        m.onControlledLocked(0)
        m.onControlOp(1_000)
        m.onDisplayObserved(DisplayObservation.OFF, 2_000)
        m.onSessionLost()
        m.onControlledLocked(3_000)
        m.onControlOp(4_000)
        assertEquals(2, m.fireCount())
        // the new round's latch is unconfirmed again: an On sample cannot clear it by itself
        assertEquals("noop:display_on_preconfirm_noop", m.onDisplayObserved(DisplayObservation.ON, 5_000).event())
        assertTrue(m.blacked())
    }

    /** v8 kept: the countdown is a commitment, a rewake observation inside PENDING must not cancel it. */
    @Test
    fun pendingCommitmentAndDisplaySamplesInteractSafely() {
        val m = PaymentBlackoutStateMachine(TestConfig(delay = 10))
        m.onControlledLocked(0)
        m.onControlOp(1_000)
        assertEquals(PaymentBlackoutStateMachine.State.PENDING, m.state())
        assertEquals("noop:display_on_noop", m.onDisplayObserved(DisplayObservation.ON, 2_000).event())
        assertEquals("noop:rewake_pending_keep", m.onControlledRewake(3_000).event())
        assertEquals("fire", m.onTick(11_000).event())
        assertTrue(m.blacked())
    }
}
