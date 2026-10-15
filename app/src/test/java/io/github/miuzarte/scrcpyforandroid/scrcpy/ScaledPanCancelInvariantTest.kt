package io.github.miuzarte.scrcpyforandroid.scrcpy

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM source invariant, not a MotionEvent/device simulation (no new Android test dependency).
 * Round14 re-restores P1-5 per the 2026-10-14 reference audit; supersedes Round13's revert.
 */
class ScaledPanCancelInvariantTest {
    @Test
    fun multiPointerCancelResetsPanBeforeAnyNewPanCanStart() {
        val source = File("src/main/java/io/github/miuzarte/scrcpyforandroid/scrcpy/TouchEventHandler.kt").readText()
        val pan = source.substringAfter("private fun handlePanGesture").substringBefore("fun handleMotionEvent")
        val guard = pan.indexOf("if (scale <= 1f || isMouseLikeEvent(event)) return false")
        val multi = pan.indexOf("if (event.pointerCount >= 2)")
        val cancel = pan.indexOf("if (event.actionMasked == MotionEvent.ACTION_CANCEL)", multi)
        val start = pan.indexOf("if (!panning)", multi)
        assertTrue("1x/mouse paths must bypass this change", guard >= 0 && guard < multi)
        assertTrue("CANCEL must be handled before pan start, even if already panning", cancel > multi && cancel < start)
        val block = pan.substring(cancel, start)
        assertTrue(block.contains("handleCancelAction(calculateContentBounds(), UiMotionActions.CANCEL)"))
        assertFalse("CANCEL must not become a click", block.contains("UiMotionActions.UP"))
        val reset = block.indexOf("panning = false")
        val centroid = block.indexOf("previousCentroid = null")
        val returned = block.indexOf("return true")
        assertTrue("every >=2 finger CANCEL leaves panning=false and no centroid before returning", reset >= 0 && centroid > reset && returned > centroid)
        assertFalse("CANCEL must not re-latch pan", block.contains("panning = true"))
        assertTrue("next single-finger DOWN must fall through when pan is reset", pan.trimEnd().endsWith("return false\n    }"))
    }
}
