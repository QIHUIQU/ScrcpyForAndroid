package io.github.miuzarte.scrcpyforandroid.scrcpy

import java.util.Locale
import kotlin.math.roundToInt

/**
 * GammaSettings
 *
 * Pure logic for the gamma slider, ported from EasyControl's TransformSettings /
 * GammaSliderMap / GammaRenderProfile:
 * - range [0.5, 2.0], default 1.25 (mid-range), step 0.01, slider progress 0..150;
 * - legal values pass through; NaN/Inf/out-of-range fall back to the default;
 * - rendering applies a per-channel gain (== gamma) in the EGL shader, the same
 *   affine approximation the original uses (gamma > 1 brightens).
 */
object GammaSettings {
    const val MIN_GAMMA = 0.5f
    const val MAX_GAMMA = 2.0f
    const val DEFAULT_GAMMA = 1.25f
    const val STEP = 0.01f

    /** (MAX_GAMMA - MIN_GAMMA) / STEP */
    const val SEEK_MAX = 150

    fun isLegal(value: Float): Boolean =
        !value.isNaN() && !value.isInfinite() && value >= MIN_GAMMA && value <= MAX_GAMMA

    /** Legal values pass through; non-finite / out-of-range fall back to [DEFAULT_GAMMA]. */
    fun normalize(value: Float): Float = if (isLegal(value)) value else DEFAULT_GAMMA

    /** gamma -> slider progress (rounded to the nearest step, clamped to [0, SEEK_MAX]). */
    fun gammaToProgress(gamma: Float): Int =
        ((gamma - MIN_GAMMA) / STEP).roundToInt().coerceIn(0, SEEK_MAX)

    /** slider progress -> gamma (clamped, snapped to the 0.01 grid). */
    fun progressToGamma(progress: Int): Float {
        val p = progress.coerceIn(0, SEEK_MAX)
        return ((MIN_GAMMA + p * STEP) * 100f).roundToInt() / 100f
    }

    /** Deterministic display format (two decimals). */
    fun format(gamma: Float): String = String.format(Locale.ROOT, "%.2f", gamma)
}
