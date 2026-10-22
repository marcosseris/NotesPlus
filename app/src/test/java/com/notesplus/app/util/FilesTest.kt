package com.notesplus.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilesTest {
    private fun fileWith(bytes: ByteArray) = File.createTempFile("np", ".pdf").apply { writeBytes(bytes); deleteOnExit() }

    @Test
    fun `finds a marker that straddles the read buffer boundary`() {
        val marker = "/NotesPlusLayer"
        val pad = ByteArray(1024 * 1024 - 5) { 'x'.code.toByte() }
        val f = fileWith(pad + marker.toByteArray() + ByteArray(100))
        assertTrue(Files.containsAny(f, listOf(marker, "/NotesPlusInsert")))
    }

    @Test
    fun `reports absence in a large file`() {
        val f = fileWith(ByteArray(3 * 1024 * 1024) { (it % 251).toByte() })
        assertFalse(Files.containsAny(f, listOf("/NotesPlusLayer", "/NotesPlusInsert")))
    }

    @Test
    fun `finds the second marker`() {
        val f = fileWith("%PDF-1.4 ... /NotesPlusInsert (abc) ...".toByteArray())
        assertTrue(Files.containsAny(f, listOf("/NotesPlusLayer", "/NotesPlusInsert")))
    }
}
