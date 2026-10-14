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
    fun pendingJumpIsConsumeOnce() {
        UnlockJumpController.pendingJump = false
        assertFalse(UnlockJumpController.consumePendingJump())
        UnlockJumpController.pendingJump = true
        assertTrue(UnlockJumpController.consumePendingJump())
        assertFalse(UnlockJumpController.consumePendingJump())
        UnlockJumpController.onStreamUiShown()
        assertFalse(UnlockJumpController.pendingJump)
    }
}
