package io.github.miuzarte.scrcpyforandroid.nativecore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapCacheTest {

    private fun BootstrapCache.addConfig(pts: Long = 0L) = add(byteArrayOf(0x67), pts, true, false)
    private fun BootstrapCache.addKey(pts: Long = 1L) = add(byteArrayOf(0x65), pts, false, true)
    private fun BootstrapCache.addFrame(pts: Long) = add(byteArrayOf(0x41), pts, false, false)

    private fun BootstrapCache.replayOrNull(): List<BootstrapPacket>? =
        (decideReplay() as? ReplayDecision.Replay)?.packets

    @Test
    fun emptyCacheDecidesNothing() {
        assertEquals(ReplayDecision.Nothing, BootstrapCache().decideReplay())
    }

    @Test
    fun configKeyFrameFramesReplayInOrder() {
        val cache = BootstrapCache()
        cache.addConfig()
        cache.addKey()
        cache.addFrame(2L)
        cache.addFrame(3L)

        val replay = cache.replayOrNull()!!
        assertEquals(4, replay.size)
        assertTrue(replay[0].isConfig)
        assertTrue(replay[1].isKeyFrame)
        assertEquals(2L, replay[2].ptsUs)
        assertEquals(3L, replay[3].ptsUs)
    }

    @Test
    fun keyFrameWithoutConfigReplaysFromKeyFrame() {
        val cache = BootstrapCache()
        cache.addKey()
        cache.addFrame(2L)

        val replay = cache.replayOrNull()!!
        assertEquals(2, replay.size)
        assertTrue(replay[0].isKeyFrame)
    }

    @Test
    fun keyFrameResetsToLatestConfigAndKeyFrame() {
        val cache = BootstrapCache()
        cache.addConfig()
        cache.addFrame(2L)
        cache.addFrame(3L)
        cache.addKey(4L)

        val replay = cache.replayOrNull()!!
        assertEquals(2, replay.size)
        assertTrue(replay[0].isConfig)
        assertTrue(replay[1].isKeyFrame)
    }

    @Test
    fun headStaysPinnedWhenCacheOverflows() {
        // The old policy evicted from the front: after 88 frames the config+key frame
        // were gone and a replay fed a key-frame-less run (the rotation blur root cause).
        val cache = BootstrapCache(maxPackets = 4)
        cache.addConfig()
        cache.addKey()
        for (i in 1..10) cache.addFrame(i.toLong())

        val replay = cache.replayOrNull()!!
        assertEquals(4, replay.size)
        assertTrue("config must stay pinned at the head", replay[0].isConfig)
        assertTrue("key frame must stay pinned", replay[1].isKeyFrame)
        assertEquals("newest frame must be the tail", 10L, replay[3].ptsUs)
    }

    @Test
    fun sizeAnnouncementSkipsReplayUntilNewConfig() {
        val cache = BootstrapCache()
        cache.addConfig()
        cache.addKey()
        cache.addFrame(2L)

        cache.onSizeAnnounced()
        assertEquals(ReplayDecision.SkipAwaitingNewStream(3), cache.decideReplay())

        // The new stream's config packet clears the expectation and makes the cache
        // replayable again (its key frame arrives right after).
        cache.addConfig(10L)
        assertEquals(ReplayDecision.SkipNoKeyFrame(1), cache.decideReplay())
        cache.addKey(11L)
        val replay = cache.replayOrNull()!!
        assertEquals(2, replay.size)
        assertEquals(10L, replay[0].ptsUs)
    }

    @Test
    fun framesOnlySkipsReplayNoKeyFrame() {
        val cache = BootstrapCache()
        cache.addFrame(1L)
        cache.addFrame(2L)

        assertEquals(ReplayDecision.SkipNoKeyFrame(2), cache.decideReplay())
    }

    @Test
    fun resetClearsPacketsAndSizeAnnouncement() {
        val cache = BootstrapCache()
        cache.addConfig()
        cache.addKey()
        cache.onSizeAnnounced()

        cache.reset()
        assertEquals(ReplayDecision.Nothing, cache.decideReplay())

        // The size announcement must not survive a reset (session restart).
        cache.addFrame(1L)
        assertEquals(ReplayDecision.SkipNoKeyFrame(1), cache.decideReplay())
    }
}
