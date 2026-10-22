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

    /** Like [writeAtomic] but reports (bytesSoFar, totalBytes or -1) while copying. */
    fun writeAtomic(target: File, input: InputStream, total: Long, onProgress: (Long, Long) -> Unit) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            val buf = ByteArray(256 * 1024)
            var done = 0L
            var lastReport = 0L
            while (true) {
                val n = input.read(buf); if (n < 0) break
                out.write(buf, 0, n); done += n
                if (done - lastReport >= 128 * 1024) { onProgress(done, total); lastReport = done }
            }
            onProgress(done, total)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("Could not replace ${target.name}")
        }
    }

    /** True when any of [markers] occurs in [file]; streams the file, never loads it whole. */
    fun containsAny(file: File, markers: List<String>): Boolean {
        val needles = markers.map { it.toByteArray(Charsets.ISO_8859_1) }
        val overlap = needles.maxOf { it.size } - 1
        val buf = ByteArray(1024 * 1024 + overlap)
        var carry = 0
        file.inputStream().use { inp ->
            while (true) {
                val n = inp.read(buf, carry, buf.size - carry)
                if (n < 0) return false
                val len = carry + n
                for (needle in needles) if (indexOf(buf, len, needle) >= 0) return true
                carry = minOf(overlap, len)
                System.arraycopy(buf, len - carry, buf, 0, carry)
            }
        }
    }

    private fun indexOf(hay: ByteArray, len: Int, needle: ByteArray): Int {
        val first = needle[0]
        var i = 0
        val last = len - needle.size
        while (i <= last) {
            if (hay[i] == first) {
                var j = 1
                while (j < needle.size && hay[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
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
