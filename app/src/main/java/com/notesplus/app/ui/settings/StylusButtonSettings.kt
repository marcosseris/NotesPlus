package com.notesplus.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.notesplus.app.data.StylusButtonMode
import com.notesplus.app.input.StylusButtons

/** Ignore events this soon after the push: pens that send a whole click on push would otherwise "release" instantly. */
private const val RELEASE_DEBOUNCE_MS = 250L

private enum class LearnStep { PRESS, RELEASE, DONE }

@Composable
fun StylusButtonSettings(
    mode: StylusButtonMode, press: String, release: String,
    onMode: (StylusButtonMode) -> Unit, onLearned: (press: String, release: String) -> Unit
) {
    var learning by remember { mutableStateOf(false) }

    Text("Pen side button", Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp), style = MaterialTheme.typography.titleSmall)
    StylusButtonMode.entries.forEach { m ->
        Row(Modifier.fillMaxWidth().clickable { onMode(m) }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = mode == m, onClick = { onMode(m) })
            Column(Modifier.padding(start = 4.dp)) {
                Text(m.label, style = MaterialTheme.typography.bodyLarge)
                Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    ListItem(
        headlineContent = { Text("Button mapping") },
        supportingContent = {
            Text(
                "Push: ${StylusButtons.describe(press)}\nRelease: ${if (release.isBlank()) "none (use “Press to switch”)" else StylusButtons.describe(release)}" +
                    if (press == StylusButtons.DEFAULT_PRESS && release == StylusButtons.DEFAULT_RELEASE) " · default" else ""
            )
        },
        trailingContent = {
            Row {
                if (press != StylusButtons.DEFAULT_PRESS || release != StylusButtons.DEFAULT_RELEASE)
                    TextButton(onClick = { onLearned(StylusButtons.DEFAULT_PRESS, StylusButtons.DEFAULT_RELEASE) }) { Text("Reset") }
                TextButton(onClick = { learning = true }, enabled = !learning) { Text("Learn") }
            }
        }
    )
    if (learning) LearnPanel(
        onSave = { p, r -> onLearned(p, r); learning = false },
        onCancel = { learning = false }
    )
}

/**
 * Inline (not a dialog: dialogs are separate windows and their input never passes through the
 * activity, which is where pen-button events are captured).
 */
@Composable
private fun LearnPanel(onSave: (String, String) -> Unit, onCancel: () -> Unit) {
    var step by remember { mutableStateOf(LearnStep.PRESS) }
    var press by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<String?>(null) }
    var pressAt by remember { mutableStateOf(0L) }
    val log = remember { mutableStateListOf<String>() }

    DisposableEffect(Unit) {
        StylusButtons.learning = true
        onDispose { StylusButtons.learning = false }
    }
    LaunchedEffect(Unit) {
        StylusButtons.signals.collect { sig ->
            log += StylusButtons.describe(sig)
            if (log.size > 6) log.removeAt(0)
            val now = System.currentTimeMillis()
            when (step) {
                LearnStep.PRESS -> { press = sig; pressAt = now; step = LearnStep.RELEASE }
                LearnStep.RELEASE -> if (now - pressAt >= RELEASE_DEBOUNCE_MS) { release = sig; step = LearnStep.DONE }
                LearnStep.DONE -> Unit
            }
        }
    }

    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Learn the side button", style = MaterialTheme.typography.titleMedium)
            Text(
                when (step) {
                    LearnStep.PRESS -> "Hold the pen just above the screen (pen buttons are only reported while the pen is near), then push the side button and keep holding it."
                    LearnStep.RELEASE -> "Got the push: ${StylusButtons.describe(press!!)}.\nNow let go of the button."
                    LearnStep.DONE -> "Push: ${StylusButtons.describe(press!!)}\nRelease: ${StylusButtons.describe(release!!)}" +
                        if (press == release) "\nYour pen sends the same event for both, so each event alternates between pushed and released." else ""
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (log.isNotEmpty()) {
                Text("Received:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                log.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                if (step != LearnStep.PRESS) TextButton(onClick = { press = null; release = null; log.clear(); step = LearnStep.PRESS }) { Text("Retry") }
                if (step == LearnStep.RELEASE) TextButton(onClick = { onSave(press!!, "") }) { Text("No release event") }
                if (step == LearnStep.DONE) Button(onClick = { onSave(press!!, release!!) }) { Text("Save") }
            }
        }
    }
}
