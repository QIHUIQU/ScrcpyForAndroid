package io.github.miuzarte.scrcpyforandroid.nativecore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSurfacePolicyTest {
    @Test
    fun primaryAlwaysTakesOver() {
        assertTrue(VideoSurfacePolicy.shouldSwitchOnAttach(requestIsPrimary = true, primaryValid = true))
        assertTrue(VideoSurfacePolicy.shouldSwitchOnAttach(requestIsPrimary = true, primaryValid = false))
    }

    @Test
    fun previewNeverStealsFromValidPrimary() {
        assertFalse(VideoSurfacePolicy.shouldSwitchOnAttach(requestIsPrimary = false, primaryValid = true))
    }

    @Test
    fun previewTakesOverWithoutPrimary() {
        assertTrue(VideoSurfacePolicy.shouldSwitchOnAttach(requestIsPrimary = false, primaryValid = false))
    }

    @Test
    fun fallbackOnlyWhenPrimaryLeftAndPreviewValid() {
        assertTrue(VideoSurfacePolicy.shouldFallbackToPreview(detachedWasPrimary = true, previewValid = true))
        assertFalse(VideoSurfacePolicy.shouldFallbackToPreview(detachedWasPrimary = true, previewValid = false))
        assertFalse(VideoSurfacePolicy.shouldFallbackToPreview(detachedWasPrimary = false, previewValid = true))
    }
}
