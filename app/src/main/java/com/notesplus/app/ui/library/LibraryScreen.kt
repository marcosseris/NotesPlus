package com.notesplus.app.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notesplus.app.data.db.NoteEntity
import com.notesplus.app.data.db.NoteKind
import com.notesplus.app.ink.PaperStyle
import com.notesplus.app.onedrive.SyncStatus
import com.notesplus.app.onedrive.auth.AuthState
import com.notesplus.app.ui.common.appViewModel
import com.notesplus.app.ui.common.formatRelative
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    pendingImport: Uri?,
    onImportConsumed: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val vm = appViewModel { LibraryViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(pendingImport) {
        val uri = pendingImport ?: return@LaunchedEffect
        onImportConsumed()
        vm.importPdf(uri) { id -> if (id != null) onOpenNote(id) }
    }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPdf(uri) { id -> if (id != null) onOpenNote(id) }
    }

    var showNewNotebook by remember { mutableStateOf(false) }
    var fabOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<NoteEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<NoteEntity?>(null) }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                title = { Text("Your notes", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    var sortMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            DropdownMenuItem(text = { Text(if (state.sort == LibrarySort.RECENT) "✓ Recently edited" else "Recently edited") }, onClick = { vm.setSort(LibrarySort.RECENT); sortMenu = false })
                            DropdownMenuItem(text = { Text(if (state.sort == LibrarySort.TITLE) "✓ Title" else "Title") }, onClick = { vm.setSort(LibrarySort.TITLE); sortMenu = false })
                        }
                    }
                    val running = state.sync is SyncStatus.Running
                    IconButton(onClick = { vm.syncNow() }, enabled = !running) {
                        if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Sync, contentDescription = "Sync now")
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (fabOpen) {
                    SmallFabRow("Import PDF", Icons.Default.UploadFile) { fabOpen = false; pickPdf.launch(arrayOf("application/pdf")) }
                    SmallFabRow("New notebook", Icons.Default.Edit) { fabOpen = false; showNewNotebook = true }
                }
                ExtendedFloatingActionButton(
                    onClick = { fabOpen = !fabOpen },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(if (fabOpen) "Close" else "New") }
                )
            }
        }
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            Row(Modifier.fillMaxSize()) {
                if (wide) FolderPane(state, onFilter = vm::setFilter, modifier = Modifier.width(236.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
            SyncBanner(state, onOpenSettings, onSync = { vm.syncNow() })
            OutlinedTextField(
                value = state.query, onValueChange = vm::setQuery, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Search notes") },
                shape = CircleShape,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedBorderColor = Color.Transparent, focusedBorderColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            )
            if (!wide) FilterChips(state, onFilter = vm::setFilter)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.notes.isEmpty()) {
                EmptyLibrary(onImport = { pickPdf.launch(arrayOf("application/pdf")) }, onNew = { showNewNotebook = true })
            } else if (state.filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing here matches", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (wide) 188.dp else 160.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.filtered, key = { it.id }) { note ->
                        NoteCard(
                            note = note, vm = vm,
                            onOpen = { onOpenNote(note.id) },
                            onRename = { renameTarget = note },
                            onDelete = { deleteTarget = note },
                            onExport = {
                                vm.export(note) { file -> shareFile(context, file) }
                            }
                        )
                    }
                }
            }
                }
            }
        }
    }

    if (showNewNotebook) {
        NewNotebookDialog(onDismiss = { showNewNotebook = false }) { title, paper ->
            showNewNotebook = false
            vm.createNotebook(title, paper) { id -> onOpenNote(id) }
        }
    }
    renameTarget?.let { note ->
        var text by remember(note.id) { mutableStateOf(note.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Title") }) },
            confirmButton = { TextButton(onClick = { vm.rename(note.id, text); renameTarget = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }
    deleteTarget?.let { note ->
        val synced = note.pdfRemoteId != null || note.inkRemoteId != null
        var alsoRemote by remember(note.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete “${note.title}”?") },
            text = {
                Column {
                    Text(if (synced) "The note will be removed from this device." else "This note only exists on this device and will be permanently deleted.")
                    if (synced) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Checkbox(checked = alsoRemote, onCheckedChange = { alsoRemote = it })
                        Text("Also delete from OneDrive")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.delete(note.id, alsoRemote); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

private fun filterLabel(f: LibraryFilter) = when (f) {
    LibraryFilter.All -> "All notes"; LibraryFilter.Notebooks -> "Notebooks"; LibraryFilter.Pdfs -> "PDFs"
    is LibraryFilter.Folder -> f.dir.substringAfterLast('/')
}

/** Left pane on tablets: quick filters plus the OneDrive subfolders found in the library. */
@Composable
private fun FolderPane(state: LibraryState, onFilter: (LibraryFilter) -> Unit, modifier: Modifier) {
    Surface(tonalElevation = 1.dp, modifier = modifier) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
            val colors = NavigationDrawerItemDefaults.colors()
            NavigationDrawerItem(label = { Text("All notes") }, icon = { Icon(Icons.AutoMirrored.Filled.Notes, null) }, selected = state.filter == LibraryFilter.All, onClick = { onFilter(LibraryFilter.All) }, colors = colors)
            NavigationDrawerItem(label = { Text("PDFs") }, icon = { Icon(Icons.Default.PictureAsPdf, null) }, selected = state.filter == LibraryFilter.Pdfs, onClick = { onFilter(LibraryFilter.Pdfs) }, colors = colors)
            NavigationDrawerItem(label = { Text("Notebooks") }, icon = { Icon(Icons.Default.Edit, null) }, selected = state.filter == LibraryFilter.Notebooks, onClick = { onFilter(LibraryFilter.Notebooks) }, colors = colors)
            if (state.folders.isNotEmpty()) {
                Text("Folders", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                state.folders.forEach { dir ->
                    val depth = dir.count { it == '/' }
                    val selected = (state.filter as? LibraryFilter.Folder)?.dir == dir
                    NavigationDrawerItem(
                        label = { Text(dir.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        icon = { Icon(if (selected) Icons.Default.FolderOpen else Icons.Default.Folder, null) },
                        selected = selected, onClick = { onFilter(LibraryFilter.Folder(dir)) }, colors = colors,
                        modifier = Modifier.padding(start = (depth * 12).dp)
                    )
                }
            }
        }
    }
}

/** Compact alternative to [FolderPane] on narrow screens. */
@Composable
private fun FilterChips(state: LibraryState, onFilter: (LibraryFilter) -> Unit) {
    val filters = listOf(LibraryFilter.All, LibraryFilter.Pdfs, LibraryFilter.Notebooks) + state.folders.map { LibraryFilter.Folder(it) }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listItems(filters) { f ->
            FilterChip(selected = state.filter == f, onClick = { onFilter(f) }, label = { Text(filterLabel(f)) },
                leadingIcon = if (f is LibraryFilter.Folder) ({ Icon(Icons.Default.Folder, null, Modifier.size(16.dp)) }) else null)
        }
    }
}

@Composable
private fun SmallFabRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 2.dp, shadowElevation = 2.dp) {
            Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(12.dp))
        SmallFloatingActionButton(onClick = onClick) { Icon(icon, contentDescription = label) }
    }
}

@Composable
private fun SyncBanner(state: LibraryState, onOpenSettings: () -> Unit, onSync: () -> Unit) {
    val (icon, text, tint) = when {
        state.auth !is AuthState.SignedIn -> Triple(Icons.Default.CloudOff, "Not connected to OneDrive. Sign in to sync your notes.", MaterialTheme.colorScheme.error)
        state.sync is SyncStatus.Running -> Triple(Icons.Default.Sync, (state.sync as SyncStatus.Running).message, MaterialTheme.colorScheme.primary)
        state.sync is SyncStatus.Failed -> Triple(Icons.Default.Warning, "Last sync failed: ${(state.sync as SyncStatus.Failed).message}", MaterialTheme.colorScheme.error)
        state.dirtyCount > 0 -> Triple(Icons.Default.CloudUpload, "${state.dirtyCount} note${if (state.dirtyCount == 1) "" else "s"} waiting to upload", MaterialTheme.colorScheme.tertiary)
        else -> Triple(Icons.Default.CloudDone, "Synced with OneDrive · ${formatRelative(state.lastSyncAt)}", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (state.auth !is AuthState.SignedIn || state.sync is SyncStatus.Failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { if (state.auth !is AuthState.SignedIn) onOpenSettings() else onSync() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit, onNew: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("No notes yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Import a lecture PDF to annotate it with your stylus, or start a blank notebook. Sign in to OneDrive in Settings and your folder's PDFs appear here automatically.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onImport) { Text("Import PDF") }
            Button(onClick = onNew) { Text("New notebook") }
        }
    }
}

@Composable
private fun NoteCard(
    note: NoteEntity, vm: LibraryViewModel,
    onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit
) {
    var thumb by remember(note.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(note.id, note.modifiedAt) { thumb = vm.thumbnails.load(note) }
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 2.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.8f).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            val bmp = thumb
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alignment = Alignment.TopCenter)
            } else {
                Icon(
                    if (note.kind == NoteKind.PDF) Icons.Default.PictureAsPdf else Icons.Default.Edit,
                    contentDescription = null, modifier = Modifier.align(Alignment.Center).size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Soft scrim so the kind badge stays readable over any page.
            Box(Modifier.fillMaxWidth().height(56.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent))))
            AssistChip(
                onClick = onOpen,
                label = { Text(if (note.kind == NoteKind.PDF) "PDF" else "Notebook", style = MaterialTheme.typography.labelSmall) },
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                border = null,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).height(26.dp)
            )
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Export annotated PDF") }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
            val statusIcon = when {
                note.syncError != null -> Icons.Default.Warning
                note.needsUpload -> Icons.Default.CloudUpload
                note.pdfRemoteId != null || note.inkRemoteId != null -> Icons.Default.CloudDone
                else -> null
            }
            if (statusIcon != null) Icon(
                statusIcon, contentDescription = null, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(18.dp),
                tint = if (note.syncError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(note.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = buildString {
                append("${note.pageCount} page${if (note.pageCount == 1) "" else "s"}")
                if (note.relativeDir.isNotEmpty()) append(" · ${note.relativeDir}")
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatRelative(note.modifiedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NewNotebookDialog(onDismiss: () -> Unit, onCreate: (String, PaperStyle) -> Unit) {
    var title by remember { mutableStateOf("") }
    var paper by remember { mutableStateOf(PaperStyle.RULED) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New notebook") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                Text("Paper", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperStyle.entries.forEach { p ->
                        FilterChip(selected = paper == p, onClick = { paper = p }, label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(title, paper) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

fun shareFile(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share annotated PDF"))
}
