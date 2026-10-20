package com.notesplus.app.editor

import com.notesplus.app.ink.InkDocument
import com.notesplus.app.ink.PageKey
import com.notesplus.app.ink.Stroke
import com.notesplus.app.ink.ToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoManagerTest {
    private fun stroke(id: String) = Stroke(id, ToolType.PEN, -1, 2f, 0L, floatArrayOf(0f, 0f, 0.5f))

    @Test
    fun `undo and redo an added stroke`() {
        val u = UndoManager()
        var doc = InkDocument().addStroke(0, stroke("a"))
        u.record(UndoManager.Op.Add(PageKey.Source(0), listOf(stroke("a"))))
        doc = u.undo(doc)
        assertEquals(0, doc.strokeCount())
        assertTrue("a" in doc.deleted)
        doc = u.redo(doc)
        assertEquals(1, doc.strokeCount())
        // Re-added under a fresh id so the tombstone for "a" still syncs correctly.
        assertFalse(doc.page(0)!!.strokes[0].id == "a")
    }

    @Test
    fun `undo an erase restores the strokes`() {
        val u = UndoManager()
        var doc = InkDocument().addStroke(0, stroke("a")).addStroke(0, stroke("b"))
        doc = doc.removeStrokes(0, listOf("a", "b"))
        u.record(UndoManager.Op.Erase(PageKey.Source(0), listOf(stroke("a"), stroke("b"))))
        doc = u.undo(doc)
        assertEquals(2, doc.strokeCount())
        doc = u.redo(doc)
        assertEquals(0, doc.strokeCount())
        assertFalse(u.canRedo)
        assertTrue(u.canUndo)
    }
}

class UndoManagerPageTest {
    @Test
    fun `undo and redo an inserted page`() {
        val u = UndoManager()
        val ins = com.notesplus.app.ink.PageInsert("p", after = 0, order = 1.0, w = 10f, h = 10f, t = 1L)
        var doc = InkDocument().addInsert(ins)
        u.record(UndoManager.Op.InsertPage(ins))
        doc = u.undo(doc)
        assertTrue(doc.orderedInserts().isEmpty())
        doc = u.redo(doc)
        assertEquals(1, doc.orderedInserts().size)
        assertFalse(doc.orderedInserts()[0].id == "p") // re-added under a fresh id
    }
}

class UndoManagerMoveTest {
    private fun stroke(id: String, x: Float) = com.notesplus.app.ink.Stroke(id, com.notesplus.app.ink.ToolType.PEN, -1, 2f, 0L, floatArrayOf(x, 0f, 0.5f))

    @Test
    fun `undo and redo a lasso move`() {
        val u = UndoManager()
        val key = PageKey.Source(0)
        val old = listOf(stroke("a", 0f))
        val moved = listOf(stroke("b", 10f))
        var doc = InkDocument().addStroke(key, moved[0])   // state after the move
        u.record(UndoManager.Op.Replace(key, old, moved))
        doc = u.undo(doc)
        assertEquals(listOf(0f), doc.strokesAt(key).map { it.x(0) })
        doc = u.redo(doc)
        assertEquals(listOf(10f), doc.strokesAt(key).map { it.x(0) })
        assertEquals(1, doc.strokeCount())
    }
}
