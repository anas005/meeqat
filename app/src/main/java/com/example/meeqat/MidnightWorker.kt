package com.example.meeqat

import android.content.Context
import android.content.ComponentName
import android.appwidget.AppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Redraws the widget just after local midnight, then schedules the next day. No GPS is used here. */
class MidnightWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        MeeqatWidget().updateAll(applicationContext)
        DaylightYearWidget().updateAll(applicationContext)
        if (inputData.getBoolean(KEY_RESCHEDULE, true)) {
            enqueue(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "meeqat_midnight"
        private const val NAME_NOW = "meeqat_now"
        private const val KEY_RESCHEDULE = "reschedule"

        fun schedule(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE)

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }

        fun cancelIfNoWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val hasMeeqatWidgets = manager.getAppWidgetIds(
                ComponentName(context, MeeqatWidgetReceiver::class.java),
            ).isNotEmpty()
            val hasDaylightWidgets = manager.getAppWidgetIds(
                ComponentName(context, DaylightYearWidgetReceiver::class.java),
            ).isNotEmpty()
            if (!hasMeeqatWidgets && !hasDaylightWidgets) cancel(context)
        }

        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<MidnightWorker>()
                .setInputData(Data.Builder().putBoolean(KEY_RESCHEDULE, false).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME_NOW, ExistingWorkPolicy.REPLACE, req)
        }

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val now = ZonedDateTime.now()
            val next = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).plusMinutes(2)
            val delayMs = Duration.between(now, next).toMillis()
            val req = OneTimeWorkRequestBuilder<MidnightWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, req)
        }
    }
}
