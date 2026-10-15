package com.notesplus.app.onedrive.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.notesplus.app.BuildConfig
import com.notesplus.app.data.Prefs
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

class NotSignedInException : IOException("Not signed in to OneDrive")
class AuthException(message: String) : IOException(message)

/**
 * Microsoft identity platform sign-in using the OAuth 2.0 authorization-code flow
 * with PKCE (no client secret), launched in a Chrome Custom Tab. Works for personal
 * Microsoft accounts and work/school accounts when the app registration allows both.
 */
class AuthManager(
    private val context: Context,
    private val prefs: Prefs,
    private val store: SecureStore,
    private val http: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()

    private val _state = MutableStateFlow<AuthState>(
        if (store.get(K_REFRESH) != null) AuthState.SignedIn(store.get(K_ACCOUNT) ?: "Microsoft account") else AuthState.SignedOut
    )
    val state: StateFlow<AuthState> = _state.asStateFlow()

    val isSignedIn: Boolean get() = store.get(K_REFRESH) != null

    /** Opens the Microsoft sign-in page. The result arrives via [handleRedirect]. */
    suspend fun startSignIn(activityContext: Context) {
        val clientId = prefs.current().effectiveClientId
        if (clientId.isBlank()) {
            _state.value = AuthState.Error("No client ID configured. Add your Azure app registration's client ID in Settings.")
            return
        }
        val verifier = randomUrlSafe(64)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val stateToken = randomUrlSafe(24)
        store.put(K_PENDING_VERIFIER, verifier)
        store.put(K_PENDING_STATE, stateToken)

        val uri = Uri.parse("$AUTHORITY/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", BuildConfig.OAUTH_REDIRECT_URI)
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", stateToken)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("prompt", "select_account")
            .build()
        _state.value = AuthState.SigningIn
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        tab.launchUrl(activityContext, uri)
    }

    /** Called by [AuthRedirectActivity] with the notesplus://auth?code=... URI. */
    suspend fun handleRedirect(uri: Uri) {
        val expectedState = store.get(K_PENDING_STATE)
        val verifier = store.get(K_PENDING_VERIFIER)
        store.put(K_PENDING_STATE, null); store.put(K_PENDING_VERIFIER, null)
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
            val clientId = prefs.current().effectiveClientId
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", BuildConfig.OAUTH_REDIRECT_URI)
                .add("code_verifier", verifier)
                .add("scope", SCOPES)
                .build()
            tokenRequest(body)
            val name = fetchAccountName()
            store.put(K_ACCOUNT, name)
            prefs.setAccountName(name)
            _state.value = AuthState.SignedIn(name)
        } catch (e: Exception) {
            _state.value = AuthState.Error(e.message ?: "Sign-in failed")
        }
    }

    fun cancelPendingSignIn() {
        if (_state.value is AuthState.SigningIn) _state.value = if (isSignedIn) AuthState.SignedIn(store.get(K_ACCOUNT) ?: "") else AuthState.SignedOut
    }

    /** Returns a valid access token, refreshing it when it is about to expire. */
    suspend fun accessToken(): String = refreshMutex.withLock {
        val expiresAt = store.get(K_EXPIRES)?.toLongOrNull() ?: 0L
        val access = store.get(K_ACCESS)
        if (access != null && System.currentTimeMillis() < expiresAt - 120_000L) return@withLock access
        val refresh = store.get(K_REFRESH) ?: throw NotSignedInException()
        val clientId = prefs.current().effectiveClientId
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("scope", SCOPES)
            .build()
        try {
            tokenRequest(body)
        } catch (e: AuthException) {
            // invalid_grant => refresh token revoked/expired: user must sign in again.
            if (e.message?.contains("invalid_grant") == true || e.message?.contains("interaction_required") == true) {
                clearTokens()
                _state.value = AuthState.SignedOut
                throw NotSignedInException()
            }
            throw e
        }
        store.get(K_ACCESS) ?: throw NotSignedInException()
    }

    private suspend fun tokenRequest(body: FormBody) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$AUTHORITY/token").post(body).build()
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
            store.put(K_ACCESS, access)
            store.put(K_EXPIRES, (System.currentTimeMillis() + expiresIn * 1000L).toString())
            obj["refresh_token"]?.jsonPrimitive?.content?.let { store.put(K_REFRESH, it) }
        }
    }

    private suspend fun fetchAccountName(): String = withContext(Dispatchers.IO) {
        val token = accessToken()
        val req = Request.Builder().url("https://graph.microsoft.com/v1.0/me?\$select=displayName,userPrincipalName,mail")
            .header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return@withContext "Microsoft account"
            val obj = json.parseToJsonElement(text).jsonObject
            val mail = obj["mail"]?.jsonPrimitive?.content ?: obj["userPrincipalName"]?.jsonPrimitive?.content
            val display = obj["displayName"]?.jsonPrimitive?.content
            listOfNotNull(display, mail).distinct().joinToString(" · ").ifBlank { "Microsoft account" }
        }
    }

    /** Forces the next [accessToken] call to use the refresh token. */
    fun invalidateAccessToken() { store.put(K_EXPIRES, "0") }

    suspend fun signOut() {
        clearTokens()
        prefs.clearAccountState()
        _state.value = AuthState.SignedOut
    }

    private fun clearTokens() {
        store.put(K_ACCESS, null); store.put(K_REFRESH, null); store.put(K_EXPIRES, null); store.put(K_ACCOUNT, null)
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        private const val AUTHORITY = "https://login.microsoftonline.com/common/oauth2/v2.0"
        private const val SCOPES = "openid profile offline_access User.Read Files.ReadWrite"
        private const val K_ACCESS = "access_token"
        private const val K_REFRESH = "refresh_token"
        private const val K_EXPIRES = "expires_at"
        private const val K_ACCOUNT = "account_name"
        private const val K_PENDING_VERIFIER = "pending_verifier"
        private const val K_PENDING_STATE = "pending_state"
    }
}
