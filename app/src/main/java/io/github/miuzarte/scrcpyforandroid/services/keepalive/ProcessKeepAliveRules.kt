package io.github.miuzarte.scrcpyforandroid.services.keepalive

/**
 * ProcessKeepAliveRules
 *
 * Pure constants/decisions for the process keep-alive (port of EasyControl's C2
 * keep-alive, minimal closed loop: foreground service + JobScheduler >= 15 min +
 * boot reschedule).
 *
 * Naming note: this is PROCESS survival, distinct from the existing connection-level
 * keepAlive (DeviceAdbAutoReconnectManager.runKeepAliveLoop).
 */
object ProcessKeepAliveRules {
    /** JobScheduler job id (single source). */
    const val JOB_ID = 0xEC02

    /** Platform lower bound for periodic jobs (15 min). */
    const val MIN_PERIODIC_INTERVAL_MS = 15L * 60L * 1000L

    /** Periodic interval: exactly the platform lower bound. */
    const val PERIODIC_INTERVAL_MS = MIN_PERIODIC_INTERVAL_MS

    /** Schedule the periodic job when the feature is enabled. */
    fun shouldSchedule(enabled: Boolean): Boolean = enabled

    /**
     * Run the foreground service while enabled and either a session is started or a crash
     * recovery is pending (the service itself decides whether an actual recovery is due).
     */
    fun shouldRunService(
        enabled: Boolean,
        sessionActive: Boolean,
        recoveryPending: Boolean = false,
    ): Boolean = enabled && (sessionActive || recoveryPending)
}
