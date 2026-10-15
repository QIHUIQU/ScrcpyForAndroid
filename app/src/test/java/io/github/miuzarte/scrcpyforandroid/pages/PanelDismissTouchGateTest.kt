package io.github.miuzarte.scrcpyforandroid.pages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P19.8: when the scale/gamma panel is visible the first video-area ACTION_DOWN dismisses it and
 * the entire gesture is swallowed; with no panel visible the existing touch path is untouched.
 */
class PanelDismissTouchGateTest {
    private companion object {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val CANCEL = 3
        const val POINTER_DOWN = 5
        const val POINTER_UP = 6
    }

    @Test
    fun noVisiblePanelLeavesExistingPathUntouched() {
        val gate = PanelDismissTouchGate()
        assertFalse(gate.shouldConsume(DOWN, panelsVisible = false))
        assertFalse(gate.shouldConsume(MOVE, panelsVisible = false))
        assertFalse(gate.shouldConsume(UP, panelsVisible = false))
        assertFalse(gate.shouldConsume(CANCEL, panelsVisible = false))
    }

    @Test
    fun firstVideoDownClosesAndSwallowsWholeGesture() {
        val gate = PanelDismissTouchGate()
        // DOWN is the dismiss trigger and is not forwarded.
        assertTrue(gate.shouldConsume(DOWN, panelsVisible = true))
        // The panel is now closed, but the rest of the gesture must still be swallowed, otherwise
        // an orphaned UP reaches the controlled device.
        assertTrue(gate.shouldConsume(MOVE, panelsVisible = false))
        assertTrue(gate.shouldConsume(POINTER_DOWN, panelsVisible = false))
        assertTrue(gate.shouldConsume(POINTER_UP, panelsVisible = false))
        assertTrue(gate.shouldConsume(UP, panelsVisible = false))
        // Swallow ended: the next gesture uses the existing path again.
        assertFalse(gate.shouldConsume(DOWN, panelsVisible = false))
    }

    @Test
    fun cancelEndsSwallowSoNoStrayConsumption() {
        val gate = PanelDismissTouchGate()
        assertTrue(gate.shouldConsume(DOWN, panelsVisible = true))
        assertTrue(gate.shouldConsume(CANCEL, panelsVisible = false))
        assertFalse(gate.shouldConsume(DOWN, panelsVisible = false))
    }

    @Test
    fun repeatedDownWithoutVisiblePanelIsNeverConsumed() {
        val gate = PanelDismissTouchGate()
        assertFalse(gate.shouldConsume(DOWN, panelsVisible = false))
        assertFalse(gate.shouldConsume(UP, panelsVisible = false))
        assertFalse(gate.shouldConsume(DOWN, panelsVisible = false))
    }
}
