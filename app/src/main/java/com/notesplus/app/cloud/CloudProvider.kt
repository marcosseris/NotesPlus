package com.notesplus.app.cloud

import com.notesplus.app.data.db.Provider
import java.io.File
import java.io.IOException

/** A file or folder on a cloud drive, in provider-neutral form. */
data class CloudFile(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long,
    val modifiedAt: Long,
    /** Content version marker (OneDrive cTag / Drive md5+version); changes whenever the bytes change. */
    val version: String?,
    /** Opaque per-provider tag used for conditional writes (OneDrive eTag). */
    val eTag: String? = null,
    val parentId: String? = null,
    val mimeType: String? = null,
    val downloadUrl: String? = null,
    /** Virtual entries (e.g. "Shared with me") cannot hold uploads. */
    val isVirtual: Boolean = false,
    val sortKey: Int = 0
) {
    val isPdf: Boolean get() = !isFolder && (name.endsWith(".pdf", ignoreCase = true) || mimeType == "application/pdf")

    /** Office / Google documents the drive can convert to PDF for us. */
    val isConvertible: Boolean get() = !isFolder && !isPdf && (CONVERTIBLE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } || mimeType in GOOGLE_NATIVE_MIMES)

    /** Anything the browser offers to open. */
    val isOpenable: Boolean get() = isPdf || isConvertible

    companion object {
        val CONVERTIBLE_EXTENSIONS = listOf(".pptx", ".ppt", ".ppsx", ".docx", ".doc", ".odp", ".odt")
        val GOOGLE_NATIVE_MIMES = setOf("application/vnd.google-apps.presentation", "application/vnd.google-apps.document")
    }
}

class CloudException(val code: Int, val providerCode: String?, message: String) : IOException(message) {
    val isPreconditionFailed get() = code == 412
    val isNotFound get() = code == 404
    val isAuth get() = code == 401 || code == 403
}

/**
 * The operations the browser and the sync engine need from a drive. Every call runs on the
 * IO dispatcher internally, so callers may invoke them from any coroutine.
 */
interface CloudProvider {
    val kind: Provider

    /** Children of [folderId]; null lists the provider's top level (which may contain virtual roots). */
    suspend fun listChildren(folderId: String?): List<CloudFile>

    suspend fun getFile(id: String): CloudFile?

    /** Finds a direct child by exact name (case-insensitive), or null. */
    suspend fun findChild(parentId: String, name: String): CloudFile?

    suspend fun download(file: CloudFile, dest: File, onProgress: ((Long, Long) -> Unit)? = null)
    suspend fun downloadText(file: CloudFile): String

    /**
     * Fetches [file] as a PDF: a plain download for PDFs, a server-side conversion for
     * PowerPoint / Word / Google Slides / Google Docs.
     */
    suspend fun downloadAsPdf(file: CloudFile, dest: File, onProgress: ((Long, Long) -> Unit)? = null)

    /**
     * Creates or replaces a file. When [existingId] is given the file is updated in place;
     * [ifMatch] (when the provider supports it) makes the update conditional and throws a
     * 412 [CloudException] if the remote changed. Returns the new metadata.
     */
    suspend fun upload(parentId: String, name: String, file: File, mimeType: String, existingId: String?, ifMatch: String?): CloudFile

    suspend fun uploadBytes(parentId: String, name: String, bytes: ByteArray, mimeType: String, existingId: String?, ifMatch: String?): CloudFile

    /** Finds or creates a direct subfolder. */
    suspend fun ensureFolder(parentId: String, name: String): CloudFile

    /** Deletes a file (no error when it is already gone). */
    suspend fun delete(id: String)

    /** True when conditional uploads ([CloudFile.eTag]) are honoured. */
    val supportsConditionalUpload: Boolean

    companion object {
        /** Hidden folder, next to the documents, that holds the annotation sidecars. */
        const val INK_FOLDER = ".ink"
    }
}
