package com.notesplus.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notesplus.app.data.db.NoteKind
import com.notesplus.app.editor.DocumentView
import com.notesplus.app.ink.InkDocument
import com.notesplus.app.onedrive.SyncStatus
import com.notesplus.app.ui.common.appViewModel
import com.notesplus.app.ui.library.shareFile

private val PEN_COLORS = listOf(
    0xFF1C1C1E, 0xFF1F4E79, 0xFFD32F2F, 0xFF2E7D32, 0xFF6A1B9A, 0xFFEF6C00, 0xFF00838F, 0xFF5D4037
).map { it.toInt() }
private val HL_COLORS = listOf(0xFFFFE627, 0xFF7CFF5F, 0xFF4FD0FF, 0xFFFF7EB6, 0xFFFFA726, 0xFFB388FF).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(noteId: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "editor-$noteId") { EditorViewModel(it, noteId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var view by remember { mutableStateOf<DocumentView?>(null) }
    var showPenOptions by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { ev ->
            when (ev) {
                is EditorEvent.ExternalInk -> view?.mergeExternal(ev.doc)
                is EditorEvent.Message -> snackbar.showSnackbar(ev.text)
                is EditorEvent.Share -> shareFile(context, ev.file)
            }
        }
    }
    // Save when the app goes to the background and when leaving the screen.
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) vm.flushNow() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); vm.flushNow() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { vm.flushNow(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                title = {
                    Column {
                        Text(state.note?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Page ${state.currentPage + 1} of ${state.pageCount}" + if (state.zoom > 1.05f) " · ${(state.zoom * 100).toInt()}%" else "",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { showJump = true }
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { view?.undo() }, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
                    IconButton(onClick = { view?.redo() }, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }
                    SyncIndicator(state, onClick = { vm.syncNow() })
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (state.note?.kind == NoteKind.INK) DropdownMenuItem(
                                text = { Text("Add page") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, null) },
                                onClick = { menu = false; view?.addPage() }
                            )
                            DropdownMenuItem(text = { Text("Go to page…") }, onClick = { menu = false; showJump = true })
                            DropdownMenuItem(text = { Text("Reset zoom") }, onClick = { menu = false; view?.resetZoom() })
                            DropdownMenuItem(text = { Text("Export annotated PDF") }, onClick = { menu = false; vm.export() })
                            DropdownMenuItem(
                                text = { Text(if (state.fingerDraws) "Finger: draws" else "Finger: scrolls") },
                                leadingIcon = { Icon(Icons.Default.TouchApp, null) },
                                onClick = { menu = false; vm.setFingerDraws(!state.fingerDraws) }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            ToolBar(
                state = state,
                onTool = { t ->
                    // Tapping the active tool again opens its size/colour options.
                    showPenOptions = (t == state.tool && t != DocumentView.Tool.PAN) && !showPenOptions
                    vm.setTool(t)
                },
                onColor = { color -> if (state.tool == DocumentView.Tool.HIGHLIGHTER) vm.setHighlighter(color = color) else vm.setPen(color = color) }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Text(state.error!!, Modifier.align(Alignment.Center).padding(24.dp))
                else -> {
                    AndroidView(
                        factory = { ctx ->
                            DocumentView(ctx).also { v ->
                                v.listener = object : DocumentView.Listener {
                                    override fun onInkChanged(doc: InkDocument, canUndo: Boolean, canRedo: Boolean) = vm.onInkChanged(doc, canUndo, canRedo)
                                    override fun onViewportChanged(currentPage: Int, pageCount: Int, zoom: Float) = vm.onViewportChanged(currentPage, pageCount, zoom)
                                }
                                vm.pageSource?.let { src -> v.post { v.setDocument(src, vm.initialInk) } }
                                view = v
                            }
                        },
                        update = { v ->
                            v.tool = state.tool
                            v.penColor = state.penColor; v.penWidth = state.penWidth
                            v.highlighterColor = state.highlighterColor; v.highlighterWidth = state.highlighterWidth
                            v.eraserRadius = state.eraserRadius
                            v.fingerDraws = state.fingerDraws
                            v.stylusButtonErases = state.stylusButtonErases
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    DisposableEffect(Unit) { onDispose { view?.release(); view = null } }
                }
            }
            if (showPenOptions && (state.tool == DocumentView.Tool.PEN || state.tool == DocumentView.Tool.HIGHLIGHTER || state.tool == DocumentView.Tool.ERASER)) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(0.9f),
                    shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 6.dp
                ) {
                    Column(Modifier.padding(16.dp)) {
                        when (state.tool) {
                            DocumentView.Tool.PEN -> {
                                Text("Pen width: ${"%.1f".format(state.penWidth)} pt", style = MaterialTheme.typography.labelLarge)
                                Slider(value = state.penWidth, onValueChange = { vm.setPen(width = it) }, valueRange = 0.6f..8f)
                            }
                            DocumentView.Tool.HIGHLIGHTER -> {
                                Text("Highlighter width: ${state.highlighterWidth.toInt()} pt", style = MaterialTheme.typography.labelLarge)
                                Slider(value = state.highlighterWidth, onValueChange = { vm.setHighlighter(width = it) }, valueRange = 6f..36f)
                            }
                            else -> {
                                Text("Eraser size: ${state.eraserRadius.toInt()} pt", style = MaterialTheme.typography.labelLarge)
                                Slider(value = state.eraserRadius, onValueChange = { vm.setEraserRadius(it) }, valueRange = 3f..40f)
                            }
                        }
                        TextButton(onClick = { showPenOptions = false }, modifier = Modifier.align(Alignment.End)) { Text("Done") }
                    }
                }
            }
        }
    }

    if (showJump) {
        var text by remember { mutableStateOf("${state.currentPage + 1}") }
        AlertDialog(
            onDismissRequest = { showJump = false },
            title = { Text("Go to page") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.filter { ch -> ch.isDigit() } }, singleLine = true, label = { Text("1 – ${state.pageCount}") }) },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntOrNull()?.let { p -> view?.scrollToPage((p - 1).coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))) }
                    showJump = false
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { showJump = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SyncIndicator(state: EditorState, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        when {
            state.sync is SyncStatus.Running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            state.unsaved || state.note?.needsUpload == true -> Icon(Icons.Default.CloudUpload, contentDescription = "Changes pending upload", tint = MaterialTheme.colorScheme.tertiary)
            state.note?.inkRemoteId != null -> Icon(Icons.Default.CloudDone, contentDescription = "Synced", tint = MaterialTheme.colorScheme.primary)
            else -> Icon(Icons.Default.Sync, contentDescription = "Sync")
        }
    }
}

@Composable
private fun ToolBar(state: EditorState, onTool: (DocumentView.Tool) -> Unit, onColor: (Int) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ToolButton(Icons.Default.Draw, "Pen", state.tool == DocumentView.Tool.PEN) { onTool(DocumentView.Tool.PEN) }
            ToolButton(Icons.Default.BorderColor, "Highlighter", state.tool == DocumentView.Tool.HIGHLIGHTER) { onTool(DocumentView.Tool.HIGHLIGHTER) }
            ToolButton(Icons.Default.AutoFixNormal, "Eraser", state.tool == DocumentView.Tool.ERASER) { onTool(DocumentView.Tool.ERASER) }
            ToolButton(Icons.Default.PanTool, "Pan / zoom", state.tool == DocumentView.Tool.PAN) { onTool(DocumentView.Tool.PAN) }
            Spacer(Modifier.width(8.dp))
            val colors = if (state.tool == DocumentView.Tool.HIGHLIGHTER) HL_COLORS else PEN_COLORS
            val selected = if (state.tool == DocumentView.Tool.HIGHLIGHTER) state.highlighterColor else state.penColor
            if (state.tool == DocumentView.Tool.PEN || state.tool == DocumentView.Tool.HIGHLIGHTER) {
                colors.forEach { c ->
                    Box(
                        Modifier.size(30.dp).padding(2.dp).clip(CircleShape).background(Color(c))
                            .border(if (c == selected) 3.dp else 1.dp, if (c == selected) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape)
                            .clickable { onColor(c) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    FilledIconToggleButton(checked = selected, onCheckedChange = { onClick() }) {
        Icon(icon, contentDescription = label)
    }
}
