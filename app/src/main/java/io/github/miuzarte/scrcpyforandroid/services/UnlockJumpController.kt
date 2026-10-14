package io.github.miuzarte.scrcpyforandroid.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.miuzarte.scrcpyforandroid.StreamActivity
import io.github.miuzarte.scrcpyforandroid.storage.Storage

/**
 * UnlockJumpController
 *
 * Port of EasyControl's "auto jump back to remote control after unlocking"
 * (autoJumpOnUnlock, default on): when this device is unlocked and a scrcpy
 * session is alive, bring the stream UI back to the front.
 *
 * BAL note (Android 10+): a background activity launch from a receiver may be
 * silently blocked. The fallback is a pending flag: MainActivity.onResume
 * consumes it, so "open the app" lands directly in the stream UI. The flag is
 * cleared whenever the stream UI is shown, so exiting the stream does not
 * bounce the user back in.
 */
object UnlockJumpController {
    private const val TAG = "UnlockJumpController"

    /** Set when an unlock jump was attempted; consumed by MainActivity.onResume / cleared when the stream UI shows. */
    @Volatile
    var pendingJump: Boolean = false

    /** Pure decision predicate (JVM-testable). */
    fun shouldJump(enabled: Boolean, sessionActive: Boolean, streamInForeground: Boolean): Boolean =
        enabled && sessionActive && !streamInForeground

    /** Consume-once read for MainActivity.onResume. */
    fun consumePendingJump(): Boolean {
        val value = pendingJump
        pendingJump = false
        return value
    }

    /** Stream UI became visible: the jump (if any) has landed. */
    fun onStreamUiShown() {
        pendingJump = false
    }

    private var registered = false

    private val receiver: BroadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_PRESENT) return
                val enabled = Storage.appSettings.bundleState.value.autoJumpOnUnlock
                val sessionActive = AppRuntime.scrcpy?.isStarted() == true
                val streamInForeground = StreamActivity.isActive()
                if (!shouldJump(enabled, sessionActive, streamInForeground)) return
                Log.i(TAG, "user present: jumping back to stream (session active)")
                pendingJump = true
                runCatching {
                    context.applicationContext.startActivity(
                        StreamActivity.createIntent(context.applicationContext).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                            )
                        },
                    )
                }.onFailure {
                    // BAL may also block silently; pendingJump covers both cases.
                    Log.w(TAG, "startActivity failed; fallback = open app lands in stream", it)
                }
            }
        }
    }

    fun register(context: Context) {
        if (registered) return
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    fun unregister(context: Context) {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }
}
