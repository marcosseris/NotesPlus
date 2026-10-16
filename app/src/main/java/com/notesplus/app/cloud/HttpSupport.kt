package com.notesplus.app.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** A fully-read HTTP response: safe to inspect on any thread. */
class Reply(val code: Int, val message: String, val body: String?) {
    val isSuccessful get() = code in 200..299
}

/**
 * Shared request plumbing: adds the bearer token, retries on throttling, refreshes once on
 * 401, and reads the body on the IO dispatcher so callers never touch a socket on the main
 * thread (which throws NetworkOnMainThreadException).
 */
class AuthedHttp(
    private val http: OkHttpClient,
    private val tokenProvider: suspend () -> String,
    private val onUnauthorized: () -> Unit
) {
    suspend fun execute(build: Request.Builder.() -> Unit, attempt: Int = 0): Reply = withContext(Dispatchers.IO) {
        val token = tokenProvider()
        val req = Request.Builder().apply(build).header("Authorization", "Bearer $token").build()
        val resp = http.newCall(req).execute()
        if (resp.code == 401 && attempt == 0) {
            resp.close(); onUnauthorized()
            return@withContext execute(build, attempt + 1)
        }
        if ((resp.code == 429 || resp.code == 503 || resp.code == 504) && attempt < 4) {
            val retryAfter = resp.header("Retry-After")?.toLongOrNull() ?: (2L shl attempt)
            resp.close()
            delay(retryAfter.coerceIn(1, 60) * 1000L)
            return@withContext execute(build, attempt + 1)
        }
        resp.use { Reply(it.code, it.message, it.body?.string()) }
    }

    /** Opens a streaming response with the bearer token. IO thread; caller closes it. */
    suspend fun openAuthed(url: String): Response = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).get().header("Authorization", "Bearer ${tokenProvider()}").build()).execute()
    }

    /** Opens a streaming response without credentials (pre-authenticated download URLs). */
    suspend fun openPlain(url: String): Response = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).get().build()).execute()
    }

    val raw: OkHttpClient get() = http
}
