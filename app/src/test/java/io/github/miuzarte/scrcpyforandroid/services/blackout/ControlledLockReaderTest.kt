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

    // Trimmed shape of a real `dumpsys SurfaceFlinger --displays` on the vivo V2436A (foldable):
    // two physical panels (inner 2480x2200 + outer 1172x2748) plus the scrcpy virtual display.
    private fun physicalDump(inner: String, outer: String) = """
        Display 4630946685006800003
            port=131
            connectionType=Internal
            name=""
            powerMode=$inner
            optimizationPolicy=optimizeForPerformance
            activeMode={id=4, hwcId=4, resolution=2480x2200, vsyncRate=60.00 Hz}
            idleTimer=
        Display 4630946685006800004
            port=132
            connectionType=Internal
            name=""
            powerMode=$outer
            activeMode={id=5, hwcId=5, resolution=1172x2748, vsyncRate=60.00 Hz}
        Virtual Display 11529215046088367904
            name="scrcpy"
            powerMode=On
            isGpu=true
            uniqueId=virtual:com.android.shell,2000,scrcpy,812
    """.trimIndent()

    @Test
    fun displayPowerOffOnlyWhenEveryPhysicalPanelIsOff() {
        assertEquals(DISPLAY_OFF, ControlledLockReader.parseDisplayPower(physicalDump("Off", "Off")))
        assertEquals(DISPLAY_ON, ControlledLockReader.parseDisplayPower(physicalDump("Off", "On")))
        assertEquals(DISPLAY_ON, ControlledLockReader.parseDisplayPower(physicalDump("On", "Off")))
    }

    @Test
    fun displayPowerIgnoresVirtualDisplays() {
        // the scrcpy virtual display is always "On"; it must never be mistaken for a physical panel
        val virtualOnly = "Virtual Display 11529215046088367904\n    name=\"scrcpy\"\n    powerMode=On\n"
        assertEquals(DISPLAY_UNKNOWN, ControlledLockReader.parseDisplayPower(virtualOnly))
        // no physical header at all (unknown shape) => UNKNOWN, never acted upon
        assertEquals(DISPLAY_UNKNOWN, ControlledLockReader.parseDisplayPower("random output\n  powerMode=On\n"))
        assertEquals(DISPLAY_UNKNOWN, ControlledLockReader.parseDisplayPower(null))
        assertEquals(DISPLAY_UNKNOWN, ControlledLockReader.parseDisplayPower(""))
    }

    private companion object {
        val DISPLAY_UNKNOWN = PaymentBlackoutStateMachine.DisplayObservation.UNKNOWN
        val DISPLAY_ON = PaymentBlackoutStateMachine.DisplayObservation.ON
        val DISPLAY_OFF = PaymentBlackoutStateMachine.DisplayObservation.OFF
    }
}
