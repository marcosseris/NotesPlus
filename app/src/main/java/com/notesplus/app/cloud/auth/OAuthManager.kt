package com.notesplus.app.cloud.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.notesplus.app.data.Prefs
import com.notesplus.app.data.db.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

sealed class AuthState {
    data object SignedOut : AuthState()
    data object SigningIn : AuthState()
    data class SignedIn(val accountName: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

class NotSignedInException(provider: Provider) : IOException("Not signed in to ${provider.label}")
class AuthException(message: String) : IOException(message)

/** Everything that differs between identity providers. */
class OAuthSpec(
    val provider: Provider,
    val authorizeUrl: String,
    val tokenUrl: String,
    val scopes: String,
    val redirectUri: String,
    /** Extra query parameters for the authorize request (e.g. access_type=offline for Google). */
    val extraAuthParams: Map<String, String> = emptyMap(),
    /** Fetches a display name for the signed-in account. */
    val fetchAccountName: suspend (http: OkHttpClient, token: String) -> String
)

/**
 * OAuth 2.0 authorization-code + PKCE for a public (installed) client, launched in a Custom
 * Tab. Tokens live in the Keystore-backed [SecureStore] under a per-provider prefix.
 */
class OAuthManager(
    private val spec: OAuthSpec,
    private val prefs: Prefs,
    private val store: SecureStore,
    private val http: OkHttpClient
) {
    val provider: Provider get() = spec.provider
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()
    private val pfx = spec.provider.name.lowercase() + "."

    private val _state = MutableStateFlow<AuthState>(
        if (store.get(pfx + K_REFRESH) != null) AuthState.SignedIn(store.get(pfx + K_ACCOUNT) ?: spec.provider.label) else AuthState.SignedOut
    )
    val state: StateFlow<AuthState> = _state.asStateFlow()
    val isSignedIn: Boolean get() = store.get(pfx + K_REFRESH) != null

    suspend fun startSignIn(activityContext: Context) {
        val clientId = prefs.current().clientId(spec.provider)
        if (clientId.isBlank()) {
            _state.value = AuthState.Error("No client ID configured for ${spec.provider.label}. Add it in Settings (see the README for the 3-minute setup).")
            return
        }
        val verifier = randomUrlSafe(64)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val stateToken = randomUrlSafe(24)
        store.put(pfx + K_PENDING_VERIFIER, verifier)
        store.put(pfx + K_PENDING_STATE, stateToken)
        val b = Uri.parse(spec.authorizeUrl).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", spec.redirectUri)
            .appendQueryParameter("scope", spec.scopes)
            .appendQueryParameter("state", stateToken)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
        spec.extraAuthParams.forEach { (k, v) -> b.appendQueryParameter(k, v) }
        _state.value = AuthState.SigningIn
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activityContext, b.build())
    }

    /** True when [uri] is this provider's redirect. */
    fun owns(uri: Uri): Boolean {
        val r = Uri.parse(spec.redirectUri)
        return uri.scheme.equals(r.scheme, ignoreCase = true) && (r.host.isNullOrEmpty() || uri.host.equals(r.host, ignoreCase = true))
    }

    suspend fun handleRedirect(uri: Uri) {
        val expectedState = store.get(pfx + K_PENDING_STATE)
        val verifier = store.get(pfx + K_PENDING_VERIFIER)
        store.put(pfx + K_PENDING_STATE, null); store.put(pfx + K_PENDING_VERIFIER, null)
        val error = uri.getQueryParameter("error")
        if (error != null) {
            val desc = uri.getQueryParameter("error_description") ?: error
            _state.value = if (error == "access_denied") AuthState.SignedOut else AuthState.Error(desc)
            return
        }
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        if (code == null || verifier == null || state != expectedState) {
            _state.value = AuthState.Error("Sign-in response was invalid. Please try again.")
            return
        }
        try {
            val clientId = prefs.current().clientId(spec.provider)
            tokenRequest(
                FormBody.Builder()
                    .add("client_id", clientId)
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", spec.redirectUri)
                    .add("code_verifier", verifier)
                    .build()
            )
            val name = runCatching { spec.fetchAccountName(http, accessToken()) }.getOrDefault(spec.provider.label)
            store.put(pfx + K_ACCOUNT, name)
            prefs.setAccountName(spec.provider, name)
            _state.value = AuthState.SignedIn(name)
        } catch (e: Exception) {
            _state.value = AuthState.Error(e.message ?: "Sign-in failed")
        }
    }

    fun cancelPendingSignIn() {
        if (_state.value is AuthState.SigningIn) _state.value = if (isSignedIn) AuthState.SignedIn(store.get(pfx + K_ACCOUNT) ?: spec.provider.label) else AuthState.SignedOut
    }

    /** A valid access token, refreshed when within two minutes of expiry. */
    suspend fun accessToken(): String = refreshMutex.withLock {
        val expiresAt = store.get(pfx + K_EXPIRES)?.toLongOrNull() ?: 0L
        val access = store.get(pfx + K_ACCESS)
        if (access != null && System.currentTimeMillis() < expiresAt - 120_000L) return@withLock access
        val refresh = store.get(pfx + K_REFRESH) ?: throw NotSignedInException(spec.provider)
        val clientId = prefs.current().clientId(spec.provider)
        try {
            tokenRequest(FormBody.Builder().add("client_id", clientId).add("grant_type", "refresh_token").add("refresh_token", refresh).build())
        } catch (e: AuthException) {
            if (e.message?.contains("invalid_grant") == true || e.message?.contains("interaction_required") == true) {
                clearTokens()
                _state.value = AuthState.SignedOut
                throw NotSignedInException(spec.provider)
            }
            throw e
        }
        store.get(pfx + K_ACCESS) ?: throw NotSignedInException(spec.provider)
    }

    /** Forces the next [accessToken] call to refresh (after a 401 from the API). */
    fun invalidateAccessToken() { store.put(pfx + K_EXPIRES, "0") }

    private suspend fun tokenRequest(body: FormBody) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(spec.tokenUrl).post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!resp.isSuccessful || obj == null) {
                val code = obj?.get("error")?.jsonPrimitive?.content
                val desc = obj?.get("error_description")?.jsonPrimitive?.content
                throw AuthException(listOfNotNull(code, desc?.lineSequence()?.firstOrNull()).joinToString(": ").ifBlank { "Token request failed (${resp.code})" })
            }
            val access = obj["access_token"]?.jsonPrimitive?.content ?: throw AuthException("No access token in response")
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
            store.put(pfx + K_ACCESS, access)
            store.put(pfx + K_EXPIRES, (System.currentTimeMillis() + expiresIn * 1000L).toString())
            obj["refresh_token"]?.jsonPrimitive?.content?.let { store.put(pfx + K_REFRESH, it) }
        }
    }

    suspend fun signOut() {
        clearTokens()
        prefs.setAccountName(spec.provider, null)
        _state.value = AuthState.SignedOut
    }

    private fun clearTokens() {
        listOf(K_ACCESS, K_REFRESH, K_EXPIRES, K_ACCOUNT).forEach { store.put(pfx + it, null) }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        private const val K_ACCESS = "access_token"
        private const val K_REFRESH = "refresh_token"
        private const val K_EXPIRES = "expires_at"
        private const val K_ACCOUNT = "account_name"
        private const val K_PENDING_VERIFIER = "pending_verifier"
        private const val K_PENDING_STATE = "pending_state"

        private val json = Json { ignoreUnknownKeys = true }

        private suspend fun getJson(http: OkHttpClient, url: String, token: String) = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                json.parseToJsonElement(t).jsonObject
            }
        }

        fun oneDrive(redirectUri: String) = OAuthSpec(
            provider = Provider.ONEDRIVE,
            authorizeUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
            tokenUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
            scopes = "openid profile offline_access User.Read Files.ReadWrite",
            redirectUri = redirectUri,
            extraAuthParams = mapOf("response_mode" to "query", "prompt" to "select_account"),
            fetchAccountName = { http, token ->
                val o = getJson(http, "https://graph.microsoft.com/v1.0/me?\$select=displayName,userPrincipalName,mail", token)
                val mail = o["mail"]?.jsonPrimitive?.content ?: o["userPrincipalName"]?.jsonPrimitive?.content
                listOfNotNull(o["displayName"]?.jsonPrimitive?.content, mail).distinct().joinToString(" · ").ifBlank { "Microsoft account" }
            }
        )

        fun googleDrive(redirectUri: String) = OAuthSpec(
            provider = Provider.GOOGLE_DRIVE,
            authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenUrl = "https://oauth2.googleapis.com/token",
            scopes = "openid email profile https://www.googleapis.com/auth/drive",
            redirectUri = redirectUri,
            extraAuthParams = mapOf("access_type" to "offline", "prompt" to "consent select_account"),
            fetchAccountName = { http, token ->
                val o = getJson(http, "https://www.googleapis.com/oauth2/v3/userinfo", token)
                listOfNotNull(o["name"]?.jsonPrimitive?.content, o["email"]?.jsonPrimitive?.content).distinct().joinToString(" · ").ifBlank { "Google account" }
            }
        )
    }
}
