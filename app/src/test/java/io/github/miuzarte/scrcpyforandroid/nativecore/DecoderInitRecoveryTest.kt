package io.github.miuzarte.scrcpyforandroid.nativecore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderInitRecoveryTest {
    @Test fun firstFailureCanRetryEvenJustAfterBoot() {
        assertTrue(shouldRetryDecoderInit(0, null, 10))
    }
    @Test fun repeatedFailureIsThrottled() {
        assertFalse(shouldRetryDecoderInit(1, 100, 8_099))
        assertTrue(shouldRetryDecoderInit(1, 100, 8_100))
    }
    @Test fun thirdRetryIsLast() {
        assertTrue(shouldRetryDecoderInit(2, 100, 20_000))
        assertFalse(shouldRetryDecoderInit(3, 100, 20_000))
    }
    @Test fun successfulConstructionStartsNewEpisode() {
        assertTrue(shouldRetryDecoderInit(0, null, 200))
    }
}
