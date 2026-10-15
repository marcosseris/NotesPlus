package com.notesplus.app.util

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object Files {
    /** Writes atomically: to a temp file in the same directory, fsync, then rename. */
    fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("Could not replace ${target.name}")
        }
    }

    fun writeAtomic(target: File, input: InputStream) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            input.copyTo(out, 256 * 1024)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("Could not replace ${target.name}")
        }
    }

    /** Strips characters OneDrive does not allow in file names. */
    fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|#%]"), "_").trim().trimEnd('.')
        return cleaned.ifBlank { "Untitled" }.take(200)
    }

    /** Normalises a relative directory: forward slashes, no leading/trailing slash. */
    fun sanitizeDir(dir: String): String = dir.replace('\\', '/').trim('/')

    fun stripExtension(name: String, vararg exts: String): String {
        for (e in exts) if (name.endsWith(e, ignoreCase = true)) return name.dropLast(e.length)
        return name
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    }
}
