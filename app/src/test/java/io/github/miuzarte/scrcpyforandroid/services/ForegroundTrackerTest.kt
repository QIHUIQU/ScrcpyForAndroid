package io.github.miuzarte.scrcpyforandroid.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTrackerTest {
    @Test
    fun consumeOnce() {
        val tracker = ForegroundTracker()
        tracker.onActivityStarted()
        assertTrue(tracker.consumeReentry())
        assertFalse(tracker.consumeReentry())
    }

    @Test
    fun inAppNavigationIsNotReentry() {
        val tracker = ForegroundTracker()
        tracker.onActivityStarted() // MainActivity
        assertTrue(tracker.consumeReentry())
        tracker.onActivityStarted() // StreamActivity 压栈 (应用仍在前台)
        assertFalse(tracker.consumeReentry())
    }

    @Test
    fun afterAllStoppedNextStartIsReentry() {
        val tracker = ForegroundTracker()
        tracker.onActivityStarted()
        tracker.consumeReentry()
        tracker.onActivityStopped() // 应用退到后台
        tracker.onActivityStarted() // 从后台回到前台
        assertTrue(tracker.consumeReentry())
    }

    @Test
    fun configChangeStartIsNotReentry() {
        val tracker = ForegroundTracker()
        tracker.onActivityStarted()
        tracker.consumeReentry()
        tracker.onActivityStopped()
        tracker.onActivityStarted(configChange = true) // 配置变更重建
        assertFalse(tracker.consumeReentry())
    }

    @Test
    fun backOutOfStreamIsNotReentry() {
        val tracker = ForegroundTracker()
        tracker.onActivityStarted() // MainActivity
        tracker.onActivityStarted() // StreamActivity
        tracker.consumeReentry()
        tracker.onActivityStopped() // StreamActivity finish -> 回到 MainActivity (应用仍在前台)
        assertFalse(tracker.consumeReentry())
    }
}
