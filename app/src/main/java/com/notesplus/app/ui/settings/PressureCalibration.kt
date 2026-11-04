package com.notesplus.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/** Same hysteresis as the editor: a stroke ends below this fraction of the threshold. */
private const val RELEASE_FACTOR = 0.7f
private const val MEASURE_MS = 4_000L

private enum class Measure { NONE, HOVER, WRITE }

/** Holds the live state of the test pad so the pointer loop never sees stale values. */
private class PadState(initial: Float) {
    var threshold by mutableFloatStateOf(initial)
    var live by mutableFloatStateOf(0f)
    var measure by mutableStateOf(Measure.NONE)
    var hoverMax by mutableStateOf<Float?>(null)
    var writeLow by mutableStateOf<Float?>(null)
    val writeSamples = ArrayList<Float>()
    var hoverPeak = 0f
    val strokes = mutableStateListOf<List<Offset>>()
    val current = mutableStateListOf<Offset>()
    val ghosts = mutableStateListOf<Offset>()

    fun onSample(pos: Offset, pressure: Float, pressed: Boolean) {
        live = if (pressed) pressure else 0f
        if (pressed) when (measure) {
            Measure.HOVER -> hoverPeak = maxOf(hoverPeak, pressure)
            Measure.WRITE -> writeSamples += pressure
            Measure.NONE -> Unit
        }
        val drawing = current.isNotEmpty()
        val needed = if (drawing) threshold * RELEASE_FACTOR else threshold
        if (pressed && pressure >= needed) {
            current += pos
        } else {
            endStroke()
            if (pressed) { ghosts += pos; if (ghosts.size > 600) ghosts.subList(0, 100).clear() }
        }
    }

    fun endStroke() {
        if (current.isNotEmpty()) { strokes += current.toList(); current.clear() }
    }

    fun clear() { strokes.clear(); current.clear(); ghosts.clear() }

    /** Threshold suggested from the two measurements, or null when there is nothing to go on. */
    fun suggestion(): Float? {
        val h = hoverMax ?: return null
        val w = writeLow
        val t = when {
            h <= 0.001f -> 0f                                   // hover reported no contact at all
            w != null && w > h + 0.04f -> h + (w - h) * 0.4f    // closer to hover so light writing still counts
            else -> h + 0.05f
        }
        return t.coerceIn(0f, 0.9f)
    }
}

@Composable
fun PressureCalibrationDialog(initial: Float, onSave: (Float) -> Unit, onDismiss: () -> Unit) {
    val pad = remember { PadState(initial) }
    val ink = MaterialTheme.colorScheme.onSurface
    val ghost = MaterialTheme.colorScheme.error.copy(alpha = 0.45f)

    LaunchedEffect(pad.measure) {
        when (pad.measure) {
            Measure.NONE -> Unit
            Measure.HOVER -> {
                pad.hoverPeak = 0f
                delay(MEASURE_MS)
                pad.hoverMax = pad.hoverPeak
                pad.measure = Measure.NONE
            }
            Measure.WRITE -> {
                pad.writeSamples.clear()
                delay(MEASURE_MS)
                val s = pad.writeSamples.sorted()
                // A low percentile: the lightest pressure you still use while writing.
                pad.writeLow = if (s.isEmpty()) null else s[(s.size * 0.1f).toInt().coerceAtMost(s.size - 1)]
                pad.measure = Measure.NONE
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp,
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth()
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pen pressure calibration", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "If your pen draws while hovering, set a minimum pressure. The pen then only draws once it presses at least this hard. " +
                        "Red dots on the pad show contact that is being ignored.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Live meter.
                PressureMeter(pad.live, pad.threshold, pad.hoverMax, pad.writeLow)
                Text(
                    "Now ${(pad.live * 100).toInt()}% · threshold ${if (pad.threshold <= 0f) "off" else "${(pad.threshold * 100).toInt()}%"}",
                    style = MaterialTheme.typography.labelMedium
                )

                // Test pad.
                Box(
                    Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    for (ch in e.changes) {
                                        if (ch.type != PointerType.Stylus && ch.type != PointerType.Eraser) continue
                                        pad.onSample(ch.position, ch.pressure, ch.pressed)
                                        ch.consume()
                                    }
                                }
                            }
                        }
                ) {
                    val padInk = Color(0xFF1C1C1E)
                    Canvas(Modifier.fillMaxSize()) {
                        pad.ghosts.forEach { drawCircle(ghost, radius = 2.5.dp.toPx(), center = it) }
                        val style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        (pad.strokes + listOf(pad.current.toList())).forEach { pts ->
                            if (pts.isEmpty()) return@forEach
                            if (pts.size == 1) { drawCircle(padInk, radius = 1.4.dp.toPx(), center = pts[0]); return@forEach }
                            val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }
                            drawPath(path, padInk, style = style)
                        }
                    }
                    if (pad.strokes.isEmpty() && pad.current.isEmpty() && pad.ghosts.isEmpty()) {
                        Text("Test your pen here", Modifier.align(Alignment.Center), color = Color(0x88000000))
                    }
                    if (pad.measure != Measure.NONE) {
                        Text(
                            if (pad.measure == Measure.HOVER) "Hover the pen just above the pad…" else "Write normally on the pad…",
                            Modifier.align(Alignment.TopCenter).padding(8.dp), color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                // Auto-calibration.
                Text("Auto-calibrate", style = MaterialTheme.typography.titleSmall)
                Text(
                    "1. Hover the pen over the pad without touching it for 4 seconds (move it around close to the glass). " +
                        "2. Write normally for 4 seconds. Then apply the suggested threshold.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pad.measure = Measure.HOVER }, enabled = pad.measure == Measure.NONE) {
                        Text(pad.hoverMax?.let { "1. Hover · ${(it * 100).toInt()}%" } ?: "1. Measure hover")
                    }
                    OutlinedButton(onClick = { pad.measure = Measure.WRITE }, enabled = pad.measure == Measure.NONE) {
                        Text(pad.writeLow?.let { "2. Write · ${(it * 100).toInt()}%" } ?: "2. Measure writing")
                    }
                }
                val suggested = pad.suggestion()
                if (suggested != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (suggested <= 0f) "Your pen reported no contact while hovering, so no threshold is needed."
                            else "Suggested threshold: ${(suggested * 100).toInt()}%",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = { pad.threshold = suggested; pad.clear() }) { Text("Apply") }
                    }
                    val w = pad.writeLow; val h = pad.hoverMax
                    if (w != null && h != null && w <= h + 0.04f && h > 0.001f) {
                        Text(
                            "Your writing pressure is close to the hover reading, so you may need to press a little firmer than usual.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                // Manual.
                Text("Minimum pressure · ${if (pad.threshold <= 0f) "off" else "${(pad.threshold * 100).toInt()}%"}", style = MaterialTheme.typography.titleSmall)
                Slider(value = pad.threshold, onValueChange = { pad.threshold = (it * 100).toInt() / 100f }, valueRange = 0f..0.9f)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { pad.clear() }) { Text("Clear pad") }
                    TextButton(onClick = { pad.threshold = 0f }) { Text("Turn off") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onSave(pad.threshold) }) { Text("Save") }
                }
            }
        }
    }
}

/** Bar showing the current pressure, with markers for the threshold and the measured hover/writing levels. */
@Composable
private fun PressureMeter(live: Float, threshold: Float, hover: Float?, write: Float?) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val above = MaterialTheme.colorScheme.primary
    val below = MaterialTheme.colorScheme.error
    val marker = MaterialTheme.colorScheme.onSurface
    val hoverColor = MaterialTheme.colorScheme.error
    val writeColor = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp))) {
        drawRect(track)
        val fill = if (threshold > 0f && live < threshold) below else above
        drawRect(fill, size = size.copy(width = size.width * live.coerceIn(0f, 1f)))
        fun tick(v: Float, c: Color, w: Float) {
            val x = size.width * v.coerceIn(0f, 1f)
            drawLine(c, Offset(x, 0f), Offset(x, size.height), strokeWidth = w)
        }
        hover?.let { tick(it, hoverColor, 2.dp.toPx()) }
        write?.let { tick(it, writeColor, 2.dp.toPx()) }
        if (threshold > 0f) tick(threshold, marker, 3.dp.toPx())
    }
}
