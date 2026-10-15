package com.notesplus.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotesPlusApp : Application(), Configuration.Provider {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, appScope)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(SYNC_CHANNEL, getString(R.string.sync_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.sync_channel_desc) }
        )
        appScope.launch { container.syncScheduler.ensurePeriodic() }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    companion object { const val SYNC_CHANNEL = "sync" }
}
