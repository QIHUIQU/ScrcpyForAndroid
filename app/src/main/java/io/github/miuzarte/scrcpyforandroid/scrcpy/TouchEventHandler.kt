package io.github.miuzarte.scrcpyforandroid.scrcpy

import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private typealias ContentBounds = ViewportFit.ContentBounds

/**
 * TouchEventHandler
 *
 * Purpose:
 * - Handles touch event processing for fullscreen control screen
 * - Manages pointer tracking, coordinate mapping, and touch injection
 */
class TouchEventHandler(
    private val coroutineScope: CoroutineScope,
    private val session: Scrcpy.Session.SessionInfo,
    private val touchAreaSize: IntSize,
    private val stretchFill: Boolean = false,
    private val activePointerIds: LinkedHashSet<Int>,
    private val activePointerPositions: LinkedHashMap<Int, Offset>,
    private val activePointerDevicePositions: LinkedHashMap<Int, Pair<Int, Int>>,
    private val pointerLabels: LinkedHashMap<Int, Int>,
    private var nextPointerLabel: Int,
    private val mouseHoverEnabled: Boolean,
    private val onInjectTouch: suspend (
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        pressure: Float,
        actionButton: Int,
        buttons: Int,
    ) -> Unit,
    private val onBackOrScreenOn: suspend (action: Int) -> Unit,
    private val onActiveTouchCountChanged: (Int) -> Unit,
    private val onActiveTouchDebugChanged: (String) -> Unit,
    private val onNextPointerLabelChanged: (Int) -> Unit,
) {
    companion object {
        private const val FULLSCREEN_TOUCH_LOG_TAG = "FullscreenTouch"
        private const val POINTER_ID_MOUSE = -1L
    }

    private object UiMotionActions {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val CANCEL = 3
        const val POINTER_DOWN = 5
        const val POINTER_UP = 6
    }

    private val eventPointerIds = HashSet<Int>(10)
    private val eventPositions = HashMap<Int, Offset>(10)
    private val eventPressures = HashMap<Int, Float>(10)
    private val justPressedPointerIds = HashSet<Int>(10)
    private val pendingMoveJobs = HashMap<Int, Job>(10)
    private var panning = false
    private var previousCentroid: Offset? = null

    /**
     * 双指平移（仅倍率 >1× 时启用）：双指拖拽整个画面，越界由 clampPan 钳制；
     * 模型对齐 moonlight-android `ui/video/VideoZoomGestureOverlay.java` 的拖动语义：
     * 平移期间不向被控端转发触控，且开始平移时主动取消已转发的触摸（ACTION_CANCEL）。
     * 单指保持远控触控；鼠标/触控笔事件直通（不走平移）。
     */
    private fun handlePanGesture(event: MotionEvent): Boolean {
        val scale = io.github.miuzarte.scrcpyforandroid.NativeCoreFacade.videoScale()
        if (scale <= 1f || isMouseLikeEvent(event)) return false
        if (event.pointerCount >= 2) {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                // Re-restore P1-5: 2026-10-14 reference audit confirmed this is a stuck-pan bug,
                // not panel-dismiss design. CANCEL must finish a pan, never start/latch one.
                handleCancelAction(calculateContentBounds(), UiMotionActions.CANCEL)
                panning = false
                previousCentroid = null
                return true
            }
            if (!panning) {
                // 平移开始：立即取消已转发的触摸。必须用 ACTION_CANCEL —— 用 UP 的话，
                // 被控端会把「第一根手指的 DOWN+UP」识别为一次点击/滑动（误触根因）；
                // CANCEL 语义 = 手势作废，不产生点击。
                if (activePointerIds.isNotEmpty()) {
                    Log.i(
                        FULLSCREEN_TOUCH_LOG_TAG,
                        "pan start: cancel ${activePointerIds.size} forwarded pointer(s) with ACTION_CANCEL",
                    )
                }
                handleCancelAction(calculateContentBounds(), UiMotionActions.CANCEL)
                panning = true
            }
            val centroid = Offset((event.getX(0) + event.getX(1)) / 2f, (event.getY(0) + event.getY(1)) / 2f)
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                previousCentroid?.let {
                    val bounds = ViewportFit.contentBounds(touchAreaSize.width.toFloat(), touchAreaSize.height.toFloat(),
                        ViewportFit.sessionAspectOf(session.width, session.height), stretchFill)
                    io.github.miuzarte.scrcpyforandroid.NativeCoreFacade.panVideoBy(
                        (centroid.x - it.x) / bounds.width, (centroid.y - it.y) / bounds.height)
                }
            }
            previousCentroid = centroid
            return true
        }
        if (panning) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                panning = false
                previousCentroid = null
            }
            return true
        }
        return false
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (touchAreaSize.width == 0 || touchAreaSize.height == 0) {
            return true
        }

        if (handlePanGesture(event)) return true
        val bounds = calculateContentBounds()

        if (isMouseLikeEvent(event)) {
            return handleMouseEvent(event, bounds)
        }

        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            return handleCancelAction(bounds)
        }

        extractEventData(event)
        handleDisappearedPointers(eventPointerIds, bounds)

        val endedPointerId = getEndedPointerId(event)
        handlePointerDown(event, endedPointerId, bounds)
        handlePointerMove(event, endedPointerId, bounds)
        handlePointerUp(endedPointerId, bounds)

        onActiveTouchCountChanged(activePointerIds.size)
        refreshTouchDebug()
        return true
    }

    private fun isMouseLikeEvent(event: MotionEvent): Boolean {
        return event.isFromSource(InputDevice.SOURCE_MOUSE) ||
                event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_HOVER_EXIT ||
                event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
    }

    private fun calculateContentBounds(): ContentBounds {
        val base = ViewportFit.contentBounds(
            containerWidth = touchAreaSize.width.toFloat(),
            containerHeight = touchAreaSize.height.toFloat(),
            sessionAspect = ViewportFit.sessionAspectOf(session.width, session.height),
            stretch = stretchFill,
        )
        val scale = io.github.miuzarte.scrcpyforandroid.NativeCoreFacade.videoScale()
        val (panX, panY) = io.github.miuzarte.scrcpyforandroid.NativeCoreFacade.videoPan()
        return ViewportFit.zoomedBounds(base, scale, panX, panY)
    }

    private fun isInsideContent(rawX: Float, rawY: Float, bounds: ContentBounds): Boolean {
        return rawX in bounds.left..(bounds.left + bounds.width) &&
                rawY in bounds.top..(bounds.top + bounds.height)
    }

    private fun mapToDevice(rawX: Float, rawY: Float, bounds: ContentBounds): Pair<Int, Int> {
        return ViewportFit.mapToDevice(rawX, rawY, bounds, session.width, session.height)
    }

    private fun getPointerLabel(pointerId: Int): Int {
        val existing = pointerLabels[pointerId]
        if (existing != null) {
            return existing
        }
        val assigned = nextPointerLabel
        nextPointerLabel += 1
        onNextPointerLabelChanged(nextPointerLabel)
        pointerLabels[pointerId] = assigned
        return assigned
    }

    private fun refreshTouchDebug() {
        if (activePointerIds.isEmpty()) {
            onActiveTouchDebugChanged("")
            return
        }
        val debug = activePointerIds
            .sortedBy { getPointerLabel(it) }
            .joinToString(separator = "\n") { pointerId ->
                val label = getPointerLabel(pointerId)
                val pos = activePointerDevicePositions[pointerId]
                if (pos == null) {
                    "#$label(id=$pointerId):?"
                } else {
                    "#$label(id=$pointerId):${pos.first},${pos.second}"
                }
            }
        onActiveTouchDebugChanged(debug)
    }

    private fun handleMouseEvent(
        event: MotionEvent,
        bounds: ContentBounds,
    ): Boolean {
        val rawX = event.getX(0)
        val rawY = event.getY(0)
        val (x, y) = mapToDevice(rawX, rawY, bounds)
        val pressure = event.getPressure(0).coerceIn(0f, 1f)
        val buttons = event.buttonState
        val actionButton = event.actionButton
        val isHoverMotion = when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT,
                -> true

            MotionEvent.ACTION_MOVE -> buttons == 0
            else -> false
        }

        if (!mouseHoverEnabled && isHoverMotion) {
            return true
        }

        if (actionButton == MotionEvent.BUTTON_SECONDARY ||
            buttons and MotionEvent.BUTTON_SECONDARY != 0
        ) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_BUTTON_PRESS,
                    -> {
                    coroutineScope.launch {
                        runCatching {
                            onBackOrScreenOn(0)
                        }.onFailure { e ->
                            Log.w(FULLSCREEN_TOUCH_LOG_TAG, "mouse secondary down failed", e)
                        }
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_BUTTON_RELEASE,
                    -> {
                    coroutineScope.launch {
                        runCatching {
                            onBackOrScreenOn(1)
                        }.onFailure { e ->
                            Log.w(FULLSCREEN_TOUCH_LOG_TAG, "mouse secondary up failed", e)
                        }
                    }
                }
            }
            return true
        }

        val injectAction = when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> MotionEvent.ACTION_HOVER_ENTER
            MotionEvent.ACTION_HOVER_MOVE -> MotionEvent.ACTION_HOVER_MOVE
            MotionEvent.ACTION_HOVER_EXIT -> MotionEvent.ACTION_HOVER_EXIT
            MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_DOWN
            MotionEvent.ACTION_UP -> MotionEvent.ACTION_UP
            MotionEvent.ACTION_MOVE -> MotionEvent.ACTION_MOVE

            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE,
                -> return true

            else -> return true
        }

        coroutineScope.launch {
            runCatching {
                onInjectTouch(
                    injectAction,
                    POINTER_ID_MOUSE,
                    x,
                    y,
                    pressure,
                    actionButton,
                    buttons,
                )
            }.onFailure { e ->
                Log.w(FULLSCREEN_TOUCH_LOG_TAG, "handleMouseEvent failed", e)
            }
        }
        return true
    }

    private fun releasePointer(pointerId: Int, bounds: ContentBounds, action: Int = UiMotionActions.UP) {
        if (!activePointerIds.contains(pointerId)) return
        pendingMoveJobs.remove(pointerId)?.cancel()
        val pos = activePointerPositions[pointerId] ?: Offset.Zero
        val (x, y) = mapToDevice(pos.x, pos.y, bounds)
        coroutineScope.launch {
            runCatching {
                onInjectTouch(action, pointerId.toLong(), x, y, 0f, 0, 0)
            }.onFailure { e ->
                Log.w(FULLSCREEN_TOUCH_LOG_TAG, "releasePointer failed for pointerId=$pointerId", e)
            }
        }
        activePointerIds -= pointerId
        activePointerPositions.remove(pointerId)
        activePointerDevicePositions.remove(pointerId)
        pointerLabels.remove(pointerId)
    }

    /**
     * 释放全部活动指针。默认发 ACTION_UP（普通路径，行为不变）；平移手势开始时以
     * [UiMotionActions.CANCEL] 调用 —— 对已转发的触摸必须发 CANCEL 而不是 UP：
     * 被控端把 DOWN+UP 识别为一次点击/滑动（「双指拖动误触被控端界面」的根因），
     * CANCEL 表示"手势作废"，不产生点击（对齐 moonlight 平移期间不产生触控的语义）。
     */
    private fun handleCancelAction(bounds: ContentBounds, action: Int = UiMotionActions.UP): Boolean {
        val toCancel = activePointerIds.toList()
        for (pointerId in toCancel) {
            releasePointer(pointerId, bounds, action)
        }
        onActiveTouchCountChanged(activePointerIds.size)
        refreshTouchDebug()
        return true
    }

    private fun extractEventData(event: MotionEvent) {
        eventPointerIds.clear()
        eventPositions.clear()
        eventPressures.clear()
        for (i in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(i)
            eventPointerIds += pointerId
            eventPositions[pointerId] = Offset(event.getX(i), event.getY(i))
            eventPressures[pointerId] = event.getPressure(i).coerceIn(0f, 1f)
        }
    }

    private fun handleDisappearedPointers(eventPointerIds: Set<Int>, bounds: ContentBounds) {
        val disappearedPointers = activePointerIds.filter { it !in eventPointerIds }
        for (pointerId in disappearedPointers) {
            releasePointer(pointerId, bounds)
        }
    }

    private fun getEndedPointerId(event: MotionEvent): Int? {
        return when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> event.getPointerId(event.actionIndex)
            else -> null
        }
    }

    private fun handlePointerDown(
        event: MotionEvent,
        endedPointerId: Int?,
        bounds: ContentBounds,
    ) {
        justPressedPointerIds.clear()
        for (i in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(i)
            if (pointerId == endedPointerId) continue
            val raw = eventPositions[pointerId] ?: continue
            val pressure = eventPressures[pointerId] ?: 0f
            if (!activePointerIds.contains(pointerId)) {
                if (!isInsideContent(raw.x, raw.y, bounds)) continue
                val (x, y) = mapToDevice(raw.x, raw.y, bounds)
                activePointerIds += pointerId
                activePointerPositions[pointerId] = raw
                activePointerDevicePositions[pointerId] = x to y
                justPressedPointerIds += pointerId
                coroutineScope.launch {
                    runCatching {
                        onInjectTouch(
                            UiMotionActions.DOWN,
                            pointerId.toLong(),
                            x,
                            y,
                            pressure,
                            0,
                            0,
                        )
                    }.onFailure { e ->
                        Log.w(
                            FULLSCREEN_TOUCH_LOG_TAG,
                            "handlePointerDown failed for pointerId=$pointerId",
                            e,
                        )
                    }
                }
            }
        }
    }

    private fun handlePointerMove(
        event: MotionEvent,
        endedPointerId: Int?,
        bounds: ContentBounds,
    ) {
        for (i in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(i)
            if (!activePointerIds.contains(pointerId)) continue
            if (pointerId == endedPointerId) continue
            if (pointerId in justPressedPointerIds) continue
            val raw = eventPositions[pointerId] ?: continue
            val pressure = eventPressures[pointerId] ?: 0f
            activePointerPositions[pointerId] = raw
            val (x, y) = mapToDevice(raw.x, raw.y, bounds)
            activePointerDevicePositions[pointerId] = x to y
            pendingMoveJobs[pointerId]?.cancel()
            pendingMoveJobs[pointerId] = coroutineScope.launch {
                runCatching {
                    onInjectTouch(UiMotionActions.MOVE, pointerId.toLong(), x, y, pressure, 0, 0)
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(
                        FULLSCREEN_TOUCH_LOG_TAG,
                        "handlePointerMove failed for pointerId=$pointerId",
                        e,
                    )
                }
            }
        }
    }

    private fun handlePointerUp(
        endedPointerId: Int?,
        bounds: ContentBounds,
    ) {
        if (endedPointerId != null) {
            val endPos = eventPositions[endedPointerId]
            if (endPos != null) {
                activePointerPositions[endedPointerId] = endPos
            }
            releasePointer(endedPointerId, bounds)
        }
    }

}
