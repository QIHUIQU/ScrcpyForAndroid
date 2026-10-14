package io.github.miuzarte.scrcpyforandroid.scrcpy

import java.util.Locale
import kotlin.math.roundToInt

/**
 * VideoScaleSettings
 *
 * 手动画面缩放的纯逻辑（与 GammaSettings 同构）：
 * - 范围 [0.5, 2.0]、默认 1.0、步进 0.05、滑块进度 0..30；
 * - 合法值直通；NaN/Inf/越界回落到默认；
 * - 渲染端在 EGL 视口做居中缩放（>1 放大裁剪、<1 缩小留黑边），与拉伸填充/gamma 共用同一渲染链
 *   （拉伸填充=比例策略，缩放=倍率；两者叠加，互不冲突）。
 */
object VideoScaleSettings {
    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 2.0f
    const val DEFAULT_SCALE = 1.0f
    const val STEP = 0.05f

    /** (MAX_SCALE - MIN_SCALE) / STEP */
    const val SEEK_MAX = 30

    fun isLegal(value: Float): Boolean =
        !value.isNaN() && !value.isInfinite() && value >= MIN_SCALE && value <= MAX_SCALE

    /** 合法值直通；非有限/越界回落到 [DEFAULT_SCALE]。 */
    fun normalize(value: Float): Float = if (isLegal(value)) value else DEFAULT_SCALE

    /** scale -> 滑块进度（就近取整并夹取到 [0, SEEK_MAX]）。 */
    fun scaleToProgress(scale: Float): Int =
        ((scale - MIN_SCALE) / STEP).roundToInt().coerceIn(0, SEEK_MAX)

    /** 滑块进度 -> scale（夹取并对齐 0.05 网格）。 */
    fun progressToScale(progress: Int): Float {
        val p = progress.coerceIn(0, SEEK_MAX)
        return ((MIN_SCALE + p * STEP) * 100f).roundToInt() / 100f
    }

    /** 确定性显示格式（两位小数 + 倍率符）。 */
    fun format(scale: Float): String = String.format(Locale.ROOT, "%.2f×", normalize(scale))
}
