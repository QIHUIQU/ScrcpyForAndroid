package io.github.miuzarte.scrcpyforandroid.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockJumpControllerTest {
    @Test
    fun shouldJumpMatrix() {
        assertTrue(
            UnlockJumpController.shouldJump(
                enabled = true,
                sessionActive = true,
                streamInForeground = false,
            ),
        )
        assertFalse(
            UnlockJumpController.shouldJump(
                enabled = false,
                sessionActive = true,
                streamInForeground = false,
            ),
        )
        assertFalse(
            UnlockJumpController.shouldJump(
                enabled = true,
                sessionActive = false,
                streamInForeground = false,
            ),
        )
        assertFalse(
            UnlockJumpController.shouldJump(
                enabled = true,
                sessionActive = true,
                streamInForeground = true,
            ),
        )
    }

    @Test
    fun wakeTransitionMatrix() {
        // 屏幕从"不可用"变为"可用"才触发（轮询兜底）
        assertTrue(UnlockJumpController.shouldTriggerOnWakeTransition(usable = true, lastUsable = false))
        assertFalse(UnlockJumpController.shouldTriggerOnWakeTransition(usable = true, lastUsable = true))
        assertFalse(UnlockJumpController.shouldTriggerOnWakeTransition(usable = false, lastUsable = true))
        assertFalse(UnlockJumpController.shouldTriggerOnWakeTransition(usable = false, lastUsable = false))
    }
}
