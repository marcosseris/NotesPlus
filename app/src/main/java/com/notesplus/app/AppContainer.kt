package com.notesplus.app

import android.content.Context
import com.notesplus.app.cloud.AuthedHttp
import com.notesplus.app.cloud.CloudProvider
import com.notesplus.app.cloud.CloudRepository
import com.notesplus.app.cloud.GoogleDriveProvider
import com.notesplus.app.cloud.OneDriveProvider
import com.notesplus.app.cloud.SyncEngine
import com.notesplus.app.cloud.SyncScheduler
import com.notesplus.app.cloud.auth.OAuthManager
import com.notesplus.app.cloud.auth.SecureStore
import com.notesplus.app.data.NoteRepository
import com.notesplus.app.data.Prefs
import com.notesplus.app.data.db.AppDatabase
import com.notesplus.app.data.db.Provider
import com.notesplus.app.export.PdfExporter
import com.notesplus.app.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-rolled dependency graph; small enough not to need a DI framework. */
class AppContainer(context: Context, appScope: CoroutineScope) {
    val prefs = Prefs(context)
    val positions = com.notesplus.app.data.PositionStore(context)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    val secureStore = SecureStore(context)

    val oneDriveAuth = OAuthManager(OAuthManager.oneDrive(BuildConfig.ONEDRIVE_REDIRECT_URI), prefs, secureStore, http)
    val googleAuth = OAuthManager(OAuthManager.googleDrive(BuildConfig.GOOGLE_REDIRECT_URI), prefs, secureStore, http)
    val authManagers: List<OAuthManager> = listOf(oneDriveAuth, googleAuth)
    fun auth(p: Provider): OAuthManager = if (p == Provider.ONEDRIVE) oneDriveAuth else googleAuth

    private val providers: Map<Provider, CloudProvider> = mapOf(
        Provider.ONEDRIVE to OneDriveProvider(AuthedHttp(http, { oneDriveAuth.accessToken() }, { oneDriveAuth.invalidateAccessToken() })),
        Provider.GOOGLE_DRIVE to GoogleDriveProvider(AuthedHttp(http, { googleAuth.accessToken() }, { googleAuth.invalidateAccessToken() }))
    )

    val syncScheduler = SyncScheduler(context, prefs)
    val db = AppDatabase.create(context)
    val repository = NoteRepository(context, db.notes()) { appScope.launch { syncScheduler.requestSync() } }
    val cloudRepository = CloudRepository(db.cloud(), providers, authManagers.associateBy { it.provider })
    val exporter = PdfExporter(context, repository)
    val syncEngine = SyncEngine(repository, cloudRepository, prefs, exporter, context.cacheDir)
    val updateManager = UpdateManager(context, http, secureStore, prefs)
}
