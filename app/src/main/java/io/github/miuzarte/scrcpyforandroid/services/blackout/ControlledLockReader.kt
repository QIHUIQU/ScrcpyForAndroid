package io.github.miuzarte.scrcpyforandroid.services.blackout

import java.util.regex.Pattern

/**
 * ControlledLockReader
 *
 * Read-only controlled-device lock observation for PB-1 (port of the lock-state part of
 * EasyControl's ControlledLockWake): parses `dumpsys window policy` for the Keyguard
 * `showing=` flag. Anything else (missing field, fetch failure, ambiguous output) is
 * UNKNOWN and callers must not act on it (never treat fetch failure as "locked").
 */
object ControlledLockReader {
    /** Read-only query run over the existing session adb shell channel. */
    const val LOCK_STATE_CMD = "dumpsys window policy"

    /**
     * Read-only query for the controlled device's real panel power. This is the authoritative
     * blackout signal (the blackout is a SurfaceControl/SurfaceFlinger display power mode, so
     * the framework screen state, `dumpsys display` state and screenshots do NOT change — see
     * flow/踩坑记录.md 2026-10-14/v10).
     */
    const val DISPLAY_POWER_CMD = "dumpsys SurfaceFlinger --displays"

    private val SHOWING = Pattern.compile("(?:^|\\s)showing=(true|false)(?:\\s|$)")
    private val VIRTUAL_DISPLAY_HEADER = Pattern.compile("^Virtual Display\\s")
    private val DISPLAY_HEADER = Pattern.compile("^Display\\s")
    private val POWER_MODE = Pattern.compile("^powerMode=(\\S+)$")

    fun parse(output: String?): PaymentBlackoutStateMachine.LockObservation {
        if (output.isNullOrEmpty()) return PaymentBlackoutStateMachine.LockObservation.UNKNOWN
        val matcher = SHOWING.matcher(output)
        if (!matcher.find()) return PaymentBlackoutStateMachine.LockObservation.UNKNOWN
        return when (matcher.group(1)) {
            "true" -> PaymentBlackoutStateMachine.LockObservation.LOCKED
            "false" -> PaymentBlackoutStateMachine.LockObservation.UNLOCKED
            else -> PaymentBlackoutStateMachine.LockObservation.UNKNOWN
        }
    }

    /**
     * Physical panel power parsed from `dumpsys SurfaceFlinger --displays`.
     *
     * Virtual displays (the scrcpy/vendor virtual displays) are ignored — only physical panels
     * count, and there may be more than one (foldables expose inner + outer). Only a `powerMode=`
     * line inside a physical display block is counted. Conservative reading so an unexpected dump
     * can never make callers act:
     *  - OFF     : every parsed physical display reports `powerMode=Off`;
     *  - ON      : at least one physical display reports any other mode (On/Doze/…);
     *  - UNKNOWN : no physical `powerMode=` line found (missing field, fetch failure, or an
     *              output shape we do not understand) — never acted upon.
     */
    fun parseDisplayPower(output: String?): PaymentBlackoutStateMachine.DisplayObservation {
        if (output.isNullOrEmpty()) return PaymentBlackoutStateMachine.DisplayObservation.UNKNOWN
        var physical = 0
        var allOff = true
        var inPhysicalBlock = false
        for (rawLine in output.split('\n')) {
            val line = rawLine.trim()
            if (VIRTUAL_DISPLAY_HEADER.matcher(line).find()) {
                inPhysicalBlock = false
                continue
            }
            if (DISPLAY_HEADER.matcher(line).find()) {
                inPhysicalBlock = true
                continue
            }
            if (!inPhysicalBlock) continue
            val matcher = POWER_MODE.matcher(line)
            if (!matcher.find()) continue
            physical++
            if (!matcher.group(1).equals("Off", ignoreCase = true)) allOff = false
        }
        if (physical == 0) return PaymentBlackoutStateMachine.DisplayObservation.UNKNOWN
        return if (allOff) {
            PaymentBlackoutStateMachine.DisplayObservation.OFF
        } else {
            PaymentBlackoutStateMachine.DisplayObservation.ON
        }
    }
}
