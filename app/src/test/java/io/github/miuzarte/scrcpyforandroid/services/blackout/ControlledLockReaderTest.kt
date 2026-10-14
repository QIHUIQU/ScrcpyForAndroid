package io.github.miuzarte.scrcpyforandroid.services.blackout

import io.github.miuzarte.scrcpyforandroid.services.blackout.PaymentBlackoutStateMachine.LockObservation
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlledLockReaderTest {
    @Test
    fun parsesShowingFlag() {
        assertEquals(
            LockObservation.LOCKED,
            ControlledLockReader.parse(
                "WINDOW MANAGER POLICY STATE\n  KeyguardServiceDelegate\n    showing=true\n    showingAndNotOccluded=true",
            ),
        )
        assertEquals(
            LockObservation.UNLOCKED,
            ControlledLockReader.parse("  showing=false\n  secure=true"),
        )
    }

    @Test
    fun unknownOnFailureOrAmbiguity() {
        assertEquals(LockObservation.UNKNOWN, ControlledLockReader.parse(null))
        assertEquals(LockObservation.UNKNOWN, ControlledLockReader.parse(""))
        assertEquals(LockObservation.UNKNOWN, ControlledLockReader.parse("no relevant fields here"))
        assertEquals(LockObservation.UNKNOWN, ControlledLockReader.parse("showing=maybe"))
        // strict token boundary: a longer token must not match
        assertEquals(LockObservation.UNKNOWN, ControlledLockReader.parse("showing=truex"))
    }
}
