package io.github.miuzarte.scrcpyforandroid.scrcpy

import kotlin.math.roundToInt

/**
 * ViewportFit
 *
 * Pure viewport math shared by touch handling and covered by JVM unit tests.
 * Deliberately free of Android/Compose dependencies.
 */
object ViewportFit {
    data class ContentBounds(
        val width: Float,
        val height: Float,
        val left: Float,
        val top: Float,
    )

    fun sessionAspectOf(width: Int, height: Int): Float =
        if (height == 0) 16f / 9f else width.toFloat() / height.toFloat()

    /**
     * Bounds of the displayed video content inside a container of the given size.
     *
     * - [stretch] = true (stretch-fill mode): content fills the whole container
     *   (non-uniform scaling), bounds = the container itself.
     * - [stretch] = false (keep-aspect): content is fitted by aspect ratio and
     *   centered, bounds = the letterboxed content area.
     */
    fun contentBounds(
        containerWidth: Float,
        containerHeight: Float,
        sessionAspect: Float,
        stretch: Boolean,
    ): ContentBounds {
        if (stretch) {
            return ContentBounds(containerWidth, containerHeight, 0f, 0f)
        }
        val containerAspect = containerWidth / containerHeight
        val contentWidth: Float
        val contentHeight: Float
        if (sessionAspect > containerAspect) {
            contentWidth = containerWidth
            contentHeight = containerWidth / sessionAspect
        } else {
            contentHeight = containerHeight
            contentWidth = containerHeight * sessionAspect
        }
        return ContentBounds(
            contentWidth,
            contentHeight,
            (containerWidth - contentWidth) / 2f,
            (containerHeight - contentHeight) / 2f,
        )
    }

    /** Map a raw position inside [bounds] to device coordinates (0..width-1, 0..height-1). */
    fun mapToDevice(
        rawX: Float,
        rawY: Float,
        bounds: ContentBounds,
        deviceWidth: Int,
        deviceHeight: Int,
    ): Pair<Int, Int> {
        val normalizedX = ((rawX - bounds.left) / bounds.width).coerceIn(0f, 1f)
        val normalizedY = ((rawY - bounds.top) / bounds.height).coerceIn(0f, 1f)
        val x = (normalizedX * (deviceWidth - 1).coerceAtLeast(0)).roundToInt()
            .coerceIn(0, (deviceWidth - 1).coerceAtLeast(0))
        val y = (normalizedY * (deviceHeight - 1).coerceAtLeast(0)).roundToInt()
            .coerceIn(0, (deviceHeight - 1).coerceAtLeast(0))
        return x to y
    }
}
