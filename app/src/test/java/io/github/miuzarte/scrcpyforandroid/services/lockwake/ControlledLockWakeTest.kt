package io.github.miuzarte.scrcpyforandroid.services.lockwake

import io.github.miuzarte.scrcpyforandroid.services.lockwake.ControlledLockWake.LockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlledLockWakeTest {
    private val windowLockedSecure =
        "  KeyguardServiceDelegate\n    showing=true\n    secure=true\n    mSimSecure=false\n"
    private val windowLockedInsecure =
        "  KeyguardServiceDelegate\n    showing=true\n    secure=false\n    mSimSecure=false\n"
    private val windowUnlocked =
        "  KeyguardServiceDelegate\n    showing=false\n    secure=false\n    mSimSecure=false\n"
    private val trustVivo = "  deviceLocked=0, trustState=TRUSTED, strongAuthRequired=0x0\n"
    private val trustRealSecure =
        "  deviceLocked=1, trustState=UNTRUSTED, strongAuthRequired=0x2\n"

    @Test
    fun parseWindowPolicyStateMatrix() {
        assertEquals(LockState.LOCKED, ControlledLockWake.parseWindowPolicyState(windowLockedSecure))
        assertEquals(LockState.UNLOCKED, ControlledLockWake.parseWindowPolicyState(windowUnlocked))
        assertEquals(LockState.UNKNOWN, ControlledLockWake.parseWindowPolicyState(null))
        assertEquals(LockState.UNKNOWN, ControlledLockWake.parseWindowPolicyState(""))
        // mIsShowing= 是 camelCase，不得命中（原项目口径）
        assertEquals(
            LockState.UNKNOWN,
            ControlledLockWake.parseWindowPolicyState("  KeyguardServiceDelegate\n    mIsShowing=true\n"),
        )
        // delegate 行之前的取值不认
        assertEquals(
            LockState.UNKNOWN,
            ControlledLockWake.parseWindowPolicyState("  showing=true\n  KeyguardServiceDelegate\n"),
        )
    }

    @Test
    fun parseSampleFields() {
        val s = ControlledLockWake.parseSample(windowLockedSecure, trustVivo)
        assertEquals(LockState.LOCKED, s.state)
        assertEquals("true", s.secure)
        assertEquals("false", s.simSecure)
        assertEquals("0", s.deviceLocked)
        assertEquals("TRUSTED", s.trustState)
        assertEquals("0x0", s.strongAuthRequired)
        assertFalse(s.hasRealSecureLock)
    }

    @Test
    fun realSecureLockDetection() {
        assertTrue(ControlledLockWake.parseSample(windowLockedSecure, trustRealSecure).hasRealSecureLock)
        assertTrue(
            ControlledLockWake.parseSample(windowLockedSecure, "  deviceLocked=0, strongAuthRequired=0x2\n")
                .hasRealSecureLock,
        )
        assertTrue(
            ControlledLockWake.parseSample(
                "  KeyguardServiceDelegate\n    showing=true\n    secure=false\n    mSimSecure=true\n",
                trustVivo,
            ).hasRealSecureLock,
        )
    }

    @Test
    fun gateVivoSecureTrueAuthorized() {
        val s = ControlledLockWake.parseSample(windowLockedSecure, trustVivo)
        val gate = ControlledLockWake.dismissGate(listOf(s, s), sentSessions = 1)
        assertTrue(gate.authorized)
        assertEquals(ControlledLockWake.REASON_AUTHORIZED, gate.reason)
    }

    @Test
    fun gateInsecureLockAuthorized() {
        val s = ControlledLockWake.parseSample(windowLockedInsecure, trustVivo)
        val gate = ControlledLockWake.dismissGate(listOf(s, s), sentSessions = 1)
        assertTrue(gate.authorized)
        assertEquals(ControlledLockWake.REASON_AUTHORIZED, gate.reason)
    }

    @Test
    fun gateRealSecureRejected() {
        val s = ControlledLockWake.parseSample(windowLockedSecure, trustRealSecure)
        val gate = ControlledLockWake.dismissGate(listOf(s, s), sentSessions = 1)
        assertFalse(gate.authorized)
        assertEquals(ControlledLockWake.REASON_REAL_SECURE, gate.reason)
    }

    @Test
    fun gateSentZeroAndNotLockedRejected() {
        val locked = ControlledLockWake.parseSample(windowLockedSecure, trustVivo)
        assertEquals(
            ControlledLockWake.REASON_SENT_ZERO,
            ControlledLockWake.dismissGate(listOf(locked), sentSessions = 0).reason,
        )
        val unlocked = ControlledLockWake.parseSample(windowUnlocked, trustVivo)
        val gate = ControlledLockWake.dismissGate(listOf(unlocked), sentSessions = 1)
        assertFalse(gate.authorized)
        assertEquals(ControlledLockWake.REASON_NOT_LOCKED, gate.reason)
    }

    @Test
    fun gateInconsistentSamplesRejected() {
        val a = ControlledLockWake.parseSample(windowLockedSecure, trustVivo)
        val b = ControlledLockWake.parseSample(windowUnlocked, trustVivo)
        val gate = ControlledLockWake.dismissGate(listOf(a, b), sentSessions = 1)
        assertFalse(gate.authorized)
        assertEquals(ControlledLockWake.REASON_V3_INCONSISTENT, gate.reason)
    }

    @Test
    fun gateUnknownConservativeRejected() {
        val unknown = ControlledLockWake.parseSample(null, null)
        val gate = ControlledLockWake.dismissGate(listOf(unknown), sentSessions = 1)
        assertFalse(gate.authorized)
        assertEquals(ControlledLockWake.REASON_V2_CONSERVATIVE, gate.reason)
    }
}
