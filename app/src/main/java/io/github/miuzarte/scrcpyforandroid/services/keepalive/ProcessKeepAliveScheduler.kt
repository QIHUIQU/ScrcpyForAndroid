package io.github.miuzarte.scrcpyforandroid.services.keepalive

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log

/** Schedules/cancels the periodic keep-alive job (single place for the job spec). */
object ProcessKeepAliveScheduler {
    private const val TAG = "KeepAliveScheduler"

    fun ensureScheduled(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(
            ProcessKeepAliveRules.JOB_ID,
            ComponentName(context, KeepAliveJobService::class.java),
        )
            .setPeriodic(ProcessKeepAliveRules.PERIODIC_INTERVAL_MS)
            .setPersisted(true)
            .build()
        runCatching { scheduler.schedule(job) }
            .onSuccess {
                Log.i(TAG, "job scheduled (interval=${ProcessKeepAliveRules.PERIODIC_INTERVAL_MS}ms)")
            }
            .onFailure { Log.w(TAG, "schedule failed", it) }
    }

    fun cancel(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        runCatching { scheduler.cancel(ProcessKeepAliveRules.JOB_ID) }
        Log.i(TAG, "job cancelled")
    }
}
