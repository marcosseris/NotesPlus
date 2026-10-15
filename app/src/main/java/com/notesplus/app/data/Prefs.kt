package com.notesplus.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.notesplus.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class Settings(
    val clientIdOverride: String = "",
    val syncFolderPath: String = "/NotesPlus",
    val syncFolderId: String? = null,
    val deltaLink: String? = null,
    val autoSync: Boolean = true,
    val syncOnMeteredNetworks: Boolean = true,
    val fingerDrawing: Boolean = false,
    val stylusButtonErases: Boolean = true,
    val exportAnnotatedPdfOnSync: Boolean = false,
    val accountName: String? = null,
    val lastSyncAt: Long = 0L,
    val lastSyncStatus: String = "",
    val penColor: Int = 0xFF1C1C1E.toInt(),
    val penWidth: Float = 2.2f,
    val highlighterColor: Int = 0xFFFFE627.toInt(),
    val highlighterWidth: Float = 14f,
    val eraserRadius: Float = 10f,
    val defaultPaper: Int = 1
) {
    val effectiveClientId: String get() = clientIdOverride.ifBlank { BuildConfig.ONEDRIVE_CLIENT_ID }
}

class Prefs(private val context: Context) {
    private object K {
        val clientId = stringPreferencesKey("client_id")
        val folderPath = stringPreferencesKey("sync_folder_path")
        val folderId = stringPreferencesKey("sync_folder_id")
        val deltaLink = stringPreferencesKey("delta_link")
        val autoSync = booleanPreferencesKey("auto_sync")
        val metered = booleanPreferencesKey("sync_metered")
        val fingerDrawing = booleanPreferencesKey("finger_drawing")
        val stylusButtonErases = booleanPreferencesKey("stylus_button_erases")
        val exportOnSync = booleanPreferencesKey("export_on_sync")
        val accountName = stringPreferencesKey("account_name")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastSyncStatus = stringPreferencesKey("last_sync_status")
        val penColor = intPreferencesKey("pen_color")
        val penWidth = floatPreferencesKey("pen_width")
        val hlColor = intPreferencesKey("hl_color")
        val hlWidth = floatPreferencesKey("hl_width")
        val eraserRadius = floatPreferencesKey("eraser_radius")
        val defaultPaper = intPreferencesKey("default_paper")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val d = Settings()
        Settings(
            clientIdOverride = p[K.clientId] ?: d.clientIdOverride,
            syncFolderPath = p[K.folderPath] ?: d.syncFolderPath,
            syncFolderId = p[K.folderId],
            deltaLink = p[K.deltaLink],
            autoSync = p[K.autoSync] ?: d.autoSync,
            syncOnMeteredNetworks = p[K.metered] ?: d.syncOnMeteredNetworks,
            fingerDrawing = p[K.fingerDrawing] ?: d.fingerDrawing,
            stylusButtonErases = p[K.stylusButtonErases] ?: d.stylusButtonErases,
            exportAnnotatedPdfOnSync = p[K.exportOnSync] ?: d.exportAnnotatedPdfOnSync,
            accountName = p[K.accountName],
            lastSyncAt = p[K.lastSyncAt] ?: 0L,
            lastSyncStatus = p[K.lastSyncStatus] ?: "",
            penColor = p[K.penColor] ?: d.penColor,
            penWidth = p[K.penWidth] ?: d.penWidth,
            highlighterColor = p[K.hlColor] ?: d.highlighterColor,
            highlighterWidth = p[K.hlWidth] ?: d.highlighterWidth,
            eraserRadius = p[K.eraserRadius] ?: d.eraserRadius,
            defaultPaper = p[K.defaultPaper] ?: d.defaultPaper
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setClientIdOverride(v: String) = context.dataStore.edit { it[K.clientId] = v.trim() }
    suspend fun setSyncFolder(path: String, id: String?) = context.dataStore.edit {
        it[K.folderPath] = path
        if (id == null) it.remove(K.folderId) else it[K.folderId] = id
        it.remove(K.deltaLink)
    }
    suspend fun setDeltaLink(v: String?) = context.dataStore.edit { if (v == null) it.remove(K.deltaLink) else it[K.deltaLink] = v }
    suspend fun setAutoSync(v: Boolean) = context.dataStore.edit { it[K.autoSync] = v }
    suspend fun setSyncOnMetered(v: Boolean) = context.dataStore.edit { it[K.metered] = v }
    suspend fun setFingerDrawing(v: Boolean) = context.dataStore.edit { it[K.fingerDrawing] = v }
    suspend fun setStylusButtonErases(v: Boolean) = context.dataStore.edit { it[K.stylusButtonErases] = v }
    suspend fun setExportOnSync(v: Boolean) = context.dataStore.edit { it[K.exportOnSync] = v }
    suspend fun setAccountName(v: String?) = context.dataStore.edit { if (v == null) it.remove(K.accountName) else it[K.accountName] = v }
    suspend fun setLastSync(at: Long, status: String) = context.dataStore.edit { it[K.lastSyncAt] = at; it[K.lastSyncStatus] = status }
    suspend fun setPen(color: Int, width: Float) = context.dataStore.edit { it[K.penColor] = color; it[K.penWidth] = width }
    suspend fun setHighlighter(color: Int, width: Float) = context.dataStore.edit { it[K.hlColor] = color; it[K.hlWidth] = width }
    suspend fun setEraserRadius(v: Float) = context.dataStore.edit { it[K.eraserRadius] = v }
    suspend fun setDefaultPaper(v: Int) = context.dataStore.edit { it[K.defaultPaper] = v }

    /** Called on sign-out: forget everything tied to the account but keep local notes. */
    suspend fun clearAccountState() = context.dataStore.edit {
        it.remove(K.folderId); it.remove(K.deltaLink); it.remove(K.accountName)
        it[K.lastSyncStatus] = ""
    }
}
