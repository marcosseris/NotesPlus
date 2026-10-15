package com.notesplus.app.onedrive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.time.Instant

data class DriveItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long,
    val eTag: String?,
    val cTag: String?,
    val lastModified: Long,
    val parentId: String?,
    val parentPath: String?,
    val downloadUrl: String?,
    val deleted: Boolean
)

data class DeltaResult(val items: List<DriveItem>, val deltaLink: String)

class GraphException(val code: Int, val graphCode: String?, message: String) : IOException(message) {
    val isPreconditionFailed get() = code == 412
    val isNotFound get() = code == 404
    val isConflict get() = code == 409
    val isAuth get() = code == 401
}

/**
 * Minimal Microsoft Graph client for the OneDrive endpoints the sync engine needs.
 * JSON is parsed dynamically to keep the surface small.
 */
class GraphClient(
    private val http: OkHttpClient,
    private val tokenProvider: suspend () -> String,
    /** Called when Graph rejects the token (401) so the next [tokenProvider] call refreshes it. */
    private val onUnauthorized: () -> Unit = {}
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------ helpers

    private suspend fun execute(build: Request.Builder.() -> Unit, attempt: Int = 0): Response = withContext(Dispatchers.IO) {
        val token = tokenProvider()
        val req = Request.Builder().apply(build).header("Authorization", "Bearer $token").build()
        val resp = http.newCall(req).execute()
        if (resp.code == 401 && attempt == 0) {
            resp.close()
            onUnauthorized()
            return@withContext execute(build, attempt + 1)
        }
        if ((resp.code == 429 || resp.code == 503 || resp.code == 504) && attempt < 4) {
            val retryAfter = resp.header("Retry-After")?.toLongOrNull() ?: (2L shl attempt)
            resp.close()
            delay(retryAfter.coerceIn(1, 60) * 1000L)
            return@withContext execute(build, attempt + 1)
        }
        resp
    }

    private fun fail(resp: Response, body: String?): Nothing {
        val obj = body?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val err = obj?.get("error")?.jsonObject
        val code = err?.get("code")?.jsonPrimitive?.content
        val msg = err?.get("message")?.jsonPrimitive?.content
        throw GraphException(resp.code, code, "OneDrive error ${resp.code}${code?.let { " ($it)" } ?: ""}: ${msg ?: resp.message}")
    }

    private suspend fun getJson(url: String): JsonObject {
        execute({ url(url).get() }).use { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) fail(resp, text)
            return json.parseToJsonElement(text ?: "{}").jsonObject
        }
    }

    private fun parseItem(o: JsonObject): DriveItem {
        val parent = o["parentReference"]?.jsonObject
        val modified = o["lastModifiedDateTime"]?.jsonPrimitive?.content?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
        return DriveItem(
            id = o["id"]!!.jsonPrimitive.content,
            name = o["name"]?.jsonPrimitive?.content ?: "",
            isFolder = o.containsKey("folder"),
            size = o["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            eTag = o["eTag"]?.jsonPrimitive?.content,
            cTag = o["cTag"]?.jsonPrimitive?.content,
            lastModified = modified,
            parentId = parent?.get("id")?.jsonPrimitive?.content,
            parentPath = parent?.get("path")?.jsonPrimitive?.content,
            downloadUrl = o["@microsoft.graph.downloadUrl"]?.jsonPrimitive?.content,
            deleted = o.containsKey("deleted")
        )
    }

    private fun enc(segment: String) = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /** Encodes a "/A/B C" style path for the ":/path:" addressing syntax. */
    private fun encPath(path: String) = path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { enc(it) }

    // ------------------------------------------------------------------ metadata

    suspend fun item(id: String): DriveItem = parseItem(getJson("$BASE/me/drive/items/$id?\$select=$SELECT"))

    suspend fun itemByPath(path: String): DriveItem? {
        val p = encPath(path)
        val url = if (p.isEmpty()) "$BASE/me/drive/root?\$select=$SELECT" else "$BASE/me/drive/root:/$p?\$select=$SELECT"
        return try { parseItem(getJson(url)) } catch (e: GraphException) { if (e.isNotFound) null else throw e }
    }

    suspend fun children(folderId: String?): List<DriveItem> {
        var url = if (folderId == null) "$BASE/me/drive/root/children?\$select=$SELECT&\$top=200"
        else "$BASE/me/drive/items/$folderId/children?\$select=$SELECT&\$top=200"
        val out = ArrayList<DriveItem>()
        while (true) {
            val obj = getJson(url)
            obj["value"]?.jsonArray?.forEach { out += parseItem(it.jsonObject) }
            url = obj["@odata.nextLink"]?.jsonPrimitive?.content ?: break
        }
        return out
    }

    suspend fun childByName(folderId: String, name: String): DriveItem? =
        children(folderId).firstOrNull { it.name.equals(name, ignoreCase = true) }

    suspend fun createFolder(parentId: String?, name: String): DriveItem {
        val url = if (parentId == null) "$BASE/me/drive/root/children" else "$BASE/me/drive/items/$parentId/children"
        val body = buildJsonObject {
            put("name", name)
            putJsonObject("folder") {}
            put("@microsoft.graph.conflictBehavior", "fail")
        }.toString().toRequestBody(JSON)
        execute({ url(url).post(body) }).use { resp ->
            val text = resp.body?.string()
            if (resp.code == 409) {
                // Already exists: look it up.
                return (if (parentId == null) children(null) else children(parentId)).first { it.name.equals(name, true) && it.isFolder }
            }
            if (!resp.isSuccessful) fail(resp, text)
            return parseItem(json.parseToJsonElement(text!!).jsonObject)
        }
    }

    /** Ensures each segment of "/A/B" exists and returns the final folder. */
    suspend fun ensureFolderPath(path: String): DriveItem {
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        var current: DriveItem = itemByPath("/") ?: throw GraphException(404, null, "Drive root not found")
        if (segments.isEmpty()) return current
        for (seg in segments) {
            val existing = childByName(current.id, seg)
            current = when {
                existing == null -> createFolder(current.id, seg)
                existing.isFolder -> existing
                else -> throw GraphException(409, "nameAlreadyExists", "'$seg' exists on OneDrive but is a file, not a folder")
            }
        }
        return current
    }

    suspend fun ensureSubfolder(parentId: String, relativeDir: String): String {
        if (relativeDir.isEmpty()) return parentId
        var current = parentId
        for (seg in relativeDir.split('/').filter { it.isNotBlank() }) {
            val existing = childByName(current, seg)
            current = (if (existing != null && existing.isFolder) existing else createFolder(current, seg)).id
        }
        return current
    }

    suspend fun delta(folderId: String, deltaLink: String?): DeltaResult {
        var url = deltaLink ?: "$BASE/me/drive/items/$folderId/delta?\$select=$SELECT"
        val out = ArrayList<DriveItem>()
        while (true) {
            val obj = try { getJson(url) } catch (e: GraphException) {
                // 410 Gone = delta token expired: restart from scratch.
                if (e.code == 410 && deltaLink != null) return delta(folderId, null) else throw e
            }
            obj["value"]?.jsonArray?.forEach { out += parseItem(it.jsonObject) }
            val next = obj["@odata.nextLink"]?.jsonPrimitive?.content
            if (next != null) { url = next; continue }
            val dl = obj["@odata.deltaLink"]?.jsonPrimitive?.content ?: throw GraphException(500, null, "Delta response had no deltaLink")
            return DeltaResult(out, dl)
        }
    }

    suspend fun delete(itemId: String) {
        execute({ url("$BASE/me/drive/items/$itemId").delete() }).use { resp ->
            if (!resp.isSuccessful && resp.code != 404) fail(resp, resp.body?.string())
        }
    }

    suspend fun rename(itemId: String, newName: String): DriveItem {
        val body = buildJsonObject { put("name", newName) }.toString().toRequestBody(JSON)
        execute({ url("$BASE/me/drive/items/$itemId").patch(body) }).use { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) fail(resp, text)
            return parseItem(json.parseToJsonElement(text!!).jsonObject)
        }
    }

    // ------------------------------------------------------------------ content

    suspend fun download(item: DriveItem, dest: File) = withContext(Dispatchers.IO) {
        val url = item.downloadUrl
        val resp = if (url != null) {
            // Pre-authenticated URL: no bearer token (and it must not leak to the CDN host).
            http.newCall(Request.Builder().url(url).get().build()).execute()
        } else {
            execute({ url("$BASE/me/drive/items/${item.id}/content").get() })
        }
        resp.use { r ->
            if (!r.isSuccessful) fail(r, r.body?.string())
            val body = r.body ?: throw IOException("Empty download body")
            body.byteStream().use { com.notesplus.app.util.Files.writeAtomic(dest, it) }
        }
    }

    suspend fun downloadText(item: DriveItem): String = withContext(Dispatchers.IO) {
        val url = item.downloadUrl
        val resp = if (url != null) http.newCall(Request.Builder().url(url).get().build()).execute()
        else execute({ url("$BASE/me/drive/items/${item.id}/content").get() })
        resp.use { r ->
            val text = r.body?.string()
            if (!r.isSuccessful) fail(r, text)
            text ?: ""
        }
    }

    /**
     * Uploads [bytes] as a new child of [parentId] named [name] (or replaces [existingId]).
     * [ifMatch] makes the replace conditional on the remote eTag => 412 on conflict.
     */
    suspend fun uploadSmall(parentId: String, name: String, bytes: ByteArray, existingId: String?, ifMatch: String?): DriveItem {
        val url = if (existingId != null) "$BASE/me/drive/items/$existingId/content"
        else "$BASE/me/drive/items/$parentId:/${enc(name)}:/content?@microsoft.graph.conflictBehavior=replace"
        execute({
            url(url).put(bytes.toRequestBody(OCTET))
            if (ifMatch != null) header("If-Match", ifMatch)
        }).use { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) fail(resp, text)
            return parseItem(json.parseToJsonElement(text!!).jsonObject)
        }
    }

    /** Resumable upload for large files (chunks are multiples of 320 KiB as Graph requires). */
    suspend fun uploadLarge(parentId: String, name: String, file: File, existingId: String?): DriveItem = withContext(Dispatchers.IO) {
        val sessionUrl = if (existingId != null) "$BASE/me/drive/items/$existingId/createUploadSession"
        else "$BASE/me/drive/items/$parentId:/${enc(name)}:/createUploadSession"
        val body = buildJsonObject {
            putJsonObject("item") {
                put("@microsoft.graph.conflictBehavior", "replace")
                put("name", name)
            }
        }.toString().toRequestBody(JSON)
        val uploadUrl = execute({ url(sessionUrl).post(body) }).use { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) fail(resp, text)
            json.parseToJsonElement(text!!).jsonObject["uploadUrl"]!!.jsonPrimitive.content
        }
        val total = file.length()
        val chunk = 10L * 320 * 1024 // 3.2 MiB
        var offset = 0L
        RandomAccessFile(file, "r").use { raf ->
            while (offset < total) {
                val len = minOf(chunk, total - offset).toInt()
                val buf = ByteArray(len)
                raf.seek(offset); raf.readFully(buf)
                val reqBody = object : RequestBody() {
                    override fun contentType() = OCTET
                    override fun contentLength() = len.toLong()
                    override fun writeTo(sink: BufferedSink) { sink.write(buf) }
                }
                var attempt = 0
                var advanced = false
                while (!advanced) {
                    val resp = http.newCall(
                        Request.Builder().url(uploadUrl).put(reqBody)
                            .header("Content-Range", "bytes $offset-${offset + len - 1}/$total").build()
                    ).execute()
                    val text = resp.body?.string()
                    val code = resp.code
                    resp.close()
                    when {
                        code == 202 -> { offset += len; advanced = true }
                        code == 200 || code == 201 -> return@withContext parseItem(json.parseToJsonElement(text!!).jsonObject)
                        (code == 429 || code >= 500) && attempt < 4 -> {
                            attempt++
                            Thread.sleep((2L shl attempt).coerceAtMost(30) * 1000L)
                        }
                        else -> throw GraphException(code, null, "Chunk upload failed ($code): ${text?.take(200)}")
                    }
                }
            }
        }
        throw IOException("Upload session ended without a final item")
    }

    suspend fun upload(parentId: String, name: String, file: File, existingId: String?, ifMatch: String?): DriveItem =
        if (file.length() < 4L * 1024 * 1024) uploadSmall(parentId, name, file.readBytes(), existingId, ifMatch)
        else uploadLarge(parentId, name, file, existingId)

    companion object {
        const val BASE = "https://graph.microsoft.com/v1.0"
        private const val SELECT = "id,name,size,eTag,cTag,lastModifiedDateTime,folder,file,parentReference,deleted,@microsoft.graph.downloadUrl"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
    }
}
