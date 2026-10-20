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
import com.notesplus.app.ink.PaperStyle
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

/** Default pen inks: neutral to vivid, all legible on white paper. */
val DEFAULT_PEN_PALETTE: List<Int> = listOf(
    0xFF1C1C1E, 0xFF5F6368, 0xFF1F4E79, 0xFF2962FF, 0xFF0288D1, 0xFF00897B,
    0xFF2E7D32, 0xFF7CB342, 0xFFFFB300, 0xFFEF6C00, 0xFFD32F2F, 0xFFC2185B,
    0xFFEC407A, 0xFF6A1B9A, 0xFF7E57C2, 0xFF5D4037
).map { it.toInt() }

/** Default highlighters: light, saturated colours that multiply nicely over text. */
val DEFAULT_HIGHLIGHTER_PALETTE: List<Int> = listOf(
    0xFFFFE627, 0xFFB4FF4A, 0xFF69F0AE, 0xFF4FD0FF, 0xFF82B1FF, 0xFFFF7EB6,
    0xFFEA80FC, 0xFFFFA726, 0xFFFFCC80, 0xFFCFD8DC
).map { it.toInt() }

data class Settings(
    val oneDriveClientIdOverride: String = "",
    val googleClientIdOverride: String = "",
    val oneDriveAccount: String? = null,
    val googleAccount: String? = null,
    val autoSync: Boolean = true,
    val syncOnMeteredNetworks: Boolean = true,
    val writeBack: WriteBackMode = WriteBackMode.IN_PLACE,
    val fingerDrawing: Boolean = false,
    /** One finger does nothing on the page; scroll and zoom with two fingers (palm-proof). */
    val palmGuard: Boolean = true,
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
    /** User-editable palettes shown on the rail and in the picker. */
    val penPalette: List<Int> = DEFAULT_PEN_PALETTE,
    val highlighterPalette: List<Int> = DEFAULT_HIGHLIGHTER_PALETTE,
    /** Paper pattern for pages inserted into a PDF. */
    val insertPaper: PaperStyle = PaperStyle.RULED,
    /** Check GitHub for new builds and install them automatically. */
    val autoUpdate: Boolean = true,
    val lastUpdateCheck: Long = 0L,
    /** Set once every known note has had its sidecar moved into the .ink folder. */
    val inkFolderMigrated: Boolean = false
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
        val palmGuard = booleanPreferencesKey("palm_guard")
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
        val penPalette = stringPreferencesKey("pen_palette")
        val hlPalette = stringPreferencesKey("hl_palette")
        val insertPaper = stringPreferencesKey("insert_paper")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val inkFolderMigrated = booleanPreferencesKey("ink_folder_migrated")
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
            palmGuard = p[K.palmGuard] ?: d.palmGuard,
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
            penPalette = p[K.penPalette]?.toColorList()?.takeIf { it.isNotEmpty() } ?: DEFAULT_PEN_PALETTE,
            highlighterPalette = p[K.hlPalette]?.toColorList()?.takeIf { it.isNotEmpty() } ?: DEFAULT_HIGHLIGHTER_PALETTE,
            insertPaper = p[K.insertPaper]?.let { runCatching { PaperStyle.valueOf(it) }.getOrNull() } ?: PaperStyle.RULED,
            autoUpdate = p[K.autoUpdate] ?: d.autoUpdate,
            lastUpdateCheck = p[K.lastUpdateCheck] ?: 0L,
            inkFolderMigrated = p[K.inkFolderMigrated] ?: false
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
    suspend fun setPalmGuard(v: Boolean) = context.dataStore.edit { it[K.palmGuard] = v }
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
    suspend fun setPenPalette(colors: List<Int>) = context.dataStore.edit { it[K.penPalette] = colors.joinToString(",") { c -> c.toLong().toString() } }
    suspend fun setHighlighterPalette(colors: List<Int>) = context.dataStore.edit { it[K.hlPalette] = colors.joinToString(",") { c -> c.toLong().toString() } }
    suspend fun setInsertPaper(v: PaperStyle) = context.dataStore.edit { it[K.insertPaper] = v.name }
    suspend fun setAutoUpdate(v: Boolean) = context.dataStore.edit { it[K.autoUpdate] = v }
    suspend fun setInkFolderMigrated(v: Boolean) = context.dataStore.edit { it[K.inkFolderMigrated] = v }
    suspend fun setLastUpdateCheck(v: Long) = context.dataStore.edit { it[K.lastUpdateCheck] = v }
    suspend fun pushRecentColor(color: Int) = context.dataStore.edit { p ->
        val existing = p[K.recentColors]?.split(',')?.mapNotNull { it.toLongOrNull()?.toInt() } ?: emptyList()
        p[K.recentColors] = (listOf(color) + existing.filter { it != color }).take(12).joinToString(",") { it.toLong().toString() }
    }
}

private fun String.toColorList(): List<Int> = split(',').mapNotNull { it.toLongOrNull()?.toInt() }
