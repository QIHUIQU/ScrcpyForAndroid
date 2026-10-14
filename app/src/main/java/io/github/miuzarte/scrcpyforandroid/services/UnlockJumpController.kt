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
 * Registration lifetime = session lifetime: registered from [io.github.miuzarte.scrcpyforandroid.NativeCoreFacade]
 * when a session starts and unregistered when it stops. MainActivity may be
 * gone (or the app fully backgrounded) while a session is alive, so its
 * lifecycle must not own the receiver.
 *
 * BAL note (Android 10+): a background activity launch may be silently
 * blocked. Fallback: when the user later opens the app while a session is
 * alive, MainActivity routes straight into the stream UI (re-entry check in
 * [AppVisibility]), so the jump is never a hard requirement.
 *
 * Logs (tag [TAG], greppable): every USER_PRESENT delivery is logged,
 * including no-op reasons.
 */
object UnlockJumpController {
    const val TAG = "UnlockJump"

    /** Pure decision predicate (JVM-testable). */
    fun shouldJump(enabled: Boolean, sessionActive: Boolean, streamInForeground: Boolean): Boolean =
        enabled && sessionActive && !streamInForeground

    private var registered = false

    private val receiver: BroadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_PRESENT) return
                val enabled = Storage.appSettings.bundleState.value.autoJumpOnUnlock
                val sessionActive = AppRuntime.scrcpy?.isStarted() == true
                val streamInForeground = StreamActivity.isActive()
                val jump = shouldJump(enabled, sessionActive, streamInForeground)
                Log.i(
                    TAG,
                    "user_present: enabled=$enabled sessionActive=$sessionActive " +
                        "streamForeground=$streamInForeground -> ${if (jump) "jump" else "noop"}",
                )
                if (!jump) return
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
                    // BAL may also block silently; opening the app later lands in the
                    // stream via the MainActivity re-entry check.
                    Log.w(TAG, "jump startActivity failed (BAL?)", it)
                }
            }
        }
    }

    /** Registered for the lifetime of a scrcpy session (see NativeCoreFacade). */
    fun register(context: Context) {
        if (registered) return
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
        Log.i(TAG, "receiver registered (session lifetime)")
    }

    fun unregister() {
        if (!registered) return
        runCatching { AppRuntime.context.unregisterReceiver(receiver) }
        registered = false
        Log.i(TAG, "receiver unregistered")
    }
}
