package io.github.miuzarte.scrcpyforandroid.services.keepalive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessKeepAliveRulesTest {
    @Test
    fun intervalRespectsPlatformLowerBound() {
        assertTrue(ProcessKeepAliveRules.PERIODIC_INTERVAL_MS >= 15L * 60L * 1000L)
    }

    @Test
    fun serviceDecision() {
        assertTrue(ProcessKeepAliveRules.shouldRunService(enabled = true, sessionActive = true))
        assertFalse(ProcessKeepAliveRules.shouldRunService(enabled = false, sessionActive = true))
        assertFalse(ProcessKeepAliveRules.shouldRunService(enabled = true, sessionActive = false))
    }
}
