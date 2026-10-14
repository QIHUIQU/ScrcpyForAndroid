package io.github.miuzarte.scrcpyforandroid

import android.util.Log
import android.view.Surface
import io.github.miuzarte.scrcpyforandroid.nativecore.DecoderCapabilities
import io.github.miuzarte.scrcpyforandroid.nativecore.PersistentVideoRenderer
import io.github.miuzarte.scrcpyforandroid.nativecore.VideoSurfacePolicy
import io.github.miuzarte.scrcpyforandroid.nativecore.VideoDecoderController
import io.github.miuzarte.scrcpyforandroid.scrcpy.ClientOptions
import io.github.miuzarte.scrcpyforandroid.scrcpy.GammaSettings
import io.github.miuzarte.scrcpyforandroid.scrcpy.TransferOptions
import io.github.miuzarte.scrcpyforandroid.scrcpy.VideoScaleSettings
import io.github.miuzarte.scrcpyforandroid.services.blackout.PaymentBlackoutCoordinator
import io.github.miuzarte.scrcpyforandroid.services.lockwake.ControlledLockWakeController
import io.github.miuzarte.scrcpyforandroid.services.keepalive.ProcessKeepAliveService
import io.github.miuzarte.scrcpyforandroid.scrcpy.Scrcpy
import io.github.miuzarte.scrcpyforandroid.services.AppRuntime
import io.github.miuzarte.scrcpyforandroid.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Facade that centralizes video rendering and decoder management.
 *
 * Acts as a thin front-end over [VideoDecoderController] (decoder lifecycle, bootstrap
 * cache, error detection) and [PersistentVideoRenderer] (EGL / surface management).
 *
 * The facade owns the session lifecycle mutex and coordinates surface attach/detach
 * with decoder creation. It also publishes video size / FPS to listeners.
 *
 * Error recovery:
 * - When the local decoder cannot handle the video resolution (e.g. MTK AVC caps at
 *   2048 but scrcpy sends 2400), the facade can automatically restart the session with
 *   a lower `max_size` (controlled by the "downsize on decode error" app setting).
 * - When the decoder enters an unrecoverable error state at runtime (e.g. MTK OMX
 *   buffer conflict after rotation), the facade restarts the session.
 */
object NativeCoreFacade {
    private val sessionLifecycleMutex = Mutex()
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val renderer = PersistentVideoRenderer()
    private val controller = VideoDecoderController(renderer)

    @Volatile
    private var activeSurfaceId: Int? = null

    // 显示输出双槽位: primary=全屏控制页表面, preview=设备卡预览表面; 全屏优先 (见 VideoSurfacePolicy)
    @Volatile
    private var primarySurface: Surface? = null

    @Volatile
    private var primarySurfaceId: Int? = null

    @Volatile
    private var previewSurface: Surface? = null

    @Volatile
    private var previewSurfaceId: Int? = null

    @Volatile
    private var recordingSurfaceAttached = false

    // Reference to Scrcpy for reading currentSessionState (set by onScrcpySessionStarted)
    @Volatile
    private var scrcpyRef: Scrcpy? = null

    @Volatile
    private var packetCount: Long = 0
    private val lastResetVideoRequestMs = java.util.concurrent.atomic.AtomicLong(0L)

    // Cached ClientOptions for session restart (downgrade / error recovery)
    @Volatile
    private var cachedClientOptions: ClientOptions? = null

    // Guards against recursive restarts (restart triggers onScrcpySessionStopped/Started).
    // Exposed as a StateFlow so the stream UI can keep the screen alive during the
    // reconnect gap (currentSessionState goes null mid-restart).
    private val _restartInProgress = MutableStateFlow(false)
    val restartInProgress: StateFlow<Boolean> = _restartInProgress.asStateFlow()
    private val isRestarting: Boolean get() = _restartInProgress.value

    suspend fun close() {
        sessionLifecycleMutex.withLock {
            controller.releaseAll()
            renderer.release()
        }
    }

    /**
     * Register the current rendering [surface].
     *
     * - If the surface is already active and the decoder exists, this is a no-op.
     * - If a decoder exists but cannot switch output surface, a new decoder is created
     *   and bound to the supplied surface.
     */
    /** Apply the gamma gain to the persistent renderer (volatile; used on the next frame). */
    fun setGamma(value: Float) {
        renderer.setGamma(GammaSettings.normalize(value))
    }

    /** Apply the manual video scale to the persistent renderer (volatile; used on the next frame). */
    @Volatile private var currentVideoScale = 1f
    fun videoScale(): Float = currentVideoScale
    fun setVideoScale(value: Float) {
        val scale = VideoScaleSettings.normalize(value)
        currentVideoScale = scale
        renderer.setVideoScale(scale)
        videoPanX = io.github.miuzarte.scrcpyforandroid.scrcpy.ViewportFit.clampPan(videoPanX, scale)
        videoPanY = io.github.miuzarte.scrcpyforandroid.scrcpy.ViewportFit.clampPan(videoPanY, scale)
        renderer.setVideoPan(videoPanX, -videoPanY)
    }

    @Volatile private var videoPanX = 0f
    @Volatile private var videoPanY = 0f
    fun videoPan(): Pair<Float, Float> = videoPanX to videoPanY
    fun panVideoBy(dx: Float, dy: Float) {
        val scale = currentVideoScale
        videoPanX = io.github.miuzarte.scrcpyforandroid.scrcpy.ViewportFit.clampPan(videoPanX + dx, scale)
        videoPanY = io.github.miuzarte.scrcpyforandroid.scrcpy.ViewportFit.clampPan(videoPanY + dy, scale)
        renderer.setVideoPan(videoPanX, -videoPanY)
    }

    suspend fun attachVideoSurface(surface: Surface, primary: Boolean) {
        sessionLifecycleMutex.withLock {
            if (!surface.isValid) {
                Log.w(TAG, "attachVideoSurface(): skip invalid surface primary=$primary")
                return
            }
            val newId = System.identityHashCode(surface)
            if (primary) {
                primarySurface = surface
                primarySurfaceId = newId
            } else {
                previewSurface = surface
                previewSurfaceId = newId
            }
            val primaryValid = primarySurface?.isValid == true
            if (!VideoSurfacePolicy.shouldSwitchOnAttach(primary, primaryValid)) {
                Log.i(
                    TAG,
                    "attachVideoSurface(): preview surfaceId=$newId registered; " +
                        "primary surfaceId=$primarySurfaceId keeps output (active=$activeSurfaceId)",
                )
                return
            }
            switchDisplayOutput(surface, newId)
        }
    }

    /** 把解码器显示输出切到 [surface]（全屏/预览共用；调用方持 [sessionLifecycleMutex]）。 */
    private suspend fun switchDisplayOutput(surface: Surface, newId: Int) {
        if (activeSurfaceId == newId && controller.isDecoderUsable()) {
            return
        }
        Log.i(TAG, "attachVideoSurface(): switch output to surfaceId=$newId oldSurfaceId=$activeSurfaceId")
        activeSurfaceId = newId
        controller.attachDisplaySurface(surface)
        val session = controller.getCurrentSessionInfo() ?: return
        if (controller.isDecoderUsable()) {
            Log.i(TAG, "attachVideoSurface(): try switch decoder output to persistent surface")
            if (controller.trySwitchDecoderSurface()) {
                return
            }
        }
        controller.ensureDecoder(session)
    }

    /**
     * Unregister a rendering [surface] from the dual-slot output model (see [VideoSurfacePolicy]).
     *
     * - If the surface is in neither slot (stale identity), the request is ignored.
     * - Detaching the primary (fullscreen) surface falls back to the registered preview surface
     *   when one is still valid; otherwise the active display target is cleared.
     * - When [releaseDecoder] is true, the current decoder is also released because the backing
     *   surface is being destroyed for real.
     */
    suspend fun detachVideoSurface(surface: Surface? = null, releaseDecoder: Boolean = false) {
        sessionLifecycleMutex.withLock {
            val requestId = surface?.let { System.identityHashCode(it) }
            val isPrimary = requestId != null && requestId == primarySurfaceId
            val isPreview = requestId != null && requestId == previewSurfaceId
            if (requestId != null && !isPrimary && !isPreview) {
                Log.i(
                    TAG,
                    "detachVideoSurface(): skip stale request requestSurfaceId=$requestId " +
                        "active=$activeSurfaceId primary=$primarySurfaceId preview=$previewSurfaceId",
                )
                return
            }
            Log.i(
                TAG,
                "detachVideoSurface(): surfaceId=$requestId releaseDecoder=$releaseDecoder " +
                    "primary=$isPrimary preview=$isPreview active=$activeSurfaceId",
            )
            if (isPrimary) {
                primarySurface = null
                primarySurfaceId = null
            }
            if (isPreview) {
                previewSurface = null
                previewSurfaceId = null
            }
            if (requestId != null && requestId != activeSurfaceId) {
                // 非当前输出: 仅清槽位, 不触碰输出
                return
            }
            val preview = previewSurface
            val previewId = previewSurfaceId
            val fallbackUsable = preview != null && previewId != null && preview.isValid
            if (VideoSurfacePolicy.shouldFallbackToPreview(isPrimary, fallbackUsable)) {
                Log.i(TAG, "detachVideoSurface(): primary gone, fallback to preview surfaceId=$previewId")
                switchDisplayOutput(preview!!, previewId!!)
                return
            }
            activeSurfaceId = null
            controller.detachDisplaySurface(surface, releaseDecoder)
        }
    }

    suspend fun attachRecordingSurface(
        surface: Surface,
        width: Int,
        height: Int,
        onFrameRendered: ((Long) -> Unit)? = null,
    ) {
        sessionLifecycleMutex.withLock {
            recordingSurfaceAttached = true
            controller.attachRecordSurface(surface, width, height, onFrameRendered)
            val session = controller.getCurrentSessionInfo()
            if (session != null && !controller.isDecoderUsable()) {
                controller.ensureDecoder(session)
            }
        }
    }

    suspend fun detachRecordingSurface(
        surface: Surface? = null,
        releaseSurface: Boolean = false,
    ) {
        sessionLifecycleMutex.withLock {
            recordingSurfaceAttached = false
            controller.detachRecordSurface(surface, releaseSurface)
            if (activeSurfaceId == null) {
                controller.releaseAll()
            }
        }
    }

    fun addVideoSizeListener(listener: (Int, Int) -> Unit) = controller.addVideoSizeListener(listener)
    fun removeVideoSizeListener(listener: (Int, Int) -> Unit) = controller.removeVideoSizeListener(listener)
    fun addVideoFpsListener(listener: (Float) -> Unit) = controller.addVideoFpsListener(listener)
    fun removeVideoFpsListener(listener: (Float) -> Unit) = controller.removeVideoFpsListener(listener)

    /**
     * Called by Scrcpy.kt when a session starts.
     * Sets up video decoders for registered surfaces.
     *
     * [options] is cached so the facade can restart the session (with a modified max_size)
     * when the decoder fails.
     */
    suspend fun onScrcpySessionStarted(
        session: Scrcpy.Session.SessionInfo,
        sessionMgr: Scrcpy.Session,
        scrcpy: Scrcpy,
        options: ClientOptions,
    ) = sessionLifecycleMutex.withLock {
        scrcpyRef = scrcpy
        cachedClientOptions = options
        _restartInProgress.value = false
        controller.releaseAll()
        controller.resetBootstrap()
        if (activeSurfaceId != null || recordingSurfaceAttached) {
            // v4.0: width/height come from first video session packet, not from initial metadata
            if (session.width > 0 && session.height > 0) {
                Log.i(TAG, "onScrcpySessionStarted(): bind decoder to persistent surface")
                controller.ensureDecoder(session)
            } else {
                Log.i(TAG, "onScrcpySessionStarted(): defer decoder until first video session packet (v4.0)")
            }
        }
        packetCount = 0
        renderer.setGamma(Storage.appSettings.bundleState.value.gammaValue)
        setVideoScale(Storage.appSettings.bundleState.value.videoScale)
        PaymentBlackoutCoordinator.onSessionStarted()
        if (Storage.appSettings.bundleState.value.processKeepAliveEnabled) {
            runCatching { ProcessKeepAliveService.start(AppRuntime.context) }
                .onFailure { Log.w(TAG, "keep-alive FGS start failed", it) }
        }
        sessionMgr.attachVideoConsumer { packet ->
            cacheAndFeed(packet)
        }
        controller.onPacketDropped = { requestResetVideoThrottled() }
    }

    /**
     * A video packet was dropped because no decoder input buffer was available.
     * Dropping a P-frame corrupts the reference chain until the next key frame, so ask
     * the server for an immediate fresh IDR (RESET_VIDEO), throttled to avoid spamming
     * the encoder when the decoder is persistently overloaded.
     */
    private fun requestResetVideoThrottled() {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastResetVideoRequestMs.get()
        if (now - last < RESET_VIDEO_MIN_INTERVAL_MS) return
        if (!lastResetVideoRequestMs.compareAndSet(last, now)) return
        lifecycleScope.launch {
            runCatching { scrcpyRef?.resetVideo() }
                .onFailure { Log.w(TAG, "resetVideo request failed", it) }
        }
    }

    /**
     * Called by Scrcpy when a video session packet arrives with new dimensions.
     * Launches a coroutine to create or rebuild the decoder under the lifecycle mutex.
     *
     * Before creating the decoder, checks whether the local hardware decoder supports the
     * target resolution. If not, triggers a downgrade restart (when enabled) or shows an
     * error snackbar.
     */
    fun onVideoSizeChanged(width: Int, height: Int) {
        lifecycleScope.launch {
            sessionLifecycleMutex.withLock {
                if (isRestarting) return@withLock
                val scrcpy = scrcpyRef ?: return@withLock
                val info = scrcpy.currentSessionState.value ?: return@withLock
                if (info.width <= 0 || info.height <= 0) return@withLock

                val mime = info.codec?.mime ?: "video/avc"
                if (!DecoderCapabilities.isSizeSupported(mime, info.width, info.height)) {
                    Log.w(
                        TAG,
                        "onVideoSizeChanged(): decoder does not support ${info.width}x${info.height} for $mime",
                    )
                    handleUnsupportedSize(info.width, info.height, mime)
                    return@withLock
                }

                controller.rebuildDecoderForSize(info)
            }
        }
    }

    /**
     * Called by Scrcpy.kt when a session stops.
     * Cleans up decoders and resets state.
     */
    suspend fun onScrcpySessionStopped() = sessionLifecycleMutex.withLock {
        PaymentBlackoutCoordinator.onSessionStopped()
        runCatching { ProcessKeepAliveService.stop(AppRuntime.context) }
        ControlledLockWakeController.resetEpisode("session_stopped")
        controller.releaseAll()
        scrcpyRef = null
        recordingSurfaceAttached = false
    }

    private fun cacheAndFeed(packet: Scrcpy.Session.VideoPacket) {
        packetCount += 1
        if (packetCount == 1L || packetCount % 120L == 0L) {
            Log.i(
                TAG,
                "videoFeed(): packets=$packetCount key=${packet.isKeyFrame} cfg=${packet.isConfig} usable=${controller.isDecoderUsable()}",
            )
        }
        val usable = controller.feed(packet)
        if (!usable && !isRestarting) {
            Log.e(TAG, "videoFeed(): decoder became unusable after feed, packets=$packetCount")
            handleDecoderError()
        }
    }

    /**
     * Handle the case where the local decoder does not support the target resolution.
     *
     * When "downsize on decode error" is enabled (default), restarts the session with a
     * lower `max_size` derived from [DecoderCapabilities.maxSupportedSize]. Otherwise,
     * shows an error snackbar and leaves the decoder uncreated (black screen, no crash).
     */
    private suspend fun handleUnsupportedSize(width: Int, height: Int, mime: String) {
        val downsizeEnabled = Storage.appSettings.bundleState.value.downsizeOnDecodeError
        if (!downsizeEnabled) {
            AppRuntime.snackbar(R.string.vm_decoder_init_failed, "${width}x${height}")
            return
        }
        val maxSize = DecoderCapabilities.maxSupportedSize(mime)
        Log.i(TAG, "handleUnsupportedSize(): downgrading to max_size=$maxSize")
        AppRuntime.snackbar(R.string.vm_decoder_unsupported_size, width, height, maxSize)
        downgradeAndRestart(maxSize)
    }

    /**
     * Handle a runtime decoder error (codec entered unrecoverable state).
     *
     * Restarts the scrcpy session so a fresh decoder is created from scratch.
     */
    private fun handleDecoderError() {
        if (isRestarting) return
        val options = cachedClientOptions ?: return
        _restartInProgress.value = true
        Log.i(TAG, "handleDecoderError(): restarting session due to decoder error")
        AppRuntime.snackbar(R.string.vm_decoder_error_restarting)
        // 不持有 sessionLifecycleMutex: stop()/start() 会回调 onScrcpySessionStopped/Started,
        // 那两个回调要拿同一把锁, 持锁重启会永久死锁
        lifecycleScope.launch {
            restartSessionWith(options)
        }
    }

    /** True when the current session was started in app-transfer (virtual display) mode. */
    fun isAppTransferSession(): Boolean = cachedClientOptions?.newDisplay?.isNotBlank() == true

    /**
     * Switch the current session between mirror and app-transfer (virtual display) modes.
     *
     * The official scrcpy server cannot switch displays in-session, so this reconnects
     * with adjusted options: [transferStartApp] != null -> virtual display (sized like the
     * current stream) with that app started; null -> plain mirror. The screen blinks for
     * the reconnect duration (a few seconds).
     */
    fun requestAppTransferReconnect(transferStartApp: String?) {
        if (isRestarting) return
        val base = cachedClientOptions ?: return
        _restartInProgress.value = true
        val plan = TransferOptions.plan(
            currentNewDisplay = base.newDisplay,
            configuredVdSize = Storage.appSettings.bundleState.value.transferVdSize,
            transferStartApp = transferStartApp,
        )
        val newOptions = runCatching {
            var options = base.copy(
                newDisplay = plan.newDisplay,
                startApp = plan.startApp,
            )
            val vdDestroyContent = plan.vdDestroyContent
            if (vdDestroyContent != null) {
                // 流转会话以 vd_destroy_content=false 启动: 再次切换/切回镜像时,
                // 虚拟显示被关闭会把应用移到主屏继续运行, 而不是连同应用一起销毁 (A 回归修复)
                options = options.copy(vdDestroyContent = vdDestroyContent)
            }
            options.fix().validate()
        }.getOrElse { error ->
            _restartInProgress.value = false
            Log.w(TAG, "requestAppTransferReconnect(): invalid options", error)
            AppRuntime.snackbar(R.string.fullscreen_transfer_failed)
            return
        }
        Log.i(
            TAG,
            "requestAppTransferReconnect(): transfer=${plan.transfer} " +
                "newDisplay=${newOptions.newDisplay} startApp=${newOptions.startApp} " +
                "vdDestroyContent=${newOptions.vdDestroyContent}",
        )
        // 不持有 sessionLifecycleMutex (见 restartSessionWith 注释)
        lifecycleScope.launch {
            restartSessionWith(newOptions)
            if (newOptions.startApp.isNotBlank() && newOptions.control) {
                // 应用的启动只能靠这条控制消息 (server 端无 start_app 参数): 重连后必须补发,
                // 否则虚拟显示停在空桌面 (v7 流转回归修复; 成功与否真机以画面为准)
                runCatching { scrcpyRef?.startApp(newOptions.startApp) }
                    .onSuccess { Log.i(TAG, "app transfer: startApp sent (${newOptions.startApp})") }
                    .onFailure { Log.w(TAG, "app transfer: startApp after reconnect failed", it) }
            }
        }
    }

    /**
     * Restart the scrcpy session with a modified `max_size`.
     *
     * Persists the new max_size to [Storage.scrcpyOptions] so the user sees the change
     * in the UI, then stops and restarts the session.
     */
    private fun downgradeAndRestart(maxSize: Int) {
        if (isRestarting) return
        val options = cachedClientOptions ?: return
        val newOptions = options.copy(maxSize = maxSize.toUShort()).fix().validate()
        _restartInProgress.value = true

        // 不持有 sessionLifecycleMutex (见 restartSessionWith 注释);
        // 本函数在 onVideoSizeChanged 的持锁块内被调用, 用 launch 让重启在块释放后再跑
        lifecycleScope.launch {
            // Persist the new max_size so the UI reflects the downgrade.
            runCatching {
                val soBundle = Storage.scrcpyOptions.bundleState.value
                Storage.scrcpyOptions.saveBundle(soBundle.copy(maxSize = maxSize))
            }
            restartSessionWith(newOptions)
        }
    }

    /**
     * Stop the current session and start a new one with [options].
     *
     * MUST NOT be called while holding [sessionLifecycleMutex]: scrcpy.stop()/start()
     * call back into [onScrcpySessionStopped]/[onScrcpySessionStarted], which acquire
     * that same mutex — holding it here deadlocks the reconnect permanently (frozen
     * video + isRestarting stuck true). The [isRestarting] flag prevents concurrent
     * restarts; it is cleared in [onScrcpySessionStarted] when the new session is up,
     * and on start failure here.
     */
    private suspend fun restartSessionWith(options: ClientOptions?) {
        val scrcpy = scrcpyRef ?: return
        if (options == null) return
        Log.i(TAG, "restartSessionWith(): stopping current session")
        runCatching { scrcpy.stop(Scrcpy.StopReason.USER) }
        Log.i(
            TAG,
            "restartSessionWith(): starting new session " +
                "(max_size=${options.maxSize}, newDisplay=${options.newDisplay})",
        )
        val started = runCatching { scrcpy.start(options) }
        if (started.isFailure) {
            // 新会话没起来: 清标志, 否则后续所有重连/切换被永久锁死
            _restartInProgress.value = false
            Log.e(TAG, "restartSessionWith(): start failed", started.exceptionOrNull())
            AppRuntime.snackbar(R.string.vm_session_restart_failed)
        } else if (!options.video) {
            _restartInProgress.value = false
        }
    }

    private const val TAG = "NativeCoreFacade"
    private const val RESET_VIDEO_MIN_INTERVAL_MS = 500L
}
