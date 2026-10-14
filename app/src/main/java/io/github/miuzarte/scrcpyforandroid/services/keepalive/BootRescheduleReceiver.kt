package io.github.miuzarte.scrcpyforandroid.services.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.miuzarte.scrcpyforandroid.storage.Storage

/** Re-asserts the periodic keep-alive job after boot when the feature is enabled. */
class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Storage.appSettings.bundleState.value.processKeepAliveEnabled) {
            Log.i(TAG, "boot completed: re-asserting keep-alive job")
            ProcessKeepAliveScheduler.ensureScheduled(context)
        }
    }

    private companion object {
        private const val TAG = "KeepAliveBoot"
    }
}
