package com.notesplus.app.editor

import com.notesplus.app.ink.InkDocument
import com.notesplus.app.ink.Stroke
import java.util.UUID

/**
 * Undo/redo over ink operations. Removals always leave tombstones (so they sync),
 * therefore "re-adding" a stroke gives it a fresh id instead of resurrecting the old one.
 */
class UndoManager(private val limit: Int = 200) {
    sealed class Op(val page: Int) {
        class Add(page: Int, var strokes: List<Stroke>) : Op(page)
        class Erase(page: Int, var strokes: List<Stroke>) : Op(page)
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

    fun undo(doc: InkDocument): InkDocument {
        val op = undoStack.removeLastOrNull() ?: return doc
        val result = when (op) {
            is Op.Add -> doc.removeStrokes(op.page, op.strokes.map { it.id })
            is Op.Erase -> {
                val copies = op.strokes.map { it.copy(id = UUID.randomUUID().toString()) }
                op.strokes = copies
                copies.fold(doc) { d, s -> d.addStroke(op.page, s) }
            }
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
        }
        undoStack.addLast(op)
        return result
    }
}
