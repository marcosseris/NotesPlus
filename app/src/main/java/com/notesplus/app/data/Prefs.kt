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
import com.notesplus.app.data.db.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** How annotations are written back into PDFs on the drive so other devices can see them. */
enum class WriteBackMode(val label: String, val description: String) {
    IN_PLACE("Into the PDF itself", "The PDF on your drive is rewritten with your annotations on a separate layer. The original pages stay inside it untouched."),
    SIBLING("Separate annotated copy", "Keeps the original PDF as is and writes “<name>.annotated.pdf” next to it."),
    OFF("Off", "Only the small annotation file is synced; other devices need NotesPlus to see your ink.")
}

data class Settings(
    val oneDriveClientIdOverride: String = "",
    val googleClientIdOverride: String = "",
    val oneDriveAccount: String? = null,
    val googleAccount: String? = null,
    val autoSync: Boolean = true,
    val syncOnMeteredNetworks: Boolean = true,
    val writeBack: WriteBackMode = WriteBackMode.IN_PLACE,
    val fingerDrawing: Boolean = false,
    val stylusButtonErases: Boolean = true,
    val lastSyncAt: Long = 0L,
    val lastSyncStatus: String = "",
    val penColor: Int = 0xFF1C1C1E.toInt(),
    val penWidth: Float = 2.2f,
    val highlighterColor: Int = 0xFFFFE627.toInt(),
    val highlighterWidth: Float = 14f,
    val eraserRadius: Float = 10f,
    val autoShape: Boolean = true,
    val railOnRight: Boolean = false,
    val lastShape: Int = 0,
    val motionPrediction: Boolean = true,
    val straightHighlighter: Boolean = true,
    val recentColors: List<Int> = emptyList(),
    /** Check GitHub for new builds and install them automatically. */
    val autoUpdate: Boolean = true,
    val lastUpdateCheck: Long = 0L
) {
    fun clientId(p: Provider): String = when (p) {
        Provider.ONEDRIVE -> oneDriveClientIdOverride.ifBlank { BuildConfig.ONEDRIVE_CLIENT_ID }
        Provider.GOOGLE_DRIVE -> googleClientIdOverride.ifBlank { BuildConfig.GOOGLE_CLIENT_ID }
    }
    fun accountName(p: Provider): String? = when (p) { Provider.ONEDRIVE -> oneDriveAccount; Provider.GOOGLE_DRIVE -> googleAccount }
}

class Prefs(private val context: Context) {
    private object K {
        val odClientId = stringPreferencesKey("client_id")
        val gdClientId = stringPreferencesKey("google_client_id")
        val odAccount = stringPreferencesKey("account_name")
        val gdAccount = stringPreferencesKey("google_account_name")
        val autoSync = booleanPreferencesKey("auto_sync")
        val metered = booleanPreferencesKey("sync_metered")
        val writeBack = stringPreferencesKey("write_back_mode")
        val fingerDrawing = booleanPreferencesKey("finger_drawing")
        val stylusButtonErases = booleanPreferencesKey("stylus_button_erases")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastSyncStatus = stringPreferencesKey("last_sync_status")
        val penColor = intPreferencesKey("pen_color")
        val penWidth = floatPreferencesKey("pen_width")
        val hlColor = intPreferencesKey("hl_color")
        val hlWidth = floatPreferencesKey("hl_width")
        val eraserRadius = floatPreferencesKey("eraser_radius")
        val autoShape = booleanPreferencesKey("auto_shape")
        val railOnRight = booleanPreferencesKey("rail_on_right")
        val lastShape = intPreferencesKey("last_shape")
        val motionPrediction = booleanPreferencesKey("motion_prediction")
        val straightHighlighter = booleanPreferencesKey("straight_highlighter")
        val recentColors = stringPreferencesKey("recent_colors")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val d = Settings()
        Settings(
            oneDriveClientIdOverride = p[K.odClientId] ?: "",
            googleClientIdOverride = p[K.gdClientId] ?: "",
            oneDriveAccount = p[K.odAccount],
            googleAccount = p[K.gdAccount],
            autoSync = p[K.autoSync] ?: d.autoSync,
            syncOnMeteredNetworks = p[K.metered] ?: d.syncOnMeteredNetworks,
            writeBack = p[K.writeBack]?.let { runCatching { WriteBackMode.valueOf(it) }.getOrNull() } ?: d.writeBack,
            fingerDrawing = p[K.fingerDrawing] ?: d.fingerDrawing,
            stylusButtonErases = p[K.stylusButtonErases] ?: d.stylusButtonErases,
            lastSyncAt = p[K.lastSyncAt] ?: 0L,
            lastSyncStatus = p[K.lastSyncStatus] ?: "",
            penColor = p[K.penColor] ?: d.penColor,
            penWidth = p[K.penWidth] ?: d.penWidth,
            highlighterColor = p[K.hlColor] ?: d.highlighterColor,
            highlighterWidth = p[K.hlWidth] ?: d.highlighterWidth,
            eraserRadius = p[K.eraserRadius] ?: d.eraserRadius,
            autoShape = p[K.autoShape] ?: d.autoShape,
            railOnRight = p[K.railOnRight] ?: d.railOnRight,
            lastShape = p[K.lastShape] ?: d.lastShape,
            motionPrediction = p[K.motionPrediction] ?: d.motionPrediction,
            straightHighlighter = p[K.straightHighlighter] ?: d.straightHighlighter,
            recentColors = p[K.recentColors]?.split(',')?.mapNotNull { it.toLongOrNull()?.toInt() } ?: emptyList(),
            autoUpdate = p[K.autoUpdate] ?: d.autoUpdate,
            lastUpdateCheck = p[K.lastUpdateCheck] ?: 0L
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setClientIdOverride(p: Provider, v: String) = context.dataStore.edit {
        it[if (p == Provider.ONEDRIVE) K.odClientId else K.gdClientId] = v.trim()
    }
    suspend fun setAccountName(p: Provider, v: String?) = context.dataStore.edit {
        val key = if (p == Provider.ONEDRIVE) K.odAccount else K.gdAccount
        if (v == null) it.remove(key) else it[key] = v
    }
    suspend fun setAutoSync(v: Boolean) = context.dataStore.edit { it[K.autoSync] = v }
    suspend fun setSyncOnMetered(v: Boolean) = context.dataStore.edit { it[K.metered] = v }
    suspend fun setWriteBack(v: WriteBackMode) = context.dataStore.edit { it[K.writeBack] = v.name }
    suspend fun setFingerDrawing(v: Boolean) = context.dataStore.edit { it[K.fingerDrawing] = v }
    suspend fun setStylusButtonErases(v: Boolean) = context.dataStore.edit { it[K.stylusButtonErases] = v }
    suspend fun setLastSync(at: Long, status: String) = context.dataStore.edit { it[K.lastSyncAt] = at; it[K.lastSyncStatus] = status }
    suspend fun setPen(color: Int, width: Float) = context.dataStore.edit { it[K.penColor] = color; it[K.penWidth] = width }
    suspend fun setHighlighter(color: Int, width: Float) = context.dataStore.edit { it[K.hlColor] = color; it[K.hlWidth] = width }
    suspend fun setEraserRadius(v: Float) = context.dataStore.edit { it[K.eraserRadius] = v }
    suspend fun setAutoShape(v: Boolean) = context.dataStore.edit { it[K.autoShape] = v }
    suspend fun setRailOnRight(v: Boolean) = context.dataStore.edit { it[K.railOnRight] = v }
    suspend fun setLastShape(v: Int) = context.dataStore.edit { it[K.lastShape] = v }
    suspend fun setMotionPrediction(v: Boolean) = context.dataStore.edit { it[K.motionPrediction] = v }
    suspend fun setStraightHighlighter(v: Boolean) = context.dataStore.edit { it[K.straightHighlighter] = v }
    suspend fun setAutoUpdate(v: Boolean) = context.dataStore.edit { it[K.autoUpdate] = v }
    suspend fun setLastUpdateCheck(v: Long) = context.dataStore.edit { it[K.lastUpdateCheck] = v }
    suspend fun pushRecentColor(color: Int) = context.dataStore.edit { p ->
        val existing = p[K.recentColors]?.split(',')?.mapNotNull { it.toLongOrNull()?.toInt() } ?: emptyList()
        p[K.recentColors] = (listOf(color) + existing.filter { it != color }).take(12).joinToString(",") { it.toLong().toString() }
    }
}
