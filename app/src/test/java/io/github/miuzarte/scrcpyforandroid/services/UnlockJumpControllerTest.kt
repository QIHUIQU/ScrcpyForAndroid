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
}
