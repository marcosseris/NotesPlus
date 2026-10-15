package com.notesplus.app.ui.editor

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notesplus.app.data.db.NoteKind
import com.notesplus.app.editor.DocumentView
import com.notesplus.app.ink.InkDocument
import com.notesplus.app.ink.ShapeKind
import com.notesplus.app.onedrive.SyncStatus
import com.notesplus.app.ui.common.appViewModel
import com.notesplus.app.ui.library.shareFile

private val PEN_COLORS = listOf(
    0xFF1C1C1E, 0xFF1F4E79, 0xFFD32F2F, 0xFF2E7D32, 0xFF6A1B9A, 0xFFEF6C00, 0xFF00838F, 0xFF5D4037
).map { it.toInt() }
private val HL_COLORS = listOf(0xFFFFE627, 0xFF7CFF5F, 0xFF4FD0FF, 0xFFFF7EB6, 0xFFFFA726, 0xFFB388FF).map { it.toInt() }
private val PEN_WIDTHS = listOf(1.2f, 2.2f, 4.0f)
private val HL_WIDTHS = listOf(10f, 16f, 26f)

/** Width from which the tablet layout (tool rail + page navigator) is used. The Honor Pad 9 qualifies in both orientations. */
private val WIDE_LAYOUT_MIN_WIDTH = 600.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(noteId: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "editor-$noteId") { EditorViewModel(it, noteId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var view by remember { mutableStateOf<DocumentView?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
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
    // Save when the app goes to the background and when leaving the screen; keep the screen awake while writing.
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) vm.flushNow() }
        lifecycleOwner.lifecycle.addObserver(obs)
        val window = (context as? ComponentActivity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            vm.flushNow()
        }
    }

    val selectTool: (DocumentView.Tool) -> Unit = { t ->
        // Tapping the active tool again opens its size options.
        showOptions = (t == state.tool && t != DocumentView.Tool.PAN && t != DocumentView.Tool.SHAPE) && !showOptions
        vm.setTool(t)
    }
    val selectColor: (Int) -> Unit = { c -> if (state.tool == DocumentView.Tool.HIGHLIGHTER) vm.setHighlighter(color = c) else vm.setPen(color = c) }
    val selectWidth: (Float) -> Unit = { w -> if (state.tool == DocumentView.Tool.HIGHLIGHTER) vm.setHighlighter(width = w) else vm.setPen(width = w) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_LAYOUT_MIN_WIDTH
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { vm.flushNow(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                    title = {
                        Column(Modifier.clickable { showRename = true }) {
                            Text(state.note?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Page ${state.currentPage + 1} of ${state.pageCount}" + if (state.zoom > 1.05f) " · ${(state.zoom * 100).toInt()}%" else "",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        if (!wide) {
                            IconButton(onClick = { view?.undo() }, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
                            IconButton(onClick = { view?.redo() }, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }
                        }
                        FilledIconToggleButton(checked = state.showPages, onCheckedChange = { vm.togglePages() }) {
                            Icon(Icons.AutoMirrored.Filled.ViewSidebar, contentDescription = "Pages")
                        }
                        SyncIndicator(state, onClick = { vm.syncNow() })
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (state.note?.kind == NoteKind.INK) DropdownMenuItem(
                                    text = { Text("Add page") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, null) },
                                    onClick = { menu = false; view?.addPage() }
                                )
                                DropdownMenuItem(text = { Text("Go to page…") }, onClick = { menu = false; showJump = true })
                                DropdownMenuItem(text = { Text("Fit page") }, leadingIcon = { Icon(Icons.Default.FitScreen, null) }, onClick = { menu = false; view?.fitPage() })
                                DropdownMenuItem(text = { Text("Fit width") }, onClick = { menu = false; view?.fitWidth() })
                                DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; showRename = true })
                                DropdownMenuItem(text = { Text("Export annotated PDF") }, onClick = { menu = false; vm.export() })
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(if (state.autoShape) "Shape snapping: on" else "Shape snapping: off") },
                                    leadingIcon = { Icon(Icons.Default.Category, null) },
                                    onClick = { menu = false; vm.setAutoShape(!state.autoShape) }
                                )
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
                if (!wide) Column {
                    if (state.showPages && !state.loading) PagesStrip(vm, state, onPage = { view?.scrollToPage(it) })
                    BottomToolBar(state, selectTool, selectColor, onShape = vm::setShapeKind)
                }
            }
        ) { padding ->
            Row(Modifier.padding(padding).fillMaxSize()) {
                if (wide && !state.railOnRight) ToolRail(state, view, selectTool, selectColor, selectWidth, onShape = vm::setShapeKind)
                Box(Modifier.weight(1f).fillMaxHeight()) {
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
                                            override fun onToolShortcut(tool: DocumentView.Tool) = vm.setTool(tool)
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
                                    v.shapeKind = state.shapeKind
                                    v.autoShape = state.autoShape
                                    v.motionPrediction = state.motionPrediction
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            DisposableEffect(Unit) { onDispose { view?.release(); view = null } }
                        }
                    }
                    if (showOptions) ToolOptionsPanel(state, vm, onClose = { showOptions = false }, modifier = Modifier.align(Alignment.BottomCenter))
                }
                if (wide && state.showPages && !state.loading) PagesPanel(vm, state, onPage = { view?.scrollToPage(it) })
                if (wide && state.railOnRight) ToolRail(state, view, selectTool, selectColor, selectWidth, onShape = vm::setShapeKind)
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
    if (showRename) {
        var text by remember { mutableStateOf(state.note?.title ?: "") }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename note") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Title") }) },
            confirmButton = { TextButton(onClick = { vm.rename(text); showRename = false }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }
}

// ----------------------------------------------------------------------------- pieces

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

private fun shapeIcon(k: ShapeKind): ImageVector = when (k) {
    ShapeKind.LINE -> Icons.Default.HorizontalRule
    ShapeKind.ARROW -> Icons.AutoMirrored.Filled.TrendingFlat
    ShapeKind.RECTANGLE -> Icons.Default.CropSquare
    ShapeKind.ELLIPSE -> Icons.Default.RadioButtonUnchecked
    ShapeKind.TRIANGLE -> Icons.Default.ChangeHistory
}

private fun shapeLabel(k: ShapeKind) = when (k) {
    ShapeKind.LINE -> "Line"; ShapeKind.ARROW -> "Arrow"; ShapeKind.RECTANGLE -> "Rectangle"
    ShapeKind.ELLIPSE -> "Ellipse"; ShapeKind.TRIANGLE -> "Triangle"
}

/** Shape tool button: tap selects the tool, tapping again (or when already active) opens the shape list. */
@Composable
private fun ShapeToolButton(state: EditorState, onSelect: (DocumentView.Tool) -> Unit, onShape: (ShapeKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledIconToggleButton(
            checked = state.tool == DocumentView.Tool.SHAPE,
            onCheckedChange = { if (state.tool == DocumentView.Tool.SHAPE) open = true else onSelect(DocumentView.Tool.SHAPE) }
        ) { Icon(shapeIcon(state.shapeKind), contentDescription = "Shapes (${shapeLabel(state.shapeKind)})") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ShapeKind.entries.forEach { k ->
                DropdownMenuItem(
                    text = { Text(shapeLabel(k)) }, leadingIcon = { Icon(shapeIcon(k), null) },
                    onClick = { open = false; onShape(k) }
                )
            }
            HorizontalDivider()
            Text("Hold the pen's button (or Shift) while dragging for a square, circle or 45° line.",
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp).width(220.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ColorDot(color: Int, selected: Boolean, size: Dp = 30.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).padding(2.dp).clip(CircleShape).background(Color(color))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun WidthDot(width: Float, selected: Boolean, maxWidth: Float, onClick: () -> Unit) {
    val d = (8 + 14 * (width / maxWidth)).dp
    Box(
        Modifier.size(34.dp).clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Box(Modifier.size(d).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface)) }
}

/** Vertical rail for tablets: everything reachable with the pen without leaving the page. */
@Composable
private fun ToolRail(
    state: EditorState, view: DocumentView?,
    onTool: (DocumentView.Tool) -> Unit, onColor: (Int) -> Unit, onWidth: (Float) -> Unit, onShape: (ShapeKind) -> Unit
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxHeight().width(64.dp)) {
        Column(
            Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(onClick = { view?.undo() }, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
            IconButton(onClick = { view?.redo() }, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }
            HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            ToolButton(Icons.Default.Draw, "Pen (P)", state.tool == DocumentView.Tool.PEN) { onTool(DocumentView.Tool.PEN) }
            ToolButton(Icons.Default.BorderColor, "Highlighter (H)", state.tool == DocumentView.Tool.HIGHLIGHTER) { onTool(DocumentView.Tool.HIGHLIGHTER) }
            ToolButton(Icons.Default.AutoFixNormal, "Eraser (E)", state.tool == DocumentView.Tool.ERASER) { onTool(DocumentView.Tool.ERASER) }
            ShapeToolButton(state, onTool, onShape)
            ToolButton(Icons.Default.PanTool, "Pan / zoom (Space)", state.tool == DocumentView.Tool.PAN) { onTool(DocumentView.Tool.PAN) }
            val inkTool = state.tool == DocumentView.Tool.PEN || state.tool == DocumentView.Tool.HIGHLIGHTER || state.tool == DocumentView.Tool.SHAPE
            if (inkTool) {
                HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                val hl = state.tool == DocumentView.Tool.HIGHLIGHTER
                val colors = if (hl) HL_COLORS else PEN_COLORS
                val selected = if (hl) state.highlighterColor else state.penColor
                colors.forEach { c -> ColorDot(c, c == selected) { onColor(c) } }
                HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                val widths = if (hl) HL_WIDTHS else PEN_WIDTHS
                val cur = if (hl) state.highlighterWidth else state.penWidth
                widths.forEach { w -> WidthDot(w, kotlin.math.abs(w - cur) < 0.05f, widths.last()) { onWidth(w) } }
            }
            HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            IconButton(onClick = { view?.fitPage() }) { Icon(Icons.Default.FitScreen, contentDescription = "Fit page") }
        }
    }
}

@Composable
private fun BottomToolBar(state: EditorState, onTool: (DocumentView.Tool) -> Unit, onColor: (Int) -> Unit, onShape: (ShapeKind) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ToolButton(Icons.Default.Draw, "Pen", state.tool == DocumentView.Tool.PEN) { onTool(DocumentView.Tool.PEN) }
            ToolButton(Icons.Default.BorderColor, "Highlighter", state.tool == DocumentView.Tool.HIGHLIGHTER) { onTool(DocumentView.Tool.HIGHLIGHTER) }
            ToolButton(Icons.Default.AutoFixNormal, "Eraser", state.tool == DocumentView.Tool.ERASER) { onTool(DocumentView.Tool.ERASER) }
            ShapeToolButton(state, onTool, onShape)
            ToolButton(Icons.Default.PanTool, "Pan / zoom", state.tool == DocumentView.Tool.PAN) { onTool(DocumentView.Tool.PAN) }
            Spacer(Modifier.width(8.dp))
            val hl = state.tool == DocumentView.Tool.HIGHLIGHTER
            if (state.tool == DocumentView.Tool.PEN || hl || state.tool == DocumentView.Tool.SHAPE) {
                val colors = if (hl) HL_COLORS else PEN_COLORS
                val selected = if (hl) state.highlighterColor else state.penColor
                colors.forEach { c -> ColorDot(c, c == selected) { onColor(c) } }
            }
        }
    }
}

@Composable
private fun ToolOptionsPanel(state: EditorState, vm: EditorViewModel, onClose: () -> Unit, modifier: Modifier) {
    Surface(modifier = modifier.padding(12.dp).width(360.dp), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 6.dp) {
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
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Done") }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    FilledIconToggleButton(checked = selected, onCheckedChange = { onClick() }) { Icon(icon, contentDescription = label) }
}

/** Page navigator for tablets (right of the canvas). */
@Composable
private fun PagesPanel(vm: EditorViewModel, state: EditorState, onPage: (Int) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentPage) {
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (state.currentPage !in visible) listState.animateScrollToItem(state.currentPage)
    }
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxHeight().width(148.dp)) {
        LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.pageCount) { i -> PageThumb(vm, i, selected = i == state.currentPage, width = 120.dp) { onPage(i) } }
        }
    }
}

/** Horizontal page strip for phones (above the bottom bar). */
@Composable
private fun PagesStrip(vm: EditorViewModel, state: EditorState, onPage: (Int) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentPage) {
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (state.currentPage !in visible) listState.animateScrollToItem(state.currentPage)
    }
    Surface(tonalElevation = 2.dp) {
        LazyRow(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.pageCount) { i -> PageThumb(vm, i, selected = i == state.currentPage, width = 72.dp) { onPage(i) } }
        }
    }
}

@Composable
private fun PageThumb(vm: EditorViewModel, index: Int, selected: Boolean, width: Dp, onClick: () -> Unit) {
    var bmp by remember(index) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(index) { bmp = vm.pageThumbnail(index) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(width).clickable(onClick = onClick)) {
        Box(
            Modifier.width(width).clip(RoundedCornerShape(4.dp))
                .border(if (selected) 2.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0x33000000), RoundedCornerShape(4.dp))
                .background(Color.White)
        ) {
            val b = bmp
            if (b != null) Image(b.asImageBitmap(), contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxWidth())
            else Box(Modifier.fillMaxWidth().height(width * 1.3f))
        }
        Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
