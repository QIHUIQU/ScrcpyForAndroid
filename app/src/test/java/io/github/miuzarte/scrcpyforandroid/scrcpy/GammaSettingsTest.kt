package io.github.miuzarte.scrcpyforandroid.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GammaSettingsTest {
    @Test
    fun defaultsAndNormalization() {
        assertEquals(1.25f, GammaSettings.DEFAULT_GAMMA, 0f)
        assertEquals(1.25f, GammaSettings.normalize(Float.NaN), 0f)
        assertEquals(1.25f, GammaSettings.normalize(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1.25f, GammaSettings.normalize(0.4f), 0f)
        assertEquals(1.25f, GammaSettings.normalize(2.1f), 0f)
        assertEquals(0.5f, GammaSettings.normalize(0.5f), 0f)
        assertEquals(2.0f, GammaSettings.normalize(2.0f), 0f)
    }

    @Test
    fun sliderMapping() {
        assertEquals(0, GammaSettings.gammaToProgress(0.5f))
        assertEquals(150, GammaSettings.gammaToProgress(2.0f))
        assertEquals(75, GammaSettings.gammaToProgress(1.25f))
        assertEquals(0.5f, GammaSettings.progressToGamma(0), 0f)
        assertEquals(2.0f, GammaSettings.progressToGamma(150), 0f)
        assertEquals(1.25f, GammaSettings.progressToGamma(75), 0f)
    }

    @Test
    fun roundtripEveryStep() {
        for (p in 0..GammaSettings.SEEK_MAX) {
            val g = GammaSettings.progressToGamma(p)
            assertTrue("illegal gamma for progress $p: $g", GammaSettings.isLegal(g))
            assertEquals(p, GammaSettings.gammaToProgress(g))
        }
    }

    @Test
    fun formatIsTwoDecimals() {
        assertEquals("1.25", GammaSettings.format(1.25f))
        assertEquals("0.50", GammaSettings.format(0.5f))
        assertEquals("2.00", GammaSettings.format(2f))
    }

    @Test
    fun legality() {
        assertFalse(GammaSettings.isLegal(Float.NaN))
        assertFalse(GammaSettings.isLegal(0.49f))
        assertTrue(GammaSettings.isLegal(1f))
    }
}
