package io.github.miuzarte.scrcpyforandroid.services.blackout

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
}
