package com.notesplus.app.onedrive

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
        val engine = app.container.syncEngine
        if (!app.container.authManager.isSignedIn) return Result.success()
        val result = engine.sync()
        return when {
            result.ok -> Result.success()
            runAttemptCount < 3 -> Result.retry()
            else -> Result.failure()
        }
    }
}

/**
 * Decides *when* to sync. Edits trigger a debounced one-off sync; a periodic job
 * picks up remote changes while the app is in the background.
 */
class SyncScheduler(private val context: Context, private val prefs: Prefs) {
    private val wm get() = WorkManager.getInstance(context)

    private suspend fun constraints(): Constraints {
        val s = prefs.settings.first()
        return Constraints.Builder()
            .setRequiredNetworkType(if (s.syncOnMeteredNetworks) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .build()
    }

    /**
     * Debounced: a new request replaces the pending one, so rapid edits coalesce.
     * Honours the "sync automatically" setting unless [force] is set (explicit user action).
     */
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
