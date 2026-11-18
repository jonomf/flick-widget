package com.jono.flick

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Redraws the widget every ~15 minutes so the 2×1's "apps you used last" stay current. */
class RefreshJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        runCatching { WidgetRenderer.renderAll(this) }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val ID = 0xF11C

        fun ensure(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(ID) != null) return
            js.schedule(
                JobInfo.Builder(ID, ComponentName(ctx, RefreshJob::class.java))
                    .setPeriodic(15 * 60 * 1000L)
                    .build(),
            )
        }

        fun cancel(ctx: Context) = ctx.getSystemService(JobScheduler::class.java).cancel(ID)
    }
}
