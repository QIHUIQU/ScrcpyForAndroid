package io.github.miuzarte.scrcpyforandroid.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportFitTest {
    private val eps = 0.01f

    // 16:9 content on a square container, keep-aspect (letterbox): top/bottom bars.
    @Test
    fun letterboxBoundsCentered() {
        val b = ViewportFit.contentBounds(1000f, 1000f, 16f / 9f, stretch = false)
        assertEquals(1000f, b.width, eps)
        assertEquals(562.5f, b.height, eps)
        assertEquals(0f, b.left, eps)
        assertEquals(218.75f, b.top, eps)
    }

    // Stretch-fill: bounds = the full container.
    @Test
    fun stretchBoundsFillContainer() {
        val b = ViewportFit.contentBounds(1000f, 1000f, 16f / 9f, stretch = true)
        assertEquals(1000f, b.width, eps)
        assertEquals(1000f, b.height, eps)
        assertEquals(0f, b.left, eps)
        assertEquals(0f, b.top, eps)
    }

    @Test
    fun cornersRoundtripLetterbox() {
        val b = ViewportFit.contentBounds(1000f, 1000f, 16f / 9f, stretch = false)
        assertEquals(0 to 0, ViewportFit.mapToDevice(b.left, b.top, b, 1920, 1080))
        assertEquals(
            1919 to 1079,
            ViewportFit.mapToDevice(b.left + b.width, b.top + b.height, b, 1920, 1080),
        )
        // container center is video center
        assertEquals(960 to 540, ViewportFit.mapToDevice(500f, 500f, b, 1920, 1080))
    }

    @Test
    fun cornersRoundtripStretchNonUniform() {
        val b = ViewportFit.contentBounds(1000f, 800f, 16f / 9f, stretch = true)
        assertEquals(0 to 0, ViewportFit.mapToDevice(0f, 0f, b, 1920, 1080))
        assertEquals(1919 to 1079, ViewportFit.mapToDevice(1000f, 800f, b, 1920, 1080))
        // half container maps to half device on both axes (non-uniform scale)
        assertEquals(960 to 540, ViewportFit.mapToDevice(500f, 400f, b, 1920, 1080))
    }

    @Test
    fun outsideBoundsClamp() {
        val b = ViewportFit.contentBounds(1000f, 1000f, 16f / 9f, stretch = false)
        assertEquals(0 to 0, ViewportFit.mapToDevice(-50f, -50f, b, 1920, 1080))
        assertEquals(1919 to 1079, ViewportFit.mapToDevice(5000f, 5000f, b, 1920, 1080))
    }
}
