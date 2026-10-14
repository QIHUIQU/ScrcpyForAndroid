package io.github.miuzarte.scrcpyforandroid.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoScaleSettingsTest {
    @Test
    fun defaultsAndNormalization() {
        assertEquals(1.0f, VideoScaleSettings.DEFAULT_SCALE, 0f)
        assertEquals(1.0f, VideoScaleSettings.normalize(Float.NaN), 0f)
        assertEquals(1.0f, VideoScaleSettings.normalize(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1.0f, VideoScaleSettings.normalize(0.49f), 0f)
        assertEquals(1.0f, VideoScaleSettings.normalize(2.01f), 0f)
        assertEquals(0.5f, VideoScaleSettings.normalize(0.5f), 0f)
        assertEquals(2.0f, VideoScaleSettings.normalize(2.0f), 0f)
    }

    @Test
    fun sliderMapping() {
        assertEquals(0, VideoScaleSettings.scaleToProgress(0.5f))
        assertEquals(30, VideoScaleSettings.scaleToProgress(2.0f))
        assertEquals(10, VideoScaleSettings.scaleToProgress(1.0f))
        assertEquals(0.5f, VideoScaleSettings.progressToScale(0), 0f)
        assertEquals(2.0f, VideoScaleSettings.progressToScale(30), 0f)
        assertEquals(1.0f, VideoScaleSettings.progressToScale(10), 0f)
    }

    @Test
    fun roundtripEveryStep() {
        for (p in 0..VideoScaleSettings.SEEK_MAX) {
            val s = VideoScaleSettings.progressToScale(p)
            assertTrue("illegal scale for progress $p: $s", VideoScaleSettings.isLegal(s))
            assertEquals(p, VideoScaleSettings.scaleToProgress(s))
        }
    }

    @Test
    fun formatCarriesUnit() {
        assertEquals("1.00×", VideoScaleSettings.format(1f))
        assertEquals("0.50×", VideoScaleSettings.format(0.5f))
        assertEquals("2.00×", VideoScaleSettings.format(2f))
    }

    @Test
    fun legality() {
        assertFalse(VideoScaleSettings.isLegal(Float.NaN))
        assertFalse(VideoScaleSettings.isLegal(0.49f))
        assertTrue(VideoScaleSettings.isLegal(1f))
    }
}
