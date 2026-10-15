package io.github.miuzarte.scrcpyforandroid.services.keepalive

import android.app.ApplicationExitInfo
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

    private fun due(
        reason: Int,
        exitTimestampMs: Long,
        lastHandledExitMs: Long = 0L,
        nowMs: Long = 10_000_000L,
        recentAttemptCount: Int = 0,
    ) = CrashRecoveryPolicy.shouldAttempt(
        reason = reason,
        exitTimestampMs = exitTimestampMs,
        lastHandledExitMs = lastHandledExitMs,
        nowMs = nowMs,
        recentAttemptCount = recentAttemptCount,
    )

    @Test fun onlyFreshUnhandledAbnormalExitsRelaunch() {
        val now = 10_000_000L
        // every abnormal exit kind within the window => relaunch
        assertTrue("crash", due(ApplicationExitInfo.REASON_CRASH, now - 1_000))
        assertTrue("native crash", due(ApplicationExitInfo.REASON_CRASH_NATIVE, now - 1_000))
        assertTrue("anr", due(ApplicationExitInfo.REASON_ANR, now - 1_000))
        assertTrue("low memory", due(ApplicationExitInfo.REASON_LOW_MEMORY, now - 1_000))
        // normal/user exits never relaunch
        assertFalse("user requested", due(ApplicationExitInfo.REASON_USER_REQUESTED, now - 1_000))
        assertFalse("exit self", due(ApplicationExitInfo.REASON_EXIT_SELF, now - 1_000))
    }

    @Test fun staleHandledFutureAndCappedExitsAreDeclined() {
        val now = 10_000_000L
        // same death already handled => no repeat
        assertFalse("handled", due(ApplicationExitInfo.REASON_CRASH, now - 5_000, lastHandledExitMs = now - 1_000))
        // older than the 10-minute window => no
        assertFalse(
            "expired",
            due(ApplicationExitInfo.REASON_CRASH, now - CrashRecoveryPolicy.RECENT_WINDOW_MS - 1),
        )
        // future timestamp (clock skew) => no
        assertFalse("future", due(ApplicationExitInfo.REASON_CRASH, now + 1))
        // 3 attempts in 10 minutes => suppressed
        assertFalse("capped", due(ApplicationExitInfo.REASON_CRASH, now - 1_000, recentAttemptCount = 3))
        // two prior attempts still allow the third
        assertTrue("under cap", due(ApplicationExitInfo.REASON_CRASH, now - 1_000, recentAttemptCount = 2))
    }

    /**
     * 恢复前置门槛 (独立 `:a11y` 进程的触发源读共享 SharedPreferences 镜像, 不读 DataStore):
     * 有活跃会话、未被划掉、开关开着才考虑; 任一不满足都不动。
     */
    @Test fun recoveryGateNeedsActiveSessionNoSwipeAndToggleOn() {
        assertTrue(CrashRecoveryPolicy.shouldConsider(active = true, taskRemoved = false, recoveryEnabled = true))
        // 无会话(正常停止/普通启动) ⇒ 不回远控
        assertFalse(CrashRecoveryPolicy.shouldConsider(active = false, taskRemoved = false, recoveryEnabled = true))
        // 用户主动划掉 ⇒ 不拉起
        assertFalse(CrashRecoveryPolicy.shouldConsider(active = true, taskRemoved = true, recoveryEnabled = true))
        // 开关关闭 ⇒ 不动作
        assertFalse(CrashRecoveryPolicy.shouldConsider(active = true, taskRemoved = false, recoveryEnabled = false))
        // 冷进程未写镜像的默认值也是 false ⇒ 保守不动作
        assertFalse(CrashRecoveryPolicy.shouldConsider(active = false, taskRemoved = false, recoveryEnabled = false))
    }

    /** 缺失/损坏不是到达；旧回调不为已清账或更新派发制造通知。 */
    @Test fun recoveryArrivalObservedOnlyWhenMarkerMoves() {
        val token = 1792054933630L
        val pending = RecoveryArrival.Observation.NOT_OBSERVED
        val arrived = RecoveryArrival.Observation.ARRIVED
        val superseded = RecoveryArrival.Observation.SUPERSEDED
        assertEquals(pending, RecoveryArrival.observation("P$token", token))
        for (content in listOf(null, "", "bad", "Ainvalid", "A${token - 1}")) {
            assertEquals(pending, RecoveryArrival.observation(content, token))
        }
        assertEquals(arrived, RecoveryArrival.observation("A${token + 1}", token))
        assertEquals(superseded, RecoveryArrival.observation("A0", token))
        assertEquals(superseded, RecoveryArrival.observation("P${token + 1}", token))
    }

    @Test fun remotePageNeedsSessionResumedRouteAndFocusedWindow() {
        for (session in listOf(false, true)) for (resumed in listOf(false, true)) for (focused in listOf(false, true)) {
            assertEquals(session && resumed && focused, CrashRecoveryPolicy.remotePageVisible(session, resumed, focused))
        }
    }
}
