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
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant

/**
 * Google Drive v3 implementation of [CloudProvider]. The top level shows three virtual
 * roots: My Drive, Shared with me, and Shared drives.
 */
class GoogleDriveProvider(private val http: AuthedHttp) : CloudProvider {
    override val kind = Provider.GOOGLE_DRIVE
    /** Drive v3 has no If-Match; the sync engine compares versions before uploading instead. */
    override val supportsConditionalUpload = false
    private val json = Json { ignoreUnknownKeys = true }

    private fun fail(r: Reply): Nothing = fail(r.code, r.message, r.body)
    private fun fail(code: Int, message: String, body: String?): Nothing {
        val obj = body?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val err = obj?.get("error")?.jsonObject
        val msg = err?.get("message")?.jsonPrimitive?.content
        val reason = err?.get("errors")?.jsonArray?.firstOrNull()?.jsonObject?.get("reason")?.jsonPrimitive?.content
        throw CloudException(code, reason, "Google Drive error $code${reason?.let { " ($it)" } ?: ""}: ${msg ?: message}")
    }

    private suspend fun getJson(url: String): JsonObject {
        val r = http.execute({ url(url).get() })
        if (!r.isSuccessful) fail(r)
        return json.parseToJsonElement(r.body ?: "{}").jsonObject
    }

    private fun parse(o: JsonObject): CloudFile {
        val mime = o["mimeType"]?.jsonPrimitive?.content
        val isFolder = mime == FOLDER_MIME
        val modified = o["modifiedTime"]?.jsonPrimitive?.content?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
        val md5 = o["md5Checksum"]?.jsonPrimitive?.content
        val ver = o["version"]?.jsonPrimitive?.content
        return CloudFile(
            id = o["id"]!!.jsonPrimitive.content,
            name = o["name"]?.jsonPrimitive?.content ?: "",
            isFolder = isFolder,
            size = o["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            modifiedAt = modified,
            version = md5 ?: ver,
            eTag = null,
            parentId = o["parents"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content,
            mimeType = mime
        )
    }

    private fun q(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    private suspend fun query(queryExpr: String, extra: String = ""): List<CloudFile> {
        val out = ArrayList<CloudFile>()
        var pageToken: String? = null
        do {
            val url = StringBuilder("$BASE/files?q=${q(queryExpr)}&fields=${q("nextPageToken,files($FIELDS)")}&pageSize=200&orderBy=${q("folder,name")}&supportsAllDrives=true&includeItemsFromAllDrives=true$extra")
            if (pageToken != null) url.append("&pageToken=").append(q(pageToken))
            val obj = getJson(url.toString())
            obj["files"]?.jsonArray?.forEach { out += parse(it.jsonObject) }
            pageToken = obj["nextPageToken"]?.jsonPrimitive?.content
        } while (pageToken != null)
        return out.filter { it.isFolder || it.isOpenable || it.name.endsWith(".ink.json", true) }
    }

    /** Listing for the browser: hides the .ink sidecar folders ([findChild] must still see them). */
    override suspend fun listChildren(folderId: String?): List<CloudFile> =
        rawChildren(folderId).filterNot { it.isFolder && it.name == CloudProvider.INK_FOLDER }

    private suspend fun rawChildren(folderId: String?): List<CloudFile> = when (folderId) {
        null -> listOf(
            CloudFile(ROOT_MY_DRIVE, "My Drive", true, 0, 0, null, isVirtual = true, sortKey = -3),
            CloudFile(ROOT_SHARED, "Shared with me", true, 0, 0, null, isVirtual = true, sortKey = -2),
            CloudFile(ROOT_DRIVES, "Shared drives", true, 0, 0, null, isVirtual = true, sortKey = -1)
        )
        ROOT_MY_DRIVE -> query("'root' in parents and trashed = false")
        ROOT_SHARED -> query("sharedWithMe = true and trashed = false")
        ROOT_DRIVES -> {
            val out = ArrayList<CloudFile>()
            var pageToken: String? = null
            do {
                val url = "$BASE/drives?pageSize=100&fields=${q("nextPageToken,drives(id,name)")}" + (pageToken?.let { "&pageToken=${q(it)}" } ?: "")
                val obj = getJson(url)
                obj["drives"]?.jsonArray?.forEach { d ->
                    val o = d.jsonObject
                    out += CloudFile(o["id"]!!.jsonPrimitive.content, o["name"]?.jsonPrimitive?.content ?: "Shared drive", true, 0, 0, null, parentId = ROOT_DRIVES)
                }
                pageToken = obj["nextPageToken"]?.jsonPrimitive?.content
            } while (pageToken != null)
            out
        }
        else -> query("'$folderId' in parents and trashed = false")
    }

    override suspend fun getFile(id: String): CloudFile? = try {
        parse(getJson("$BASE/files/$id?fields=${q(FIELDS)}&supportsAllDrives=true"))
    } catch (e: CloudException) { if (e.isNotFound) null else throw e }

    override suspend fun findChild(parentId: String, name: String): CloudFile? {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        val parentExpr = if (parentId == ROOT_MY_DRIVE) "'root' in parents" else "'$parentId' in parents"
        return query("$parentExpr and name = '$escaped' and trashed = false").firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    override suspend fun ensureFolder(parentId: String, name: String): CloudFile {
        findChild(parentId, name)?.let { if (it.isFolder) return it }
        val parent = if (parentId == ROOT_MY_DRIVE) "root" else parentId
        val meta = buildJsonObject {
            put("name", name); put("mimeType", FOLDER_MIME)
            putJsonArray("parents") { add(kotlinx.serialization.json.JsonPrimitive(parent)) }
        }.toString()
        val r = http.execute({ url("$BASE/files?supportsAllDrives=true&fields=${q(FIELDS)}").post(meta.toRequestBody("application/json; charset=UTF-8".toMediaType())) })
        if (!r.isSuccessful) fail(r)
        return parse(json.parseToJsonElement(r.body!!).jsonObject)
    }

    override suspend fun delete(id: String) {
        val r = http.execute({ url("$BASE/files/$id?supportsAllDrives=true").delete() })
        if (!r.isSuccessful && r.code != 404) fail(r)
    }

    override suspend fun download(file: CloudFile, dest: File, onProgress: ((Long, Long) -> Unit)?) = withContext(Dispatchers.IO) {
        http.openAuthed("$BASE/files/${file.id}?alt=media&supportsAllDrives=true").use { r ->
            if (!r.isSuccessful) fail(r.code, r.message, r.body?.string())
            val body = r.body ?: throw IOException("Empty download body")
            val total = body.contentLength().takeIf { it > 0 } ?: file.size
            body.byteStream().use { Files.writeAtomic(dest, it, total) { a, b -> onProgress?.invoke(a, b) } }
        }
    }

    override suspend fun downloadAsPdf(file: CloudFile, dest: File, onProgress: ((Long, Long) -> Unit)?) = withContext(Dispatchers.IO) {
        if (file.isPdf) return@withContext download(file, dest, onProgress)
        if (file.mimeType in CloudFile.GOOGLE_NATIVE_MIMES) return@withContext exportPdf(file.id, dest)
        // Office file: Drive converts it when copied into the matching Google format.
        val target = if (file.name.lowercase().let { it.endsWith(".docx") || it.endsWith(".doc") || it.endsWith(".odt") }) "application/vnd.google-apps.document"
        else "application/vnd.google-apps.presentation"
        val meta = buildJsonObject { put("name", "NotesPlus temp – ${file.name}"); put("mimeType", target) }.toString()
        val r = http.execute({ url("$BASE/files/${file.id}/copy?supportsAllDrives=true&fields=id").post(meta.toRequestBody("application/json; charset=UTF-8".toMediaType())) })
        if (!r.isSuccessful) fail(r)
        val copyId = json.parseToJsonElement(r.body!!).jsonObject["id"]!!.jsonPrimitive.content
        try { exportPdf(copyId, dest) } finally {
            runCatching { http.execute({ url("$BASE/files/$copyId?supportsAllDrives=true").delete() }) }
        }
    }

    /** Export links (docs.google.com) have no size cap; files.export is the fallback. */
    private suspend fun exportPdf(id: String, dest: File) {
        val meta = getJson("$BASE/files/$id?fields=exportLinks&supportsAllDrives=true")
        val link = meta["exportLinks"]?.jsonObject?.get("application/pdf")?.jsonPrimitive?.content
        val url = link ?: "$BASE/files/$id/export?mimeType=application%2Fpdf"
        http.openAuthed(url).use { r ->
            if (!r.isSuccessful) fail(r.code, r.message, r.body?.string())
            val body = r.body ?: throw IOException("Empty export body")
            body.byteStream().use { Files.writeAtomic(dest, it) }
        }
    }

    override suspend fun downloadText(file: CloudFile): String = withContext(Dispatchers.IO) {
        http.openAuthed("$BASE/files/${file.id}?alt=media&supportsAllDrives=true").use { r ->
            val text = r.body?.string()
            if (!r.isSuccessful) fail(r.code, r.message, text)
            text ?: ""
        }
    }

    override suspend fun uploadBytes(parentId: String, name: String, bytes: ByteArray, mimeType: String, existingId: String?, ifMatch: String?): CloudFile =
        uploadBody(parentId, name, bytes.toRequestBody(mimeType.toMediaType()), existingId)

    override suspend fun upload(parentId: String, name: String, file: File, mimeType: String, existingId: String?, ifMatch: String?): CloudFile =
        uploadBody(parentId, name, file.asRequestBody(mimeType.toMediaType()), existingId)

    /** Multipart upload (metadata + content) for new files, media update for existing ones. */
    private suspend fun uploadBody(parentId: String, name: String, content: okhttp3.RequestBody, existingId: String?): CloudFile {
        val r = if (existingId != null) {
            http.execute({ url("$UPLOAD/files/$existingId?uploadType=media&supportsAllDrives=true&fields=${q(FIELDS)}").patch(content) })
        } else {
            val parent = if (parentId == ROOT_MY_DRIVE) "root" else parentId
            val meta = buildJsonObject { put("name", name); putJsonArray("parents") { add(kotlinx.serialization.json.JsonPrimitive(parent)) } }.toString()
            val multipart = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(meta.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .addPart(content)
                .build()
            http.execute({ url("$UPLOAD/files?uploadType=multipart&supportsAllDrives=true&fields=${q(FIELDS)}").post(multipart) })
        }
        if (!r.isSuccessful) fail(r)
        return parse(json.parseToJsonElement(r.body!!).jsonObject)
    }

    companion object {
        const val BASE = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val ROOT_MY_DRIVE = "root"
        const val ROOT_SHARED = "virtual:shared-with-me"
        const val ROOT_DRIVES = "virtual:shared-drives"
        private const val FIELDS = "id,name,mimeType,size,modifiedTime,md5Checksum,version,parents"
    }
}
