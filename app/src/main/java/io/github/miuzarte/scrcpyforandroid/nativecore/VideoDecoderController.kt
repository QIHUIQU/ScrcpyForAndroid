package io.github.miuzarte.scrcpyforandroid.nativecore

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArraySet

internal fun shouldRetryDecoderInit(attempts: Int, lastRetryMs: Long?, nowMs: Long): Boolean =
    attempts < 3 && (lastRetryMs == null || nowMs - lastRetryMs >= 8_000L)

/**
 * Owns the video [MediaCodecVideoDecoder] lifecycle and all state surrounding it:
 *
 * - Creating / rebuilding / releasing the decoder bound to the persistent renderer surface.
 * - Caching and replaying bootstrap packets (config + keyframe + following frames) so a
 *   freshly created decoder can resume playback quickly.
 * - Detecting decoder errors (both construction-time [DecoderException] and runtime
 *   codec errors) and exposing them via [isDecoderUsable] / the return value of [feed]
 *   for the facade to react.
 * - Publishing video size / FPS changes to registered listeners on the main thread.
 *
 * Threading:
 * - All public methods are safe to call from any thread; mutations are guarded by
 *   [controllerLock] for synchronous methods and by the facade's session mutex for
 *   suspend methods.
 * - The decoder itself is single-threaded (MediaCodecVideoDecoder is @Synchronized).
 *
 * This class does **not** decide session restart policy; it reports errors and lets
 * [NativeCoreFacade] decide whether to restart the scrcpy session.
 */
internal class VideoDecoderController(
    private val renderer: PersistentVideoRenderer,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val controllerLock = Any()
    private var decoder: MediaCodecVideoDecoder? = null

    @Volatile
    private var currentSessionInfo: Scrcpy.Session.SessionInfo? = null

    private val videoSizeListeners = CopyOnWriteArraySet<(Int, Int) -> Unit>()
    private val videoFpsListeners = CopyOnWriteArraySet<(Float) -> Unit>()

    private val bootstrapCache = BootstrapCache()

    /**
     * Forwarded from the active decoder: invoked when a packet had to be dropped due to
     * input starvation. See [MediaCodecVideoDecoder.onPacketDropped].
     */
    @Volatile
    var onPacketDropped: (() -> Unit)? = null

    /** Reports construction outcome; recovery policy belongs to the facade. */
    @Volatile
    var onDecoderInitResult: ((Boolean) -> Unit)? = null

    /**
     * The session info currently associated with the decoder (or the most recently released
     * one). Returns null if no session has ever been bound.
     *
     * The facade uses this instead of maintaining its own copy, so there is a single source
     * of truth kept in sync by [ensureDecoder] / [rebuildDecoderForSize] / the
     * `onOutputSizeChanged` callback.
     */
    fun getCurrentSessionInfo(): Scrcpy.Session.SessionInfo? = currentSessionInfo

    // ---------- Listeners ----------

    fun addVideoSizeListener(listener: (Int, Int) -> Unit) = videoSizeListeners.add(listener)
    fun removeVideoSizeListener(listener: (Int, Int) -> Unit) = videoSizeListeners.remove(listener)
    fun addVideoFpsListener(listener: (Float) -> Unit) = videoFpsListeners.add(listener)
    fun removeVideoFpsListener(listener: (Float) -> Unit) = videoFpsListeners.remove(listener)

    // ---------- Session lifecycle ----------

    /**
     * Tear down everything: release the decoder and drop bootstrap state.
     * Called by the facade when a scrcpy session stops or when the controller is closed.
     */
    fun releaseAll() {
        synchronized(controllerLock) {
            runCatching { decoder?.release() }
            decoder = null
            currentSessionInfo = null
        }
        bootstrapCache.reset()
    }

    /**
     * Reset bootstrap state without touching the decoder (used on session start before a new
     * decoder is created).
     */
    fun resetBootstrap() {
        bootstrapCache.reset()
    }

    /**
     * Called (on the video reader thread) when a new video size is announced: until the
     * new stream's config packet arrives, cached packets still belong to the old stream
     * and must not be replayed into a decoder configured for the new size.
     */
    fun onVideoSizeAnnounced() = bootstrapCache.onSizeAnnounced()

    // ---------- Surface management ----------

    /**
     * Try to switch the active decoder's output surface to the renderer's current decoder
     * surface. Returns true if the switch succeeded (or was unnecessary), false if the
     * caller needs to recreate the decoder.
     */
    fun trySwitchDecoderSurface(): Boolean {
        synchronized(controllerLock) {
            val current = decoder ?: return false
            val switched = current.switchOutputSurface(renderer.getDecoderSurface())
            Log.i(TAG, "trySwitchDecoderSurface(): success=$switched")
            return switched
        }
    }

    /**
     * Attach the display surface to the renderer. Does not touch the decoder directly.
     */
    fun attachDisplaySurface(surface: Surface) {
        renderer.attachDisplaySurface(surface)
    }

    /**
     * Detach the display surface. When [releaseDecoder] is true the decoder is also released
     * because the backing surface is being destroyed.
     */
    fun detachDisplaySurface(surface: Surface?, releaseDecoder: Boolean) {
        renderer.detachDisplaySurface(surface, releaseSurface = false)
        if (releaseDecoder) {
            synchronized(controllerLock) {
                Log.i(TAG, "detachDisplaySurface(): releasing decoder with destroyed surface")
                runCatching { decoder?.release() }
                decoder = null
            }
        }
    }

    /**
     * Attach a recording surface to the renderer. If no decoder exists yet but session info
     * is known, a new decoder is created.
     */
    fun attachRecordSurface(
        surface: Surface,
        width: Int,
        height: Int,
        onFrameRendered: ((Long) -> Unit)?,
    ) {
        renderer.attachRecordSurface(surface, width, height, onFrameRendered)
    }

    fun detachRecordSurface(surface: Surface?, releaseSurface: Boolean) {
        renderer.detachRecordSurface(surface, releaseSurface)
    }

    // ---------- Decoder creation / rebuild ----------

    /**
     * Create a decoder for [session] if none exists. Should be called when a display or
     * recording surface is attached and the session size is known.
     *
     * Returns true if a decoder exists after the call (either pre-existing or newly created).
     */
    fun ensureDecoder(session: Scrcpy.Session.SessionInfo): Boolean {
        synchronized(controllerLock) {
            val current = decoder
            if (current != null && current.isUsable()) return true
            if (session.width <= 0 || session.height <= 0) {
                Log.i(
                    TAG,
                    "ensureDecoder(): defer decoder, session size not yet known (${session.width}x${session.height})",
                )
                return false
            }
            // If a damaged decoder exists, release it first.
            if (current != null) {
                Log.i(TAG, "ensureDecoder(): releasing damaged decoder before rebuild")
                runCatching { current.release() }
                decoder = null
            }
            currentSessionInfo = session
            createOrReplaceDecoderLocked(session, recreateSurface = false)
            return decoder != null
        }
    }

    /**
     * Rebuild the decoder when the video size changes (e.g. flex-display rotation).
     *
     * - Recreates the persistent decoder surface to avoid OMX buffer conflicts on chips
     *   like MTK that fail when a new codec is configured against a surface with leftover
     *   buffers from a previously released codec.
     * - Caches the new session info so future size-change checks can compare.
     *
     * Returns true if the decoder was rebuilt, false if skipped (same size, no decoder, etc.).
     */
    suspend fun rebuildDecoderForSize(session: Scrcpy.Session.SessionInfo): Boolean {
        val current = synchronized(controllerLock) { currentSessionInfo }
        if (current != null && current.width == session.width && current.height == session.height) {
            return false
        }

        val needRecreateSurface: Boolean
        synchronized(controllerLock) {
            if (decoder == null) {
                // Initial creation (deferred until first session packet).
                Log.i(TAG, "rebuildDecoderForSize(): create decoder ${session.width}x${session.height}")
                currentSessionInfo = session
                createOrReplaceDecoderLocked(session, recreateSurface = false)
                return decoder != null
            }
            // Release the old decoder now so OMX stops writing to the surface, then
            // drop the lock for the blocking delay + surface recreation below.
            Log.i(
                TAG,
                "rebuildDecoderForSize(): rebuild decoder ${current?.width}x${current?.height} -> ${session.width}x${session.height}",
            )
            currentSessionInfo = session
            runCatching { decoder?.release() }
            decoder = null
            needRecreateSurface = true
        }

        // Give OMX time to return buffers to the old native window before we tear down
        // the SurfaceTexture, then recreate the surface. Both block, so run on IO.
        //
        // No lock is held here: the old decoder is already released and `decoder` is null,
        // so feed() will no-op. The facade's sessionLifecycleMutex ensures no other
        // lifecycle operation runs concurrently.
        withContext(Dispatchers.IO) {
            delay(50)
            runCatching { renderer.recreateDecoderSurface() }
        }

        synchronized(controllerLock) {
            createOrReplaceDecoderLocked(session, recreateSurface = false)
            return decoder != null
        }
    }

    /**
     * Core method: construct a new [MediaCodecVideoDecoder] and replay bootstrap packets into it.
     *
     * Caller must have already released the old decoder (if any) and, when needed, recreated
     * the persistent surface. Must be called while holding [controllerLock].
     */
    private fun createOrReplaceDecoderLocked(
        session: Scrcpy.Session.SessionInfo,
        recreateSurface: Boolean,
    ) {
        if (recreateSurface) {
            // Should not happen in the current call path — surface recreation is handled
            // by rebuildDecoderForSize outside the lock. Keep the parameter for clarity.
            error("recreateSurface must be handled by caller")
        }

        val surface = renderer.getDecoderSurface()
        val mime = session.codec?.mime ?: "video/avc"
        Log.i(
            TAG,
            "createOrReplaceDecoder(): codec=${session.codec?.string ?: "null"}, " +
                    "size=${session.width}x${session.height}, " +
                    "recreateSurface=$recreateSurface, " +
                    "persistent=true",
        )
        val newDecoder = try {
            MediaCodecVideoDecoder(
                width = session.width,
                height = session.height,
                outputSurface = surface,
                mimeType = mime,
                onOutputSizeChanged = { width, height ->
                    val current = currentSessionInfo
                    if (current == null || (current.width == width && current.height == height)) {
                        return@MediaCodecVideoDecoder
                    }
                    Log.i(
                        TAG,
                        "videoSizeChanged(): ${current.width}x${current.height} -> ${width}x${height}",
                    )
                    currentSessionInfo = current.copy(width = width, height = height)
                    mainHandler.post {
                        videoSizeListeners.forEach { listener ->
                            runCatching { listener(width, height) }
                        }
                    }
                },
                onFpsUpdated = { fps ->
                    mainHandler.post {
                        videoFpsListeners.forEach { listener ->
                            runCatching { listener(fps) }
                        }
                    }
                },
            )
        } catch (e: DecoderException) {
            Log.e(TAG, "createOrReplaceDecoder(): decoder init failed for ${e.mime} ${e.width}x${e.height}", e)
            // 构造失败此前只在日志记录、静默无恢复(黑屏只剩音频): 通知 facade 走自愈重启 (P1-3)
            onDecoderInitResult?.invoke(false)
            return
        }
        newDecoder.onPacketDropped = { onPacketDropped?.invoke() }
        decoder = newDecoder
        onDecoderInitResult?.invoke(true)
        replayBootstrapPackets(newDecoder)
    }

    // ---------- Packet feeding ----------

    /**
     * Cache a bootstrap packet and feed it to the active decoder if one exists.
     *
     * Returns true if the decoder is still usable after feeding, false if the decoder
     * entered an error state (the facade should restart the session in that case).
     */
    fun feed(packet: Scrcpy.Session.VideoPacket): Boolean {
        cacheBootstrapPacket(packet)
        val dec = synchronized(controllerLock) { decoder } ?: return true
        runCatching {
            dec.feedAnnexB(
                packet.data,
                packet.ptsUs,
                packet.isKeyFrame,
                packet.isConfig,
            )
        }
        // A decoder intentionally released/replaced during size change is not a codec error.
        return synchronized(controllerLock) { decoder !== dec || dec.isUsable() }
    }

    /**
     * Whether the current decoder (if any) is still usable.
     */
    fun isDecoderUsable(): Boolean {
        val dec = synchronized(controllerLock) { decoder } ?: return false
        return dec.isUsable()
    }

    // ---------- Bootstrap packet cache ----------

    /**
     * Feed the cached bootstrap run into a freshly created decoder, following the
     * [BootstrapCache] replay policy: skip while a new stream after a size change is
     * expected (stale data), skip when no key frame is cached (a key-frame-less run
     * would poison the decoder until the next periodic IDR).
     */
    private fun replayBootstrapPackets(decoder: MediaCodecVideoDecoder) {
        when (val decision = bootstrapCache.decideReplay()) {
            is ReplayDecision.Nothing -> return

            is ReplayDecision.SkipAwaitingNewStream -> {
                Log.i(
                    TAG,
                    "replayBootstrapPackets(): skip stale replay " +
                            "(awaiting new stream after size change), count=${decision.count}",
                )
                return
            }

            is ReplayDecision.SkipNoKeyFrame -> {
                Log.w(
                    TAG,
                    "replayBootstrapPackets(): skip replay, no keyframe in cache yet, " +
                            "count=${decision.count}",
                )
                return
            }

            is ReplayDecision.Replay -> {
                val snapshot = decision.packets
                Log.i(TAG, "replayBootstrapPackets(): count=${snapshot.size}")
                snapshot.forEachIndexed { index, packet ->
                    if (!decoder.isUsable()) {
                        Log.w(TAG, "replayBootstrapPackets(): decoder unusable, aborting replay at $index")
                        return
                    }
                    runCatching {
                        decoder.feedAnnexB(packet.data, packet.ptsUs, packet.isKeyFrame, packet.isConfig)
                    }
                }
            }
        }
    }

    private fun cacheBootstrapPacket(packet: Scrcpy.Session.VideoPacket) {
        bootstrapCache.add(packet.data, packet.ptsUs, packet.isConfig, packet.isKeyFrame)
    }

    companion object {
        private const val TAG = "VideoDecoderController"
    }
}
