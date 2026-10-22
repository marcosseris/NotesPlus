package com.notesplus.app.ui.editor

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FrontHand
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notesplus.app.editor.DocumentView
import com.notesplus.app.ink.InkDocument
import com.notesplus.app.ink.ShapeKind
import com.notesplus.app.cloud.SyncStatus
import com.notesplus.app.ui.common.ColorPickerDialog
import com.notesplus.app.ui.common.ColorSwatch
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import com.notesplus.app.ui.common.appViewModel
import com.notesplus.app.ui.common.shareFile

private val PEN_WIDTHS = listOf(1.2f, 2.2f, 4.0f)
private val HL_WIDTHS = listOf(10f, 16f, 26f)

/** Width from which the tablet layout (floating rail + page navigator) is used. */
private val WIDE_LAYOUT_MIN_WIDTH = 600.dp

/** How many palette colours sit directly on the rail / bar; the rest live behind "+". */
private const val QUICK_COLORS = 7

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(noteId: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "editor-$noteId") { EditorViewModel(it, noteId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    var view by remember { mutableStateOf<DocumentView?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var showClearAll by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var editSlot by remember { mutableStateOf<Int?>(null) }
    var currentIsInsert by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { ev ->
            when (ev) {
                is EditorEvent.ExternalInk -> view?.mergeExternal(ev.doc)
                is EditorEvent.SizesChanged -> view?.pageSizesChanged()
                is EditorEvent.NewSource -> view?.let { v -> val pos = v.positionSnapshot(); v.setDocument(ev.source, ev.ink); pos?.let { v.restorePosition(it) } }
                is EditorEvent.PdfUpdated -> {
                    if (snackbar.showSnackbar("This PDF changed on your drive", actionLabel = "Reload", duration = androidx.compose.material3.SnackbarDuration.Long) ==
                        androidx.compose.material3.SnackbarResult.ActionPerformed) vm.reloadPdf()
                }
                is EditorEvent.Message -> snackbar.showSnackbar(ev.text)
                is EditorEvent.Share -> shareFile(context, ev.file)
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) { vm.savePosition(view?.positionSnapshot()); vm.flushNow() } }
        lifecycleOwner.lifecycle.addObserver(obs)
        val window = (context as? ComponentActivity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        (context as? com.notesplus.app.MainActivity)?.setHighRefreshRate(true)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            (context as? com.notesplus.app.MainActivity)?.setHighRefreshRate(false)
            vm.savePosition(view?.positionSnapshot())
            vm.flushNow()
        }
    }

    val isHl = state.tool == DocumentView.Tool.HIGHLIGHTER
    val selectTool: (DocumentView.Tool) -> Unit = { t ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        showOptions = (t == state.tool && t != DocumentView.Tool.PAN && t != DocumentView.Tool.SHAPE) && !showOptions
        vm.setTool(t)
    }
    val selectColor: (Int) -> Unit = { c -> if (isHl) vm.setHighlighter(color = c) else vm.setPen(color = c) }
    val selectWidth: (Float) -> Unit = { w -> if (isHl) vm.setHighlighter(width = w) else vm.setPen(width = w) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_LAYOUT_MIN_WIDTH
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    navigationIcon = { IconButton(onClick = { vm.savePosition(view?.positionSnapshot()); vm.flushNow(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                    title = {
                        Column {
                            Text(state.note?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(
                                (state.note?.pathLabel ?: "") + " · ${state.pageCount} page${if (state.pageCount == 1) "" else "s"}" +
                                    if (state.zoom > 1.05f) " · ${(state.zoom * 100).toInt()}%" else "",
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        if (!wide) {
                            IconButton(onClick = { view?.undo() }, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
                            IconButton(onClick = { view?.redo() }, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }
                        }
                        IconButton(onClick = { vm.togglePages() }) {
                            Icon(Icons.AutoMirrored.Filled.ViewSidebar, contentDescription = "Pages", tint = if (state.showPages) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        SyncIndicator(state, onClick = { vm.syncNow() })
                        Box {
                            IconButton(onClick = { currentIsInsert = view?.isInsertedPage(state.currentPage) == true; menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Insert blank page above") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                                    onClick = { menu = false; view?.insertBlankPage(above = true, paper = state.insertPaper); vm.invalidatePageThumbnails() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Insert blank page below") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                                    onClick = { menu = false; view?.insertBlankPage(above = false, paper = state.insertPaper); vm.invalidatePageThumbnails() }
                                )
                                if (currentIsInsert) DropdownMenuItem(
                                    text = { Text("Remove this inserted page") }, leadingIcon = { Icon(Icons.Default.PlaylistRemove, null) },
                                    onClick = { menu = false; view?.removeInsertedPage(state.currentPage); vm.invalidatePageThumbnails() }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Go to page…") }, onClick = { menu = false; showJump = true })
                                DropdownMenuItem(text = { Text("Fit page") }, leadingIcon = { Icon(Icons.Default.FitScreen, null) }, onClick = { menu = false; view?.fitPage() })
                                DropdownMenuItem(text = { Text("Fit width") }, onClick = { menu = false; view?.fitWidth() })
                                DropdownMenuItem(text = { Text("Share annotated PDF") }, onClick = { menu = false; vm.export() })
                                DropdownMenuItem(
                                    text = { Text("Remove all annotations…") }, leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                                    enabled = state.strokeCount > 0, onClick = { menu = false; showClearAll = true }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(if (state.autoShape) "Shape snapping: on" else "Shape snapping: off") },
                                    leadingIcon = { Icon(Icons.Default.Category, null) },
                                    onClick = { menu = false; vm.setAutoShape(!state.autoShape) }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (state.palmGuard) "Palm guard: on (two-finger scroll)" else "Palm guard: off") },
                                    leadingIcon = { Icon(Icons.Default.FrontHand, null) },
                                    onClick = { menu = false; vm.setPalmGuard(!state.palmGuard) }
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
                    AnimatedVisibility(state.showPages && !state.loading, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                        PagesStrip(vm, state, onPage = { view?.scrollToPage(it) })
                    }
                    BottomToolBar(state, selectTool, selectColor, onShape = vm::setShapeKind, onMoreColors = { editSlot = null; showColorPicker = true }, onEditSlot = { editSlot = it; showColorPicker = true })
                }
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
                                        override fun onToolShortcut(tool: DocumentView.Tool) = vm.setTool(tool)
                                        override fun onSelectionChanged(selected: Int, canPaste: Boolean) = vm.onSelectionChanged(selected, canPaste)
                                    }
                                    vm.pageSource?.let { src -> v.post { v.setDocument(src, vm.initialInk); vm.initialPosition?.let { v.restorePosition(it) } } }
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
                                v.straightHighlighter = state.straightHighlighter
                                v.palmGuard = state.palmGuard
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        DisposableEffect(Unit) { onDispose { view?.release(); view = null } }

                        if (wide) {
                            val railAlign = if (state.railOnRight) Alignment.CenterEnd else Alignment.CenterStart
                            val panelAlign = if (state.railOnRight) Alignment.CenterStart else Alignment.CenterEnd
                            Box(Modifier.align(railAlign).padding(10.dp)) {
                                FloatingToolRail(state, view, selectTool, selectColor, selectWidth, onShape = vm::setShapeKind, onMoreColors = { editSlot = null; showColorPicker = true }, onEditSlot = { editSlot = it; showColorPicker = true })
                            }
                            PagesPanel(vm, state, visible = state.showPages, fromRight = !state.railOnRight, onPage = { view?.scrollToPage(it) }, modifier = Modifier.align(panelAlign))
                        }
                        SelectionBar(
                            state, visible = state.tool == DocumentView.Tool.LASSO && (state.selectionCount > 0 || state.canPaste),
                            onCopy = { view?.copySelection() }, onCut = { view?.cutSelection() }, onDelete = { view?.deleteSelection() },
                            onPaste = { view?.paste() }, onClear = { view?.clearSelection() },
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                        )
                        PagePill(state, onPrev = { view?.scrollToPage(state.currentPage - 1) }, onNext = { view?.scrollToPage(state.currentPage + 1) }, onJump = { showJump = true },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp))
                        OptionsPanel(state, vm, visible = showOptions, onClose = { showOptions = false }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
                    }
                }
            }
        }
    }

    if (showColorPicker) {
        ColorPickerDialog(
            title = if (isHl) "Highlighter colour" else "Pen colour",
            initial = editSlot?.let { (if (isHl) state.highlighterPalette else state.penPalette).getOrNull(it) } ?: if (isHl) state.highlighterColor else state.penColor,
            palette = if (isHl) state.highlighterPalette else state.penPalette,
            recent = state.recentColors,
            onPick = { c -> selectColor(c); vm.rememberColor(c); showColorPicker = false },
            onDismiss = { showColorPicker = false },
            initialEditSlot = editSlot,
            onReplaceSlot = { index, c -> vm.replacePaletteColor(isHl, index, c) },
            onResetPalette = { vm.resetPalette(isHl) }
        )
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
    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("Remove all annotations?") },
            text = { Text("Every stroke on “${state.note?.title}” will be erased, here and on your drive. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { vm.clearAllAnnotations(); showClearAll = false }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { showClearAll = false }) { Text("Cancel") } }
        )
    }
}

// ----------------------------------------------------------------------------- pieces

@Composable
private fun SyncIndicator(state: EditorState, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        when {
            state.sync is SyncStatus.Running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            state.unsaved || state.note?.let { it.inkDirty || it.writeBackPending } == true -> Icon(Icons.Default.CloudUpload, contentDescription = "Changes pending upload", tint = MaterialTheme.colorScheme.tertiary)
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

/** Rounded, animated tool button used on the rail and the bottom bar. */
@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "toolBg")
    val fg by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, label = "toolFg")
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = label, tint = fg) }
}

/** Shape tool button: tap selects the tool; tapping it while active opens the shape list. */
@Composable
private fun ShapeToolButton(state: EditorState, onSelect: (DocumentView.Tool) -> Unit, onShape: (ShapeKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(shapeIcon(state.shapeKind), "Shapes (${shapeLabel(state.shapeKind)})", state.tool == DocumentView.Tool.SHAPE) {
            if (state.tool == DocumentView.Tool.SHAPE) open = true else onSelect(DocumentView.Tool.SHAPE)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ShapeKind.entries.forEach { k ->
                DropdownMenuItem(text = { Text(shapeLabel(k)) }, leadingIcon = { Icon(shapeIcon(k), null) }, onClick = { open = false; onShape(k) })
            }
            HorizontalDivider()
            Text("Hold the pen's button (or Shift) while dragging for a square, circle or 45° line.",
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp).width(220.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WidthDot(width: Float, selected: Boolean, maxWidth: Float, onClick: () -> Unit) {
    val d = (7 + 13 * (width / maxWidth)).dp
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, label = "widthBg")
    Box(Modifier.size(36.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(d).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
    }
}

/** "+" swatch that opens the full colour picker. */
@Composable
private fun MoreColorsButton(onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).padding(2.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Default.Add, contentDescription = "More colours", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** The palette slots shown inline (index to colour), plus the current colour when it is not in the palette. */
private fun quickColors(palette: List<Int>, current: Int): List<Pair<Int, Int>> {
    val quick = palette.take(QUICK_COLORS).mapIndexed { i, c -> i to c }
    return if (quick.any { it.second == current } || palette.indexOf(current) >= 0) quick else listOf(-1 to current) + quick.dropLast(1)
}

/** Floating pill rail for tablets: everything reachable with the pen without leaving the page. */
@Composable
private fun FloatingToolRail(
    state: EditorState, view: DocumentView?,
    onTool: (DocumentView.Tool) -> Unit, onColor: (Int) -> Unit, onWidth: (Float) -> Unit,
    onShape: (ShapeKind) -> Unit, onMoreColors: () -> Unit, onEditSlot: (Int) -> Unit
) {
    Surface(shape = RoundedCornerShape(26.dp), tonalElevation = 4.dp, shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            Modifier.width(60.dp).verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(onClick = { view?.undo() }, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
            IconButton(onClick = { view?.redo() }, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }
            RailDivider()
            ToolButton(Icons.Default.Draw, "Pen (P)", state.tool == DocumentView.Tool.PEN) { onTool(DocumentView.Tool.PEN) }
            ToolButton(Icons.Default.BorderColor, "Highlighter (H)", state.tool == DocumentView.Tool.HIGHLIGHTER) { onTool(DocumentView.Tool.HIGHLIGHTER) }
            ToolButton(Icons.Default.AutoFixNormal, "Eraser (E)", state.tool == DocumentView.Tool.ERASER) { onTool(DocumentView.Tool.ERASER) }
            ShapeToolButton(state, onTool, onShape)
            ToolButton(Icons.Default.HighlightAlt, "Lasso select (L)", state.tool == DocumentView.Tool.LASSO) { onTool(DocumentView.Tool.LASSO) }
            ToolButton(Icons.Default.PanTool, "Pan / zoom (Space)", state.tool == DocumentView.Tool.PAN) { onTool(DocumentView.Tool.PAN) }
            val inkTool = state.tool == DocumentView.Tool.PEN || state.tool == DocumentView.Tool.HIGHLIGHTER || state.tool == DocumentView.Tool.SHAPE
            AnimatedVisibility(inkTool) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RailDivider()
                    val hl = state.tool == DocumentView.Tool.HIGHLIGHTER
                    val selected = if (hl) state.highlighterColor else state.penColor
                    quickColors(if (hl) state.highlighterPalette else state.penPalette, selected).forEach { (i, c) ->
                        ColorSwatch(c, c == selected, onLongClick = if (i >= 0) ({ onEditSlot(i) }) else null) { onColor(c) }
                    }
                    MoreColorsButton(onMoreColors)
                    RailDivider()
                    val widths = if (hl) HL_WIDTHS else PEN_WIDTHS
                    val cur = if (hl) state.highlighterWidth else state.penWidth
                    widths.forEach { w -> WidthDot(w, kotlin.math.abs(w - cur) < 0.05f, widths.last()) { onWidth(w) } }
                }
            }
            RailDivider()
            IconButton(onClick = { view?.fitPage() }) { Icon(Icons.Default.FitScreen, contentDescription = "Fit page") }
        }
    }
}

@Composable
private fun RailDivider() = HorizontalDivider(Modifier.padding(horizontal = 14.dp, vertical = 5.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun BottomToolBar(
    state: EditorState, onTool: (DocumentView.Tool) -> Unit, onColor: (Int) -> Unit,
    onShape: (ShapeKind) -> Unit, onMoreColors: () -> Unit, onEditSlot: (Int) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ToolButton(Icons.Default.Draw, "Pen", state.tool == DocumentView.Tool.PEN) { onTool(DocumentView.Tool.PEN) }
            ToolButton(Icons.Default.BorderColor, "Highlighter", state.tool == DocumentView.Tool.HIGHLIGHTER) { onTool(DocumentView.Tool.HIGHLIGHTER) }
            ToolButton(Icons.Default.AutoFixNormal, "Eraser", state.tool == DocumentView.Tool.ERASER) { onTool(DocumentView.Tool.ERASER) }
            ShapeToolButton(state, onTool, onShape)
            ToolButton(Icons.Default.HighlightAlt, "Lasso select", state.tool == DocumentView.Tool.LASSO) { onTool(DocumentView.Tool.LASSO) }
            ToolButton(Icons.Default.PanTool, "Pan / zoom", state.tool == DocumentView.Tool.PAN) { onTool(DocumentView.Tool.PAN) }
            val hl = state.tool == DocumentView.Tool.HIGHLIGHTER
            if (state.tool == DocumentView.Tool.PEN || hl || state.tool == DocumentView.Tool.SHAPE) {
                Spacer(Modifier.width(6.dp))
                val selected = if (hl) state.highlighterColor else state.penColor
                quickColors(if (hl) state.highlighterPalette else state.penPalette, selected).forEach { (i, c) ->
                    ColorSwatch(c, c == selected, onLongClick = if (i >= 0) ({ onEditSlot(i) }) else null) { onColor(c) }
                }
                MoreColorsButton(onMoreColors)
            }
        }
    }
}

/** Actions for the lasso selection: copy, cut, paste, delete. */
@Composable
private fun SelectionBar(
    state: EditorState, visible: Boolean,
    onCopy: () -> Unit, onCut: () -> Unit, onDelete: () -> Unit, onPaste: () -> Unit, onClear: () -> Unit,
    modifier: Modifier
) {
    AnimatedVisibility(visible, modifier = modifier, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
        Surface(shape = CircleShape, tonalElevation = 4.dp, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
                val has = state.selectionCount > 0
                Text(if (has) "${state.selectionCount} selected" else "Clipboard", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp))
                IconButton(onClick = onCopy, enabled = has) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy") }
                IconButton(onClick = onCut, enabled = has) { Icon(Icons.Default.ContentCut, contentDescription = "Cut") }
                IconButton(onClick = onPaste, enabled = state.canPaste) { Icon(Icons.Default.ContentPaste, contentDescription = "Paste") }
                IconButton(onClick = onDelete, enabled = has) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                if (has) IconButton(onClick = onClear) { Icon(Icons.Default.Close, contentDescription = "Deselect") }
            }
        }
    }
}

/** Compact "3 / 12" pill with previous/next, floating over the bottom of the canvas. */
@Composable
private fun PagePill(state: EditorState, onPrev: () -> Unit, onNext: () -> Unit, onJump: () -> Unit, modifier: Modifier) {
    Surface(modifier = modifier, shape = CircleShape, tonalElevation = 4.dp, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onPrev, enabled = state.currentPage > 0, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous page") }
            Text("${state.currentPage + 1} / ${state.pageCount}", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = onJump).padding(horizontal = 8.dp, vertical = 6.dp))
            IconButton(onClick = onNext, enabled = state.currentPage < state.pageCount - 1, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.ChevronRight, contentDescription = "Next page") }
        }
    }
}

@Composable
private fun OptionsPanel(state: EditorState, vm: EditorViewModel, visible: Boolean, onClose: () -> Unit, modifier: Modifier) {
    AnimatedVisibility(visible, modifier = modifier, enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
        Surface(modifier = Modifier.padding(12.dp).width(360.dp), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                when (state.tool) {
                    DocumentView.Tool.PEN -> {
                        Text("Pen width · ${"%.1f".format(state.penWidth)} pt", style = MaterialTheme.typography.labelLarge)
                        Slider(value = state.penWidth, onValueChange = { vm.setPen(width = it) }, valueRange = 0.6f..8f)
                    }
                    DocumentView.Tool.HIGHLIGHTER -> {
                        Text("Highlighter width · ${state.highlighterWidth.toInt()} pt", style = MaterialTheme.typography.labelLarge)
                        Slider(value = state.highlighterWidth, onValueChange = { vm.setHighlighter(width = it) }, valueRange = 6f..36f)
                    }
                    else -> {
                        Text("Eraser size · ${state.eraserRadius.toInt()} pt", style = MaterialTheme.typography.labelLarge)
                        Slider(value = state.eraserRadius, onValueChange = { vm.setEraserRadius(it) }, valueRange = 3f..40f)
                    }
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
}

/** Floating page navigator for tablets. */
@Composable
private fun BoxScope.PagesPanel(vm: EditorViewModel, state: EditorState, visible: Boolean, fromRight: Boolean, onPage: (Int) -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentPage, visible) {
        if (!visible) return@LaunchedEffect
        val shown = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (state.currentPage !in shown) listState.animateScrollToItem(state.currentPage)
    }
    AnimatedVisibility(
        visible, modifier = modifier.padding(10.dp).fillMaxHeight(),
        enter = slideInHorizontally { if (fromRight) it else -it } + fadeIn(), exit = slideOutHorizontally { if (fromRight) it else -it } + fadeOut()
    ) {
        Surface(shape = RoundedCornerShape(22.dp), tonalElevation = 4.dp, shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.width(150.dp)) {
            LazyColumn(state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.pageCount) { i -> PageThumb(vm, i, selected = i == state.currentPage, width = 118.dp) { onPage(i) } }
            }
        }
    }
}

/** Horizontal page strip for phones (above the bottom bar). */
@Composable
private fun PagesStrip(vm: EditorViewModel, state: EditorState, onPage: (Int) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentPage) {
        val shown = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (state.currentPage !in shown) listState.animateScrollToItem(state.currentPage)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyRow(state = listState, contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.pageCount) { i -> PageThumb(vm, i, selected = i == state.currentPage, width = 72.dp) { onPage(i) } }
        }
    }
}

@Composable
private fun PageThumb(vm: EditorViewModel, index: Int, selected: Boolean, width: Dp, onClick: () -> Unit) {
    var bmp by remember(index) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(index) { bmp = vm.pageThumbnail(index) }
    val ring by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color(0x22000000), label = "thumbRing")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(width).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Box(Modifier.width(width).clip(RoundedCornerShape(8.dp)).border(if (selected) 2.5.dp else 1.dp, ring, RoundedCornerShape(8.dp)).background(Color.White)) {
            val b = bmp
            if (b != null) Image(b.asImageBitmap(), contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxWidth())
            else Box(Modifier.fillMaxWidth().height(width * 1.3f))
        }
        Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
    }
}
