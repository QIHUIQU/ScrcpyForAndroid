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

    private val SHOWING = Pattern.compile("(?:^|\\s)showing=(true|false)(?:\\s|$)")

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
}
