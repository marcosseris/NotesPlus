package com.notesplus.app.editor

import com.notesplus.app.ink.InkDocument
import com.notesplus.app.ink.PageInsert
import com.notesplus.app.ink.PageKey
import com.notesplus.app.ink.Stroke
import java.util.UUID

/**
 * Undo/redo over ink operations. Removals always leave tombstones (so they sync),
 * therefore "re-adding" a stroke or page gives it a fresh id instead of resurrecting the old one.
 */
class UndoManager(private val limit: Int = 200) {
    sealed class Op {
        class Add(val page: PageKey, var strokes: List<Stroke>) : Op()
        class Erase(val page: PageKey, var strokes: List<Stroke>) : Op()
        class InsertPage(var insert: PageInsert) : Op()
        class RemovePage(var insert: PageInsert) : Op()
    }

    private val undoStack = ArrayDeque<Op>()
    private val redoStack = ArrayDeque<Op>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun record(op: Op) {
        undoStack.addLast(op)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    fun clear() { undoStack.clear(); redoStack.clear() }

    private fun freshCopy(ins: PageInsert) = ins.copy(id = UUID.randomUUID().toString(), strokes = ins.strokes.map { it.copy(id = UUID.randomUUID().toString()) })

    /** Returns the new document; the op's identifiers are rewritten when something was re-added. */
    fun undo(doc: InkDocument): InkDocument {
        val op = undoStack.removeLastOrNull() ?: return doc
        val result = when (op) {
            is Op.Add -> doc.removeStrokes(op.page, op.strokes.map { it.id })
            is Op.Erase -> {
                val copies = op.strokes.map { it.copy(id = UUID.randomUUID().toString()) }
                op.strokes = copies
                copies.fold(doc) { d, s -> d.addStroke(op.page, s) }
            }
            is Op.InsertPage -> doc.removeInsert(op.insert.id)
            is Op.RemovePage -> { val copy = freshCopy(op.insert); op.insert = copy; doc.addInsert(copy) }
        }
        redoStack.addLast(op)
        return result
    }

    fun redo(doc: InkDocument): InkDocument {
        val op = redoStack.removeLastOrNull() ?: return doc
        val result = when (op) {
            is Op.Add -> {
                val copies = op.strokes.map { it.copy(id = UUID.randomUUID().toString()) }
                op.strokes = copies
                copies.fold(doc) { d, s -> d.addStroke(op.page, s) }
            }
            is Op.Erase -> doc.removeStrokes(op.page, op.strokes.map { it.id })
            is Op.InsertPage -> { val copy = freshCopy(op.insert); op.insert = copy; doc.addInsert(copy) }
            is Op.RemovePage -> doc.removeInsert(op.insert.id)
        }
        undoStack.addLast(op)
        return result
    }
}
