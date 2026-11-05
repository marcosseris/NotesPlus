package com.notesplus.app.input

import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Turns whatever a pen's side button produces into plain "signals" so it can be learned and
 * mapped. Pens differ wildly: most report a MotionEvent button (BUTTON_STYLUS_PRIMARY,
 * BUTTON_SECONDARY, …) but some send key presses (KEYCODE_STYLUS_BUTTON_PRIMARY, page keys,
 * even BACK), and some send a different event on release than on push.
 *
 * A signal is a string such as `btn:32:down`, `btn:32:up`, `key:62:down` or `key:62:up`.
 * [MainActivity] feeds every key and motion event in before the views see them.
 */
object StylusButtons {
    const val DEFAULT_PRESS = "btn:${MotionEvent.BUTTON_STYLUS_PRIMARY}:down"
    const val DEFAULT_RELEASE = "btn:${MotionEvent.BUTTON_STYLUS_PRIMARY}:up"

    private val _signals = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val signals: SharedFlow<String> = _signals.asSharedFlow()

    /** While true (the learn dialog is open) every key is swallowed, so e.g. a pen sending BACK does not close it. */
    @Volatile var learning = false

    /** Key signals the editor has mapped; these keys are swallowed so they don't also scroll, go back, etc. */
    @Volatile var consumedKeys: Set<Int> = emptySet()

    private var lastButtons = 0

    /** Returns true when the key event was used up and must not reach the rest of the app. */
    fun onKey(ev: KeyEvent): Boolean {
        val code = ev.keyCode
        // Ordinary typing or the system's volume keys are never pen buttons unless learned.
        val relevant = learning || code in consumedKeys
        if (!relevant) return false
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> if (ev.repeatCount == 0) _signals.tryEmit("key:$code:down")
            KeyEvent.ACTION_UP -> _signals.tryEmit("key:$code:up")
        }
        return true
    }

    /** Watches pen events for side-button changes (works while hovering and while touching). */
    fun onMotion(ev: MotionEvent) {
        if (ev.pointerCount == 0) return
        val tt = ev.getToolType(0)
        if (tt != MotionEvent.TOOL_TYPE_STYLUS && tt != MotionEvent.TOOL_TYPE_ERASER) return
        val now = ev.buttonState
        val changed = now xor lastButtons
        if (changed == 0) return
        var bit = 1
        while (bit != 0 && bit <= changed) {
            if (changed and bit != 0) _signals.tryEmit("btn:$bit:${if (now and bit != 0) "down" else "up"}")
            bit = bit shl 1
        }
        lastButtons = now
    }

    /** Key code a signal refers to, or null for MotionEvent buttons. */
    fun keyCodeOf(signal: String): Int? =
        if (signal.startsWith("key:")) signal.split(':').getOrNull(1)?.toIntOrNull() else null

    /** Human-readable name for settings, e.g. "Pen button 1 pressed" or "Key PAGE_DOWN released". */
    fun describe(signal: String): String {
        val parts = signal.split(':')
        if (parts.size != 3) return "Not set"
        val dir = if (parts[2] == "down") "pressed" else "released"
        val n = parts[1].toIntOrNull() ?: return signal
        return when (parts[0]) {
            "btn" -> "${buttonName(n)} $dir"
            "key" -> "Key ${KeyEvent.keyCodeToString(n).removePrefix("KEYCODE_")} $dir"
            else -> signal
        }
    }

    private fun buttonName(bit: Int) = when (bit) {
        MotionEvent.BUTTON_STYLUS_PRIMARY -> "Pen button 1"
        MotionEvent.BUTTON_STYLUS_SECONDARY -> "Pen button 2"
        MotionEvent.BUTTON_PRIMARY -> "Primary button"
        MotionEvent.BUTTON_SECONDARY -> "Secondary button"
        MotionEvent.BUTTON_TERTIARY -> "Middle button"
        else -> "Button $bit"
    }
}
