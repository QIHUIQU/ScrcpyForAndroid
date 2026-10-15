package io.github.miuzarte.scrcpyforandroid.services

import io.github.miuzarte.scrcpyforandroid.models.ConnectionTarget
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-7: a failed keepalive probe is retried once in place (no intervalMs wait); only when the
 * retry also fails is the link declared dead and the existing reconnect path taken.
 */
class DeviceAdbBackgroundRunnerTest {
    @Test
    fun healthyProbeNeverDeclaresDead() = runBlocking {
        var probes = 0
        val target = ConnectionTarget("test")
        DeviceAdbBackgroundRunner().use { runner ->
            withTimeout(2_000) {
                runner.runKeepAliveLoop(
                    sessionState = { DeviceAdbSessionState(isConnected = probes < 3, currentTarget = target) },
                    isForeground = { true }, intervalMs = 1,
                    keepAliveCheck = { _, _ -> probes++; true },
                    reconnect = { _, _ -> error("Healthy link must not reconnect") },
                    onReconnectSuccess = { _, _ -> error("Not expected") },
                    onReconnectFailure = { throw it },
                    shouldAutoReconnect = { error("Healthy link must not declare dead") },
                )
            }
        }
        assertEquals(3, probes)
    }

    @Test
    fun immediateRetryRecoversTransientFailure() = runBlocking {
        var probes = 0
        val target = ConnectionTarget("test")
        val results = listOf(false, true)
        DeviceAdbBackgroundRunner().use { runner ->
            withTimeout(2_000) {
                runner.runKeepAliveLoop(
                    sessionState = { DeviceAdbSessionState(isConnected = probes < 2, currentTarget = target) },
                    isForeground = { true }, intervalMs = 1,
                    keepAliveCheck = { _, _ -> results[probes++] },
                    reconnect = { _, _ -> error("A retried failure must not reconnect") },
                    onReconnectSuccess = { _, _ -> error("Not expected") },
                    onReconnectFailure = { throw it },
                    shouldAutoReconnect = { error("Transient failure must not declare dead") },
                )
            }
        }
        // Exactly two probes (original + immediate retry), no reconnect.
        assertEquals(2, probes)
    }

    @Test
    fun consecutiveFailedProbesDeclareDead() = runBlocking {
        var probes = 0
        var deadDecisions = 0
        val target = ConnectionTarget("test")
        val results = listOf(false, false)
        DeviceAdbBackgroundRunner().use { runner ->
            withTimeout(2_000) {
                runner.runKeepAliveLoop(
                    sessionState = { DeviceAdbSessionState(isConnected = true, currentTarget = target) },
                    isForeground = { true }, intervalMs = 1,
                    keepAliveCheck = { _, _ -> results[probes++] },
                    reconnect = { _, _ -> error("must not run when auto-reconnect is disabled") },
                    onReconnectSuccess = { _, _ -> error("Not expected") },
                    onReconnectFailure = { throw it },
                    shouldAutoReconnect = { deadDecisions++; false },
                )
            }
        }
        assertEquals(2, probes)
        assertEquals(1, deadDecisions)
    }

    @Test
    fun retryHappensWithoutWaitingTheNextInterval() = runBlocking {
        var probes = 0
        val target = ConnectionTarget("test")
        val timestamps = mutableListOf<Long>()
        val results = listOf(false, true)
        val intervalMs = 300L
        DeviceAdbBackgroundRunner().use { runner ->
            withTimeout(2_000) {
                runner.runKeepAliveLoop(
                    sessionState = { DeviceAdbSessionState(isConnected = probes < 2, currentTarget = target) },
                    isForeground = { true }, intervalMs = intervalMs,
                    keepAliveCheck = { _, _ -> timestamps += System.nanoTime(); results[probes++] },
                    reconnect = { _, _ -> error("Transient failure must not reconnect") },
                    onReconnectSuccess = { _, _ -> error("Not expected") },
                    onReconnectFailure = { throw it },
                    shouldAutoReconnect = { error("Transient failure must not declare dead") },
                )
            }
        }
        assertEquals(2, timestamps.size)
        val gapMs = (timestamps[1] - timestamps[0]) / 1_000_000
        // The retry must fire back-to-back; if it waited intervalMs the gap would be >= 300ms.
        assertTrue("retry gap ${gapMs}ms must be well under intervalMs=${intervalMs}ms", gapMs < 150)
    }

    @Test
    fun cancellationReleasesTheLoop() = runBlocking {
        var started = false
        val target = ConnectionTarget("test")
        val job = launch {
            DeviceAdbBackgroundRunner().use { runner ->
                runner.runKeepAliveLoop(
                    sessionState = { DeviceAdbSessionState(isConnected = true, currentTarget = target) },
                    isForeground = { started = true; true }, intervalMs = 60_000,
                    keepAliveCheck = { _, _ -> error("probe must not run before the interval elapses") },
                    reconnect = { _, _ -> error("Not expected") },
                    onReconnectSuccess = { _, _ -> error("Not expected") },
                    onReconnectFailure = { throw it },
                    shouldAutoReconnect = { error("Not expected") },
                )
            }
        }
        withTimeout(2_000) { while (!started) delay(1) }
        // Cancelling during the interval delay must unwind withContext and close the runner.
        job.cancelAndJoin()
    }
}
