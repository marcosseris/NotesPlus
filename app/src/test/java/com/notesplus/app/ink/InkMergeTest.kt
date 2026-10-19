package com.notesplus.app.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkMergeTest {
    private fun stroke(id: String, t: Long = 0L) = Stroke(id, ToolType.PEN, -16777216, 2f, t, floatArrayOf(0f, 0f, 0.5f, 10f, 10f, 0.5f))

    @Test
    fun `merge keeps strokes from both sides`() {
        val a = InkDocument().addStroke(0, stroke("a", 1), now = 100)
        val b = InkDocument().addStroke(0, stroke("b", 2), now = 200)
        val m = InkMerge.merge(a, b)
        assertEquals(setOf("a", "b"), m.page(0)!!.strokes.map { it.id }.toSet())
        assertEquals(200L, m.modified)
    }

    @Test
    fun `tombstones win over strokes from the other side`() {
        val base = InkDocument().addStroke(0, stroke("a")).addStroke(0, stroke("b"))
        val local = base.removeStrokes(0, listOf("a"))
        val remote = base.addStroke(0, stroke("c"))
        val m = InkMerge.merge(local, remote)
        assertEquals(listOf("b", "c").toSet(), m.page(0)!!.strokes.map { it.id }.toSet())
        assertTrue("a" in m.deleted)
        // Symmetric.
        val m2 = InkMerge.merge(remote, local)
        assertEquals(m.contentSignature(), m2.contentSignature())
    }

    @Test
    fun `merge is idempotent`() {
        val a = InkDocument().addStroke(1, stroke("x")).addStroke(3, stroke("y"))
        val m = InkMerge.merge(a, a)
        assertEquals(a.contentSignature(), m.contentSignature())
    }

    @Test
    fun `standalone notebook keeps page size and max page count`() {
        val a = InkDocument.newNotebook(PageSize.A4, PaperStyle.RULED, 2)
        val b = a.withPageCount(5).addStroke(4, stroke("z"))
        val m = InkMerge.merge(a, b)
        assertEquals(PageSize.A4, m.pageSize)
        assertEquals(5, m.pageCount)
        assertTrue(m.isStandalone)
    }

    @Test
    fun `inserted pages merge by id and tombstone cleanly`() {
        val ins = PageInsert("p1", after = 0, order = 1.0, w = 595f, h = 842f, t = 1L)
        val a = InkDocument().addInsert(ins).addStroke(PageKey.Insert("p1"), stroke("s1"))
        val b = InkDocument().addInsert(ins).addStroke(PageKey.Insert("p1"), stroke("s2"))
        val m = InkMerge.merge(a, b)
        assertEquals(1, m.orderedInserts().size)
        assertEquals(setOf("s1", "s2"), m.insert("p1")!!.strokes.map { it.id }.toSet())
        assertEquals(2, m.strokeCount())
        // Removing the page on one side wins over additions on the other.
        val removed = a.removeInsert("p1")
        val m2 = InkMerge.merge(removed, b)
        assertTrue(m2.orderedInserts().isEmpty())
        assertTrue("p1" in m2.deleted && "s1" in m2.deleted)
        assertEquals(m2.contentSignature(), InkMerge.merge(b, removed).contentSignature())
        // Round trip keeps inserts.
        val back = InkJson.decode(InkJson.encode(m))
        assertEquals(m.contentSignature(), back.contentSignature())
    }

    @Test
    fun `inserted pages order by after then order`() {
        val d = InkDocument()
            .addInsert(PageInsert("b", after = 2, order = 1.0, w = 1f, h = 1f, t = 1))
            .addInsert(PageInsert("a", after = -1, order = 1.0, w = 1f, h = 1f, t = 2))
            .addInsert(PageInsert("c", after = 2, order = 0.5, w = 1f, h = 1f, t = 3))
        assertEquals(listOf("a", "c", "b"), d.orderedInserts().map { it.id })
    }

    @Test
    fun `json round trip preserves everything`() {
        val doc = InkDocument.newNotebook(PageSize.LETTER, PaperStyle.GRID, 3)
            .addStroke(0, Stroke("s1", ToolType.HIGHLIGHTER, 0x66FFE627, 14f, 5L, floatArrayOf(1f, 2f, 0.3f, 4f, 5f, 0.6f)))
            .removeStrokes(0, listOf("gone"))
        val text = InkJson.encode(doc)
        val back = InkJson.decode(text)
        assertEquals(doc.contentSignature(), back.contentSignature())
        assertEquals(doc.page(0)!!.strokes[0].pts.toList(), back.page(0)!!.strokes[0].pts.toList())
        assertEquals(PaperStyle.GRID, back.paper)
        assertTrue(text.contains("\"format\":\"notesplus-ink\""))
    }

    @Test
    fun `unknown fields are ignored when decoding`() {
        val text = """{"format":"notesplus-ink","version":2,"future":{"x":1},"pages":[{"index":0,"strokes":[],"extra":true}],"modified":5}"""
        val doc = InkJson.decode(text)
        assertEquals(5L, doc.modified)
        assertFalse(doc.isStandalone)
    }
}
