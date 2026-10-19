package com.notesplus.app.ui.common

import android.graphics.Color as AColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ColorSwatch(color: Int, selected: Boolean, size: Dp = 30.dp, editing: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = Color(color)
    val ring = when {
        editing -> MaterialTheme.colorScheme.tertiary
        selected -> MaterialTheme.colorScheme.primary
        else -> Color(0x22000000)
    }
    Box(
        Modifier.size(size).padding(2.dp).clip(CircleShape).background(c)
            .border(if (selected || editing) 2.5.dp else 1.dp, ring, CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        val fg = if (c.luminance() > 0.6f) Color.Black else Color.White
        if (editing) Icon(Icons.Default.Edit, contentDescription = "Editing this colour", tint = fg, modifier = Modifier.size(size * 0.5f))
        else if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * Full colour chooser: palette grid, recently used colours and a free HSV picker with hex entry.
 * With [onReplaceSlot] the palette itself can be edited: pick a slot (or arrive with
 * [initialEditSlot] from a long-press) and the chosen colour replaces it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Int,
    palette: List<Int>,
    recent: List<Int>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    initialEditSlot: Int? = null,
    onReplaceSlot: ((index: Int, color: Int) -> Unit)? = null,
    onResetPalette: (() -> Unit)? = null
) {
    var editSlot by remember { mutableStateOf(initialEditSlot) }
    var editMode by remember { mutableStateOf(initialEditSlot != null) }
    val hsv = remember { FloatArray(3).also { AColor.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val current = AColor.HSVToColor(floatArrayOf(hue, sat, value))
    var hex by remember(current) { mutableStateOf(String.format("%06X", current and 0xFFFFFF)) }

    fun setColor(c: Int) { val h = FloatArray(3); AColor.colorToHSV(c, h); hue = h[0]; sat = h[1]; value = h[2] }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onReplaceSlot != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = editMode, onClick = { editMode = !editMode; if (!editMode) editSlot = null }, label = { Text("Edit palette") })
                        if (editMode) Text(if (editSlot == null) "Tap a swatch to replace it" else "Replacing swatch ${editSlot!! + 1}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        if (editMode && onResetPalette != null) TextButton(onClick = { onResetPalette(); editSlot = null }) { Text("Reset") }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    palette.forEachIndexed { i, c ->
                        ColorSwatch(c, c == current && !editMode, size = 34.dp, editing = editMode && editSlot == i,
                            onLongClick = if (onReplaceSlot != null) ({ editMode = true; editSlot = i; setColor(c) }) else null) {
                            if (editMode) { editSlot = i; setColor(c) } else setColor(c)
                        }
                    }
                }
                if (recent.isNotEmpty()) {
                    Text("Recent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { recent.take(8).forEach { c -> ColorSwatch(c, c == current, size = 30.dp) { setColor(c) } } }
                }
                Text("Custom", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Saturation / value square.
                val pureHue = Color(AColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                Canvas(
                    Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ -> sat = (change.position.x / size.width).coerceIn(0f, 1f); value = 1f - (change.position.y / size.height).coerceIn(0f, 1f) }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { p -> sat = (p.x / size.width).coerceIn(0f, 1f); value = 1f - (p.y / size.height).coerceIn(0f, 1f) }
                        }
                ) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, pureHue)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val p = Offset(sat * size.width, (1f - value) * size.height)
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = p, style = Stroke(2.5.dp.toPx()))
                    drawCircle(Color.Black, radius = 9.dp.toPx(), center = p, style = Stroke(1.dp.toPx()))
                }
                // Hue bar.
                Canvas(
                    Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp))
                        .pointerInput(Unit) { detectDragGestures { change, _ -> hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f } }
                        .pointerInput(Unit) { detectTapGestures { p -> hue = (p.x / size.width).coerceIn(0f, 1f) * 360f } }
                ) {
                    val stops = (0..6).map { Color(AColor.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }
                    drawRect(Brush.horizontalGradient(stops))
                    val x = hue / 360f * size.width
                    drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(x, size.height / 2), style = Stroke(2.5.dp.toPx()))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(current)).border(1.dp, Color(0x22000000), RoundedCornerShape(12.dp)))
                    OutlinedTextField(
                        value = hex, onValueChange = { v ->
                            val clean = v.trimStart('#').uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                            hex = clean
                            if (clean.length == 6) setColor((0xFF000000L or clean.toLong(16)).toInt())
                        },
                        singleLine = true, prefix = { Text("#") }, label = { Text("Hex") }, modifier = Modifier.width(150.dp)
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            val slot = editSlot
            if (editMode && slot != null && onReplaceSlot != null) TextButton(onClick = { onReplaceSlot(slot, current); onPick(current) }) { Text("Replace swatch ${slot + 1}") }
            else TextButton(onClick = { onPick(current) }) { Text("Use colour") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
