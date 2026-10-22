package com.notesplus.app.data

import android.content.Context
import com.notesplus.app.data.db.NoteEntity

/** Where you were in a document: page, how far down it, horizontal position and zoom. */
data class ReadingPosition(val page: Int, val pageFraction: Float, val xFraction: Float, val zoom: Float)

/**
 * Remembers reading positions per drive file (provider + remote id), so they survive the
 * local cache being cleared or the note being re-downloaded.
 */
class PositionStore(context: Context) {
    private val prefs = context.getSharedPreferences("reading_positions", Context.MODE_PRIVATE)

    private fun key(n: NoteEntity) = "${n.provider}:${n.remoteId}"

    fun get(note: NoteEntity): ReadingPosition? {
        val v = prefs.getString(key(note), null) ?: return null
        val p = v.split('|')
        if (p.size < 4) return null
        return runCatching { ReadingPosition(p[0].toInt(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat()) }.getOrNull()
    }

    fun put(note: NoteEntity, pos: ReadingPosition) {
        prefs.edit().putString(key(note), "${pos.page}|${pos.pageFraction}|${pos.xFraction}|${pos.zoom}").apply()
    }
}
