package io.github.miuzarte.scrcpyforandroid.services.blackout

/**
 * PaymentBlackoutConfig
 *
 * Pure constants + normalization for PB-1 (port of EasyControl's PaymentBlackoutConfig):
 * - blackout delay 0..30 s, default 0 (immediate);
 * - fallback (legacy countdown) enabled by default;
 * - fallback threshold whitelist {30, 60, 120, 300} s, default 30 (never earlier than 30 s).
 *
 * Read failures / garbage values always fall back to defaults — never crash.
 */
object PaymentBlackoutConfig {
    /** Stable log prefix (grep-able on device; all PB-1 logs start with this). */
    const val LOG_PREFIX = "PB-1 "

    const val DELAY_MIN_SEC = 0
    const val DELAY_MAX_SEC = 30
    const val DELAY_DEFAULT_SEC = 0

    const val FALLBACK_ENABLED_DEFAULT = true

    val FALLBACK_THRESHOLD_OPTIONS_SEC = intArrayOf(30, 60, 120, 300)
    const val FALLBACK_THRESHOLD_DEFAULT_SEC = 30

    fun normalizeDelaySec(raw: Int): Int = when {
        raw < DELAY_MIN_SEC -> DELAY_DEFAULT_SEC
        raw > DELAY_MAX_SEC -> DELAY_MAX_SEC
        else -> raw
    }

    fun normalizeFallbackEnabled(raw: Boolean?): Boolean = raw ?: FALLBACK_ENABLED_DEFAULT

    fun normalizeFallbackSec(raw: Int): Int =
        if (FALLBACK_THRESHOLD_OPTIONS_SEC.contains(raw)) raw else FALLBACK_THRESHOLD_DEFAULT_SEC

    fun describe(): String =
        "payBlackout delay=[$DELAY_MIN_SEC..$DELAY_MAX_SEC] default=$DELAY_DEFAULT_SEC" +
            " fallbackEnabledDefault=$FALLBACK_ENABLED_DEFAULT" +
            " fallbackThreshold=[${FALLBACK_THRESHOLD_OPTIONS_SEC.joinToString(",")}]" +
            " default=$FALLBACK_THRESHOLD_DEFAULT_SEC"
}
