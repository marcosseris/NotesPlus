package com.notesplus.app

import android.content.Context
import com.notesplus.app.data.NoteRepository
import com.notesplus.app.data.Prefs
import com.notesplus.app.data.db.AppDatabase
import com.notesplus.app.export.PdfExporter
import com.notesplus.app.onedrive.GraphClient
import com.notesplus.app.onedrive.SyncEngine
import com.notesplus.app.onedrive.SyncScheduler
import com.notesplus.app.onedrive.auth.AuthManager
import com.notesplus.app.onedrive.auth.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-rolled dependency graph; small enough not to need a DI framework. */
class AppContainer(context: Context, private val appScope: CoroutineScope) {
    val prefs = Prefs(context)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    val secureStore = SecureStore(context)
    val authManager = AuthManager(context, prefs, secureStore, http)
    val graph = GraphClient(http, tokenProvider = { authManager.accessToken() }, onUnauthorized = { authManager.invalidateAccessToken() })
    val syncScheduler = SyncScheduler(context, prefs)
    val db = AppDatabase.create(context)
    val repository = NoteRepository(context, db.notes()) {
        appScope.launch { syncScheduler.requestSync() }
    }
    val exporter = PdfExporter(context, repository)
    val syncEngine = SyncEngine(repository, prefs, authManager, graph, exporter)
}
