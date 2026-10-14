package io.github.miuzarte.scrcpyforandroid.nativecore

/**
 * BootstrapCache
 *
 * Keeps the packet run a freshly (re)created decoder needs to resume playback without
 * waiting for the next key frame:
 *
 * - A config (SPS/PPS) packet resets the cache and marks the (re)started encoder stream.
 * - A key frame resets the cache to [latest config, key frame]; following frames are
 *   appended, so the cached run is always decodable from its first packet (no gaps).
 * - The head (config + key frame) is never evicted; only trailing frames age out once
 *   the cache exceeds [maxPackets] (pathological: more packets than a full i-frame
 *   interval's worth — the server is not emitting periodic IDRs).
 *
 * Replay policy ([decideReplay]):
 * - After a video size change is announced, cached packets still belong to the OLD
 *   stream until a config packet of the new stream arrives. Replaying stale old-size
 *   packets into a decoder configured for the new size feeds it data it cannot decode
 *   (the MTK decoder drops into "wait for key frame"; with the previous eviction policy
 *   the key frame was usually already gone, so the decoder output garbage until the
 *   next periodic IDR — the reported "blurry/distorted for ~10 s after rotation").
 *   While a new stream is expected, replay is skipped: the live stream's config+IDR is
 *   already on its way.
 * - Without a key frame in the cache a replay would poison the decoder the same way,
 *   so it is skipped too; the live stream's next key frame is the recovery point.
 */
internal class BootstrapCache(
    private val maxPackets: Int = MAX_BOOTSTRAP_PACKETS,
) {
    private val lock = Any()
    private val packets = ArrayDeque<BootstrapPacket>()
    private var latestConfig: BootstrapPacket? = null
    private var awaitingNewStreamAfterSizeChange = false

    fun add(data: ByteArray, ptsUs: Long, isConfig: Boolean, isKeyFrame: Boolean) {
        val packet = BootstrapPacket(data.copyOf(), ptsUs, isConfig, isKeyFrame)
        synchronized(lock) {
            when {
                isConfig -> {
                    latestConfig = packet
                    packets.clear()
                    packets.addLast(packet)
                    // A config packet marks a (re)started encoder stream.
                    awaitingNewStreamAfterSizeChange = false
                }

                isKeyFrame -> {
                    packets.clear()
                    latestConfig?.let { packets.addLast(it) }
                    packets.addLast(packet)
                }

                else -> {
                    while (packets.size >= maxPackets) {
                        evictOldestFrameLocked()
                    }
                    packets.addLast(packet)
                }
            }
        }
    }

    /**
     * A new video size was announced; cached packets still belong to the old stream
     * until a config packet of the new stream arrives.
     */
    fun onSizeAnnounced() {
        synchronized(lock) {
            awaitingNewStreamAfterSizeChange = true
        }
    }

    fun reset() {
        synchronized(lock) {
            packets.clear()
            latestConfig = null
            awaitingNewStreamAfterSizeChange = false
        }
    }

    fun decideReplay(): ReplayDecision {
        synchronized(lock) {
            if (packets.isEmpty()) return ReplayDecision.Nothing
            if (awaitingNewStreamAfterSizeChange) {
                return ReplayDecision.SkipAwaitingNewStream(packets.size)
            }
            if (packets.none { it.isKeyFrame }) {
                return ReplayDecision.SkipNoKeyFrame(packets.size)
            }
            return ReplayDecision.Replay(packets.toList())
        }
    }

    private fun evictOldestFrameLocked() {
        // The leading run of config/key-frame packets is pinned; the first plain frame
        // after it is the oldest evictable entry.
        val iterator = packets.iterator()
        while (iterator.hasNext()) {
            val packet = iterator.next()
            if (!packet.isConfig && !packet.isKeyFrame) {
                iterator.remove()
                return
            }
        }
        // Only pinned entries present (should not happen): drop the oldest to stay bounded.
        packets.removeFirstOrNull()
    }

    companion object {
        private const val MAX_BOOTSTRAP_PACKETS = 600
    }
}

/** One cached video packet (see [BootstrapCache]). */
internal data class BootstrapPacket(
    val data: ByteArray,
    val ptsUs: Long,
    val isConfig: Boolean,
    val isKeyFrame: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BootstrapPacket

        if (ptsUs != other.ptsUs) return false
        if (isConfig != other.isConfig) return false
        if (isKeyFrame != other.isKeyFrame) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = ptsUs.hashCode()
        result = 31 * result + isConfig.hashCode()
        result = 31 * result + isKeyFrame.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/** Replay policy result (see [BootstrapCache.decideReplay]). */
internal sealed class ReplayDecision {
    /** Nothing cached: nothing to replay. */
    object Nothing : ReplayDecision()

    /** Cache holds only stale (pre-size-change) packets; the new stream is on its way. */
    data class SkipAwaitingNewStream(val count: Int) : ReplayDecision()

    /** No key frame cached: a replay would poison the decoder. */
    data class SkipNoKeyFrame(val count: Int) : ReplayDecision()

    /** Decodable run to feed into a fresh decoder. */
    data class Replay(val packets: List<BootstrapPacket>) : ReplayDecision()
}
