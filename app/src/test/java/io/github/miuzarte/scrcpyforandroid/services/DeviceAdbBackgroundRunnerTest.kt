package io.github.miuzarte.scrcpyforandroid.services

import io.github.miuzarte.scrcpyforandroid.models.ConnectionTarget
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAdbBackgroundRunnerTest {
    @Test fun successResetsConsecutiveFailures() = runBlocking {
        val results = listOf(false, false, true, false, false, false)
        var probes = 0
        var deadDecisions = 0
        DeviceAdbBackgroundRunner().use { runner ->
            withTimeout(2_000) {
                runner.runKeepAliveLoop(
                    sessionState = { DeviceAdbSessionState(isConnected = true, currentTarget = ConnectionTarget("test")) },
                    isForeground = { true }, intervalMs = 1,
                    keepAliveCheck = { _, _ -> results[probes++] },
                    reconnect = { _, _ -> error("Auto reconnect disabled") },
                    onReconnectSuccess = { _, _ -> error("Not expected") },
                    onReconnectFailure = { throw it },
                    shouldAutoReconnect = { deadDecisions++; false },
                )
            }
        }
        assertEquals(6, probes)
        assertEquals(1, deadDecisions)
    }

    @Test fun singleFailureThenSuccessDoesNotDeclareDead() = runBlocking {
        var probes = 0
        val target = ConnectionTarget("test")
        DeviceAdbBackgroundRunner().use { runner ->
            withTimeout(2_000) {
                runner.runKeepAliveLoop(
                    sessionState = { DeviceAdbSessionState(isConnected = probes < 2, currentTarget = target) },
                    isForeground = { true }, intervalMs = 1,
                    keepAliveCheck = { _, _ -> ++probes == 2 },
                    reconnect = { _, _ -> error("Single failure must not reconnect") },
                    onReconnectSuccess = { _, _ -> error("Not expected") },
                    onReconnectFailure = { throw it },
                    shouldAutoReconnect = { error("Single failure must not declare dead") },
                )
            }
        }
        assertEquals(2, probes)
    }
}
