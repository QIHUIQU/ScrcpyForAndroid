package io.github.miuzarte.scrcpyforandroid.services

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * App-level foreground/background tracker.
 *
 * The "re-enter the app with a live session -> land in the stream UI" behavior
 * must distinguish "the user came back to the app from outside" (launcher,
 * recents, unlock) from "an in-app navigation returned to MainActivity".
 * The former is the only case where an activity of this app starts while no
 * other activity of this app is started.
 *
 * [ForegroundTracker] holds the pure logic (JVM-testable); [AppVisibility]
 * feeds it from Application activity lifecycle callbacks.
 */
class ForegroundTracker {
    private var startedCount = 0
    private var pendingReentry = false

    /** @param configChange true when the activity start is a config-change recreation. */
    fun onActivityStarted(configChange: Boolean = false) {
        if (startedCount == 0 && !configChange) pendingReentry = true
        startedCount += 1
    }

    fun onActivityStopped() {
        startedCount = (startedCount - 1).coerceAtLeast(0)
    }

    /** True exactly once per background -> foreground transition. */
    fun consumeReentry(): Boolean {
        val value = pendingReentry
        pendingReentry = false
        return value
    }
}

object AppVisibility {
    private val tracker = ForegroundTracker()
    private var installed = false

    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(
            object: Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    tracker.onActivityStarted(activity.isChangingConfigurations)
                }

                override fun onActivityStopped(activity: Activity) {
                    tracker.onActivityStopped()
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            },
        )
    }

    /** True exactly once per background -> foreground transition. */
    fun consumeReentry(): Boolean = tracker.consumeReentry()
}
