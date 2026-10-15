package io.github.miuzarte.scrcpyforandroid.nativecore

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Looper
import android.util.Log
import android.view.Surface

/**
 * MediaCodecVideoDecoder
 *
 * Purpose:
 * - Wraps Android MediaCodec for all video codecs (H.264/H.265/AV1/VP8/VP9).
 * - Handles critical startup packets (config/keyframes) and provides callbacks
 *   for output size changes and FPS updates.
 * - Codecs without Annex-B config packets (VP8/VP9) work natively: no CSD is
 *   required for MediaCodec initialization.
 *
 * Threading / safety:
 * - Public methods are synchronized to allow calls from multiple threads
 *   (packet producer vs. teardown). Internally, MediaCodec callbacks and
 *   buffer queues are used on the calling thread.
 */
class MediaCodecVideoDecoder(
    width: Int,
    height: Int,
    outputSurface: Surface,
    mimeType: String = MIME_AVC,
    sps: ByteArray? = null,
    pps: ByteArray? = null,
    onOutputSizeChanged: ((width: Int, height: Int) -> Unit)? = null,
    onFpsUpdated: ((fps: Float) -> Unit)? = null,
) {
    private val codec: MediaCodec = try {
        MediaCodec.createDecoderByType(mimeType)
    } catch (e: Exception) {
        throw DecoderException(mimeType, width, height, e)
    }
    private val bufferInfo = MediaCodec.BufferInfo()
    private val outputSizeCallback = onOutputSizeChanged
    private val fpsUpdatedCallback = onFpsUpdated
    private val decoderMime = mimeType
    private var inCount = 0L
    private var outCount = 0L
    private var fpsWindowStartNs = System.nanoTime()
    private var fpsWindowFrameCount = 0
    private val droppedPacketCount = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Invoked (on the feeding thread) whenever a packet had to be dropped because no
     * MediaCodec input buffer became available. Dropping a non-key frame corrupts the
     * H.264/H.265 reference chain until the next key frame, so callers should react
     * (e.g. request a fresh IDR via the RESET_VIDEO control message).
     */
    @Volatile
    var onPacketDropped: (() -> Unit)? = null

    fun droppedPackets(): Long = droppedPacketCount.get()

    @Volatile
    private var released = false

    /**
     * Set when the codec reports an unrecoverable error (either during configure/start
     * or at runtime via [MediaCodec.CodecException] / [IllegalStateException]).
     *
     * Once non-null, [isUsable] returns false and subsequent [feedAnnexB] calls are no-ops.
     * The controller ([VideoDecoderController]) checks this to trigger session restart.
     */
    @Volatile
    internal var codecError: Throwable? = null
        private set

    init {
        // https://github.com/Miuzarte/ScrcpyForAndroid/issues/63
        // should not affect normal device
        if (Looper.myLooper() == null) {
            Log.w(TAG, "preparing Looper for MediaCodec (missing on this thread)")
            Looper.prepare()
        }

        if (!outputSurface.isValid) {
            runCatching { codec.release() }
            throw DecoderException(
                mimeType, width, height,
                IllegalStateException("Output surface is not valid"),
            )
        }
        val format = MediaFormat.createVideoFormat(mimeType, width, height)
        if (sps != null) {
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps))
        }
        if (pps != null) {
            format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps))
        }
        try {
            codec.configure(format, outputSurface, null, 0)
            codec.start()
        } catch (e: Exception) {
            runCatching { codec.release() }
            codecError = e
            throw DecoderException(mimeType, width, height, e)
        }
    }

    /**
     * Feed an Annex-B framed packet into the decoder.
     *
     * - `isConfig` indicates codec configuration packets (SPS/PPS) and are handled with
     *   higher priority: they can clear bootstrap buffering and are retried for input.
     * - `isKeyFrame` is used to mark access units that allow decoder restarts.
     * - The method attempts to dequeue an input buffer and queue the data; if no
     *   input buffer is available for critical packets, it drains output and retries
     *   to reduce startup stalls.
     */
    @Synchronized
    fun feedAnnexB(data: ByteArray, ptsUs: Long, isKeyFrame: Boolean, isConfig: Boolean = false) {
        if (released || codecError != null) {
            return
        }
        runCatching {
            var inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex < 0) {
                // Never skip a frame while the decoder is merely behind: this mirrors the
                // original EasyControl design, whose queue+pairing feed path has no drop
                // path at all. Wait for a buffer instead of dropping (dropping a non-key
                // frame corrupts the reference chain until the next key frame). Bounded by
                // MAX_INPUT_WAIT_US so a wedged decoder cannot stall the reader forever;
                // if the bound is exceeded we drop + request a fresh IDR (RESET_VIDEO).
                var waitedUs = INPUT_TIMEOUT_US
                while (inputIndex < 0 && waitedUs < MAX_INPUT_WAIT_US) {
                    drainOutput()
                    inputIndex = codec.dequeueInputBuffer(CRITICAL_INPUT_TIMEOUT_US)
                    waitedUs += CRITICAL_INPUT_TIMEOUT_US
                }
            }
            if (inputIndex >= 0) {
                val inBuf = codec.getInputBuffer(inputIndex)
                inBuf?.clear()
                inBuf?.put(data)
                var flags = 0
                if (isKeyFrame) {
                    flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                if (isConfig) {
                    flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                }
                codec.queueInputBuffer(inputIndex, 0, data.size, ptsUs, flags)
                inCount += 1
                if (inCount == 1L || inCount % 180L == 0L || isConfig) {
                    Log.i(
                        TAG,
                        "feed(): mime=$decoderMime in=$inCount size=${data.size} key=$isKeyFrame cfg=$isConfig pts=$ptsUs",
                    )
                }
            } else {
                val dropped = droppedPacketCount.incrementAndGet()
                Log.w(
                    TAG,
                    "drop packet (input starved): mime=$decoderMime size=${data.size} key=$isKeyFrame cfg=$isConfig droppedTotal=$dropped",
                )
                onPacketDropped?.invoke()
            }
            drainOutput()
        }.onFailure {
            // MediaCodec.CodecException and IllegalStateException indicate the codec is in an
            // unrecoverable error state (e.g. MTK OMX fatal error after surface conflicts).
            // Record the error so isUsable() returns false and the controller can react.
            codecError = it
            Log.e(
                TAG,
                "feed failed (codec marked unusable): mime=$decoderMime size=${data.size} key=$isKeyFrame cfg=$isConfig",
                it,
            )
        }
    }

    /**
     * Switch the codec's output surface.
     *
     * - Returns true when the codec accepted the new surface, false otherwise.
     * - Safe to call during playback to move rendering between preview/fullscreen surfaces.
     */
    @Synchronized
    fun switchOutputSurface(surface: Surface): Boolean {
        if (released) {
            return false
        }
        return runCatching {
            codec.setOutputSurface(surface)
            true
        }.getOrElse { false }
    }

    /**
     * Release the codec resources.
     *
     * - Stops decoding, releases the MediaCodec instance, and marks this decoder as released
     *   so subsequent calls are no-ops.
     */
    @Synchronized
    fun release() {
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    /**
     * Returns true when the decoder is still alive and the codec has not reported
     * an unrecoverable error.
     */
    fun isUsable(): Boolean = !released && codecError == null

    private fun drainOutput() {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
            when {
                outIndex >= 0 -> {
                    codec.releaseOutputBuffer(outIndex, true)
                    outCount += 1
                    recordOutputFrame()
                    if (outCount == 1L || outCount % 180L == 0L) {
                        Log.i(TAG, "drain(): mime=$decoderMime out=$outCount")
                    }
                }

                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    notifyOutputSize(codec.outputFormat)
                    continue
                }

                else -> return
            }
        }
    }

    private fun notifyOutputSize(format: MediaFormat) {
        val rawWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        val rawHeight = format.getInteger(MediaFormat.KEY_HEIGHT)

        val width = if (format.containsKey("crop-right") && format.containsKey("crop-left")) {
            format.getInteger("crop-right") - format.getInteger("crop-left") + 1
        } else {
            rawWidth
        }
        val height = if (format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
            format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        } else {
            rawHeight
        }

        if (width > 0 && height > 0) {
            outputSizeCallback?.invoke(width, height)
        }
    }

    private fun recordOutputFrame() {
        fpsWindowFrameCount += 1
        val nowNs = System.nanoTime()
        val elapsedNs = nowNs - fpsWindowStartNs
        if (elapsedNs < FPS_WINDOW_NS) {
            return
        }
        val fps = (fpsWindowFrameCount * 1_000_000_000f) / elapsedNs.toFloat()
        fpsUpdatedCallback?.invoke(fps)
        fpsWindowStartNs = nowNs
        fpsWindowFrameCount = 0
    }

    companion object {
        private const val TAG = "MediaCodecVideoDecoder"
        private const val MIME_AVC = "video/avc"
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val CRITICAL_INPUT_TIMEOUT_US = 50_000L

        /** Upper bound for waiting on a decoder input buffer before giving up (drop + RESET_VIDEO). */
        private const val MAX_INPUT_WAIT_US = 2_000_000L
        private const val OUTPUT_TIMEOUT_US = 0L
        private const val FPS_WINDOW_NS = 1_000_000_000L
    }
}
