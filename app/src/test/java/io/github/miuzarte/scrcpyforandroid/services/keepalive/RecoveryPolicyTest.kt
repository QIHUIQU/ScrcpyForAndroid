package io.github.miuzarte.scrcpyforandroid.services.keepalive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPolicyTest {
    @Test fun threeAttemptsInTenMinutesAndExpiry() {
        assertEquals(3, RecoveryPolicy.recentAttempts(1_000_000L, "900000,950000,999999,garbage,1").size)
        assertEquals(0, RecoveryPolicy.recentAttempts(1_600_001L, "900000,950000,999999").size)
    }

    @Test fun serviceRunsForSessionOrPendingRecovery() {
        assertTrue(ProcessKeepAliveRules.shouldRunService(enabled = true, sessionActive = true))
        assertTrue(
            ProcessKeepAliveRules.shouldRunService(
                enabled = true, sessionActive = false, recoveryPending = true,
            ),
        )
        assertFalse(ProcessKeepAliveRules.shouldRunService(enabled = true, sessionActive = false))
        assertFalse(
            ProcessKeepAliveRules.shouldRunService(
                enabled = false, sessionActive = false, recoveryPending = true,
            ),
        )
    }
}
