package com.notesplus.app.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.notesplus.app.NotesPlusApp
import com.notesplus.app.data.Prefs
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NotesPlusApp
        val c = app.container
        if (c.cloudRepository.signedInProviders().isEmpty()) return Result.success()
        val result = c.syncEngine.sync()
        if (result.writeBackLater) c.syncScheduler.requestSync(delayMs = 60_000L)
        return when {
            result.ok -> Result.success()
            runAttemptCount < 3 -> Result.retry()
            else -> Result.failure()
        }
    }
}

/** Decides *when* to sync: debounced after edits, periodically in the background. */
class SyncScheduler(private val context: Context, private val prefs: Prefs) {
    private val wm get() = WorkManager.getInstance(context)

    private suspend fun constraints(): Constraints {
        val s = prefs.settings.first()
        return Constraints.Builder()
            .setRequiredNetworkType(if (s.syncOnMeteredNetworks) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .build()
    }

    /** A new request replaces the pending one, so rapid edits coalesce. [force] ignores the auto-sync switch. */
    suspend fun requestSync(delayMs: Long = 8_000L, force: Boolean = false) {
        if (!force && !prefs.settings.first().autoSync) return
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        wm.enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, req)
    }

    suspend fun ensurePeriodic() {
        if (!prefs.settings.first().autoSync) { wm.cancelUniqueWork(PERIODIC); return }
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancelAll() { wm.cancelAllWorkByTag(TAG) }

    companion object {
        private const val TAG = "notesplus-sync"
        private const val ONE_TIME = "notesplus-sync-now"
        private const val PERIODIC = "notesplus-sync-periodic"
    }
}
