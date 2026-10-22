package com.notesplus.app.cloud

import com.notesplus.app.data.db.Provider
import com.notesplus.app.util.Files
import kotlinx.coroutines.Dispatchers
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
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.time.Instant

/** Microsoft Graph implementation of [CloudProvider]. */
class OneDriveProvider(private val http: AuthedHttp) : CloudProvider {
    override val kind = Provider.ONEDRIVE
    override val supportsConditionalUpload = true
    private val json = Json { ignoreUnknownKeys = true }

    private fun fail(r: Reply): Nothing = fail(r.code, r.message, r.body)
    private fun fail(code: Int, message: String, body: String?): Nothing {
        val obj = body?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val err = obj?.get("error")?.jsonObject
        val gc = err?.get("code")?.jsonPrimitive?.content
        val msg = err?.get("message")?.jsonPrimitive?.content
        throw CloudException(code, gc, "OneDrive error $code${gc?.let { " ($it)" } ?: ""}: ${msg ?: message}")
    }

    private suspend fun getJson(url: String): JsonObject {
        val r = http.execute({ url(url).get() })
        if (!r.isSuccessful) fail(r)
        return json.parseToJsonElement(r.body ?: "{}").jsonObject
    }

    private fun parse(o: JsonObject): CloudFile {
        val parent = o["parentReference"]?.jsonObject
        val modified = o["lastModifiedDateTime"]?.jsonPrimitive?.content?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
        return CloudFile(
            id = o["id"]!!.jsonPrimitive.content,
            name = o["name"]?.jsonPrimitive?.content ?: "",
            isFolder = o.containsKey("folder"),
            size = o["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            modifiedAt = modified,
            version = o["cTag"]?.jsonPrimitive?.content ?: o["eTag"]?.jsonPrimitive?.content,
            eTag = o["eTag"]?.jsonPrimitive?.content,
            parentId = parent?.get("id")?.jsonPrimitive?.content,
            mimeType = o["file"]?.jsonObject?.get("mimeType")?.jsonPrimitive?.content,
            downloadUrl = o["@microsoft.graph.downloadUrl"]?.jsonPrimitive?.content
        )
    }

    private fun enc(segment: String) = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    override suspend fun listChildren(folderId: String?): List<CloudFile> {
        var url = if (folderId == null) "$BASE/me/drive/root/children?\$select=$SELECT&\$top=200"
        else "$BASE/me/drive/items/$folderId/children?\$select=$SELECT&\$top=200"
        val out = ArrayList<CloudFile>()
        while (true) {
            val obj = getJson(url)
            obj["value"]?.jsonArray?.forEach { out += parse(it.jsonObject) }
            url = obj["@odata.nextLink"]?.jsonPrimitive?.content ?: break
        }
        return out.filter { (it.isFolder && it.name != CloudProvider.INK_FOLDER) || it.isOpenable || it.name.endsWith(".ink.json", true) }
    }

    override suspend fun getFile(id: String): CloudFile? = try {
        parse(getJson("$BASE/me/drive/items/$id?\$select=$SELECT"))
    } catch (e: CloudException) { if (e.isNotFound) null else throw e }

    override suspend fun findChild(parentId: String, name: String): CloudFile? {
        val url = "$BASE/me/drive/items/$parentId:/${enc(name)}?\$select=$SELECT"
        return try { parse(getJson(url)) } catch (e: CloudException) { if (e.isNotFound) null else throw e }
    }

    override suspend fun ensureFolder(parentId: String, name: String): CloudFile {
        findChild(parentId, name)?.let { if (it.isFolder) return it }
        val body = buildJsonObject { put("name", name); putJsonObject("folder") {}; put("@microsoft.graph.conflictBehavior", "fail") }.toString().toRequestBody(JSON)
        val r = http.execute({ url("$BASE/me/drive/items/$parentId/children").post(body) })
        if (r.code == 409) return findChild(parentId, name) ?: fail(r)
        if (!r.isSuccessful) fail(r)
        return parse(json.parseToJsonElement(r.body!!).jsonObject)
    }

    override suspend fun delete(id: String) {
        val r = http.execute({ url("$BASE/me/drive/items/$id").delete() })
        if (!r.isSuccessful && r.code != 404) fail(r)
    }

    override suspend fun download(file: CloudFile, dest: File, onProgress: ((Long, Long) -> Unit)?) = withContext(Dispatchers.IO) {
        val resp = file.downloadUrl?.let { http.openPlain(it) } ?: http.openAuthed("$BASE/me/drive/items/${file.id}/content")
        resp.use { r ->
            if (!r.isSuccessful) fail(r.code, r.message, r.body?.string())
            val body = r.body ?: throw IOException("Empty download body")
            val total = body.contentLength().takeIf { it > 0 } ?: file.size
            body.byteStream().use { Files.writeAtomic(dest, it, total) { a, b -> onProgress?.invoke(a, b) } }
        }
    }

    /** Graph converts Office documents on the fly: /content?format=pdf (redirects to a download URL). */
    override suspend fun downloadAsPdf(file: CloudFile, dest: File, onProgress: ((Long, Long) -> Unit)?) = withContext(Dispatchers.IO) {
        if (file.isPdf) return@withContext download(file, dest, onProgress)
        http.openAuthed("$BASE/me/drive/items/${file.id}/content?format=pdf").use { r ->
            if (!r.isSuccessful) fail(r.code, r.message, r.body?.string())
            val body = r.body ?: throw IOException("Empty conversion body")
            body.byteStream().use { Files.writeAtomic(dest, it, body.contentLength()) { a, b -> onProgress?.invoke(a, b) } }
        }
    }

    override suspend fun downloadText(file: CloudFile): String = withContext(Dispatchers.IO) {
        val resp = file.downloadUrl?.let { http.openPlain(it) } ?: http.openAuthed("$BASE/me/drive/items/${file.id}/content")
        resp.use { r ->
            val text = r.body?.string()
            if (!r.isSuccessful) fail(r.code, r.message, text)
            text ?: ""
        }
    }

    override suspend fun uploadBytes(parentId: String, name: String, bytes: ByteArray, mimeType: String, existingId: String?, ifMatch: String?): CloudFile {
        val url = if (existingId != null) "$BASE/me/drive/items/$existingId/content"
        else "$BASE/me/drive/items/$parentId:/${enc(name)}:/content?@microsoft.graph.conflictBehavior=replace"
        val r = http.execute({
            url(url).put(bytes.toRequestBody(mimeType.toMediaType()))
            if (ifMatch != null) header("If-Match", ifMatch)
        })
        if (!r.isSuccessful) fail(r)
        return parse(json.parseToJsonElement(r.body!!).jsonObject)
    }

    override suspend fun upload(parentId: String, name: String, file: File, mimeType: String, existingId: String?, ifMatch: String?): CloudFile =
        if (file.length() < 4L * 1024 * 1024) uploadBytes(parentId, name, file.readBytes(), mimeType, existingId, ifMatch)
        else uploadLarge(parentId, name, file, existingId, ifMatch)

    /** Resumable upload session; chunks are multiples of 320 KiB as Graph requires. */
    private suspend fun uploadLarge(parentId: String, name: String, file: File, existingId: String?, ifMatch: String?): CloudFile = withContext(Dispatchers.IO) {
        val sessionUrl = if (existingId != null) "$BASE/me/drive/items/$existingId/createUploadSession"
        else "$BASE/me/drive/items/$parentId:/${enc(name)}:/createUploadSession"
        val body = buildJsonObject {
            putJsonObject("item") { put("@microsoft.graph.conflictBehavior", "replace"); put("name", name) }
        }.toString().toRequestBody(JSON)
        val uploadUrl = http.execute({
            url(sessionUrl).post(body)
            if (ifMatch != null) header("If-Match", ifMatch)
        }).let { r ->
            if (!r.isSuccessful) fail(r)
            json.parseToJsonElement(r.body!!).jsonObject["uploadUrl"]!!.jsonPrimitive.content
        }
        val total = file.length()
        val chunk = 10L * 320 * 1024
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
                    val resp = http.raw.newCall(
                        Request.Builder().url(uploadUrl).put(reqBody).header("Content-Range", "bytes $offset-${offset + len - 1}/$total").build()
                    ).execute()
                    val text = resp.body?.string(); val code = resp.code; resp.close()
                    when {
                        code == 202 -> { offset += len; advanced = true }
                        code == 200 || code == 201 -> return@withContext parse(json.parseToJsonElement(text!!).jsonObject)
                        (code == 429 || code >= 500) && attempt < 4 -> { attempt++; Thread.sleep((2L shl attempt).coerceAtMost(30) * 1000L) }
                        else -> throw CloudException(code, null, "Chunk upload failed ($code): ${text?.take(200)}")
                    }
                }
            }
        }
        throw IOException("Upload session ended without a final item")
    }

    companion object {
        const val BASE = "https://graph.microsoft.com/v1.0"
        private const val SELECT = "id,name,size,eTag,cTag,lastModifiedDateTime,folder,file,parentReference,@microsoft.graph.downloadUrl"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
    }
}
