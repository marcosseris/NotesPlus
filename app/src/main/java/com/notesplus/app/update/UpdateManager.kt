package com.notesplus.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.notesplus.app.BuildConfig
import com.notesplus.app.cloud.auth.SecureStore
import com.notesplus.app.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val assetApiUrl: String,
    val assetName: String,
    val sizeBytes: Long,
    val sha256: String?,
    val notes: String?
)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val release: ReleaseInfo) : UpdateState()
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState()
    data class Installing(val release: ReleaseInfo) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

/**
 * Self-update from GitHub Releases. The release workflow publishes a signed APK tagged
 * v1.0.<build number>; the app compares that number with its own versionCode, downloads
 * the APK and installs it through PackageInstaller. On Android 12+ the install needs no
 * confirmation once NotesPlus has installed itself at least once (it becomes the app's
 * installer of record). The repository is private, so requests carry a read-only token.
 */
class UpdateManager(
    private val context: Context,
    private val http: OkHttpClient,
    private val store: SecureStore,
    private val prefs: Prefs
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Updates only make sense for release builds (debug builds use another package id and key). */
    val supported: Boolean get() = !BuildConfig.DEBUG

    var token: String?
        get() = store.get("github.token")
        set(value) { store.put("github.token", value?.trim()?.takeIf { it.isNotEmpty() }) }

    private fun authed(b: Request.Builder): Request.Builder {
        token?.let { b.header("Authorization", "Bearer $it") }
        return b.header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "NotesPlus/${BuildConfig.VERSION_NAME}")
    }

    /** Looks for a newer release. Returns it (and sets [state]) or null when up to date. */
    suspend fun check(): ReleaseInfo? = mutex.withLock {
        _state.value = UpdateState.Checking
        try {
            val release = withContext(Dispatchers.IO) { fetchLatest() }
            prefs.setLastUpdateCheck(System.currentTimeMillis())
            if (release != null && release.versionCode > BuildConfig.VERSION_CODE) {
                _state.value = UpdateState.Available(release); release
            } else { _state.value = UpdateState.UpToDate; null }
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message ?: "Update check failed"); null
        }
    }

    private fun fetchLatest(): ReleaseInfo? {
        val req = authed(Request.Builder().url("$API/repos/$REPO/releases/latest").header("Accept", "application/vnd.github+json")).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 404) return null
            if (r.code == 401 || r.code == 403) throw IOException(if (token == null) "A GitHub token is needed for this private repository (Settings → Updates)" else "GitHub rejected the token (${r.code})")
            if (!r.isSuccessful) throw IOException("GitHub returned ${r.code}")
            val o = json.parseToJsonElement(text).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.content ?: return null
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val apk = o["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true } ?: return null
            val sha = o["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it["name"]?.jsonPrimitive?.content == "latest.json" }?.let { asset ->
                runCatching {
                    val lreq = authed(Request.Builder().url(asset["url"]!!.jsonPrimitive.content).header("Accept", "application/octet-stream")).build()
                    http.newCall(lreq).execute().use { lr -> json.parseToJsonElement(lr.body?.string().orEmpty()).jsonObject["sha256"]?.jsonPrimitive?.content }
                }.getOrNull()
            }
            return ReleaseInfo(
                versionCode = code,
                versionName = o["name"]?.jsonPrimitive?.content?.ifBlank { null } ?: tag.removePrefix("v"),
                assetApiUrl = apk["url"]!!.jsonPrimitive.content,
                assetName = apk["name"]!!.jsonPrimitive.content,
                sizeBytes = apk["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                sha256 = sha,
                notes = o["body"]?.jsonPrimitive?.content
            )
        }
    }

    /** Downloads [release] and hands it to the package installer. */
    suspend fun downloadAndInstall(release: ReleaseInfo) = mutex.withLock {
        try {
            val apk = File(context.cacheDir, "updates/${release.assetName}").apply { parentFile?.mkdirs() }
            _state.value = UpdateState.Downloading(release, 0f)
            withContext(Dispatchers.IO) {
                val req = authed(Request.Builder().url(release.assetApiUrl).header("Accept", "application/octet-stream")).build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("Download failed (${r.code})")
                    val body = r.body ?: throw IOException("Empty download")
                    val total = if (body.contentLength() > 0) body.contentLength() else release.sizeBytes
                    val digest = MessageDigest.getInstance("SHA-256")
                    apk.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        body.byteStream().use { inp ->
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                                if (total > 0) _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (release.sha256 != null && !release.sha256.equals(hex, ignoreCase = true)) { apk.delete(); throw IOException("Downloaded file is corrupt (checksum mismatch)") }
                }
            }
            _state.value = UpdateState.Installing(release)
            withContext(Dispatchers.IO) { install(apk) }
        } catch (e: Exception) {
            _state.value = UpdateState.Error(e.message ?: "Update failed")
        }
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("notesplus.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }

    /** Called by [InstallResultReceiver]. */
    fun onInstallResult(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.UpToDate
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit // confirmation dialog shown by the receiver
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = UpdateState.Idle
            else -> _state.value = UpdateState.Error(message ?: "Install failed ($status)")
        }
    }

    companion object {
        const val REPO = "marcosseris/NotesPlus"
        private const val API = "https://api.github.com"
    }
}
