package com.notesplus.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notesplus.app.cloud.SyncStatus
import com.notesplus.app.cloud.auth.AuthState
import com.notesplus.app.data.db.CloudItemEntity
import com.notesplus.app.data.db.NoteEntity
import com.notesplus.app.data.db.Provider
import com.notesplus.app.ui.common.appViewModel
import com.notesplus.app.ui.common.formatRelative
import com.notesplus.app.ui.common.shareFile
import com.notesplus.app.util.Files

private enum class Tab(val label: String) { BROWSE("Browse"), RECENT("Recent") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenNote: (String) -> Unit, onOpenSettings: () -> Unit) {
    val vm = appViewModel { HomeViewModel(it) }
    val browse by vm.browse.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.BROWSE) }

    LaunchedEffect(Unit) {
        vm.events.collect { ev ->
            when (ev) {
                is HomeEvent.OpenNote -> onOpenNote(ev.noteId)
                is HomeEvent.Message -> snackbar.showSnackbar(ev.text)
                is HomeEvent.Share -> shareFile(context, ev.file)
                is HomeEvent.UpdateAvailable -> {
                    if (ev.autoInstalling) snackbar.showSnackbar("Installing NotesPlus ${ev.versionName}…")
                    else if (snackbar.showSnackbar("NotesPlus ${ev.versionName} is available", actionLabel = "Install") == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.installUpdate()
                }
            }
        }
    }
    BackHandler(enabled = tab == Tab.BROWSE && browse.location?.folderId != null) { vm.up() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    navigationIcon = {
                        if (tab == Tab.BROWSE && browse.location?.folderId != null) IconButton(onClick = { vm.up() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up") }
                    },
                    title = {
                        if (tab == Tab.BROWSE) {
                            val loc = browse.location
                            Column {
                                Text(browse.breadcrumb.lastOrNull()?.name ?: loc?.provider?.label ?: "NotesPlus", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                                if (loc != null && browse.breadcrumb.isNotEmpty()) Text(
                                    (listOf(loc.provider.label) + browse.breadcrumb.dropLast(1).map { it.name }).joinToString(" / "),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else Text("Recent", style = MaterialTheme.typography.titleLarge)
                    },
                    actions = {
                        if (tab == Tab.BROWSE && browse.location != null) IconButton(onClick = { vm.refresh() }, enabled = !browse.refreshing) {
                            if (browse.refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                        val running = recent.sync is SyncStatus.Running
                        IconButton(onClick = { vm.syncNow() }, enabled = !running) {
                            if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Sync, contentDescription = "Sync now")
                        }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!wide) NavigationBar {
                    NavigationBarItem(selected = tab == Tab.BROWSE, onClick = { tab = Tab.BROWSE }, icon = { Icon(Icons.Default.Cloud, null) }, label = { Text("Browse") })
                    NavigationBarItem(selected = tab == Tab.RECENT, onClick = { tab = Tab.RECENT }, icon = { Icon(Icons.Default.History, null) }, label = { Text("Recent") })
                }
            }
        ) { padding ->
            Row(Modifier.padding(padding).fillMaxSize()) {
                if (wide) NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Spacer(Modifier.height(8.dp))
                    NavigationRailItem(selected = tab == Tab.BROWSE, onClick = { tab = Tab.BROWSE }, icon = { Icon(Icons.Default.Cloud, null) }, label = { Text("Browse") })
                    NavigationRailItem(selected = tab == Tab.RECENT, onClick = { tab = Tab.RECENT }, icon = { Icon(Icons.Default.History, null) }, label = { Text("Recent") })
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (tab) {
                        Tab.BROWSE -> BrowsePane(browse, vm, wide, onOpenSettings)
                        Tab.RECENT -> RecentPane(recent, vm, wide, onOpenSettings)
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------- browse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowsePane(state: BrowseState, vm: HomeViewModel, wide: Boolean, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val signedIn = Provider.entries.filter { state.signedIn[it] is AuthState.SignedIn }
    Column(Modifier.fillMaxSize()) {
        if (signedIn.size > 1 || (signedIn.size == 1 && state.location != null)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                signedIn.forEach { p ->
                    FilterChip(selected = state.location?.provider == p, onClick = { vm.switchProvider(p) }, label = { Text(p.label) }, leadingIcon = { Icon(Icons.Default.Cloud, null, Modifier.size(16.dp)) })
                }
            }
        }
        when {
            signedIn.isEmpty() -> ConnectPrompt(onSignIn = { vm.signIn(it, context) }, onOpenSettings = onOpenSettings)
            state.location == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                if (state.error != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudOff, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Showing cached contents · ${state.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
                    if (state.items.isEmpty() && !state.refreshing) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No folders or PDFs here", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (wide) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(200.dp), contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()
                        ) {
                            items(state.items, key = { it.id }) { item -> ItemCard(item, state.notesByRemote[item.id], opening = state.opening == item.id) { vm.open(item) } }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                            listItems(state.items, key = { it.id }) { item -> ItemRow(item, state.notesByRemote[item.id], opening = state.opening == item.id) { vm.open(item) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectPrompt(onSignIn: (Provider) -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Cloud, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Connect a drive", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Browse your lecture notes on OneDrive or Google Drive, annotate them with your pen, and have the annotations sync back automatically.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onSignIn(Provider.ONEDRIVE) }) { Text("OneDrive") }
            Button(onClick = { onSignIn(Provider.GOOGLE_DRIVE) }) { Text("Google Drive") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenSettings) { Text("Set up client IDs") }
    }
}

@Composable
private fun StatusBadge(note: NoteEntity?) {
    if (note == null) return
    val (icon, tint) = when {
        note.syncError != null -> Icons.Default.Warning to MaterialTheme.colorScheme.error
        note.inkDirty || note.writeBackPending -> Icons.Default.CloudUpload to MaterialTheme.colorScheme.tertiary
        else -> Icons.Default.CloudDone to MaterialTheme.colorScheme.primary
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
}

@Composable
private fun ItemRow(item: CloudItemEntity, note: NoteEntity?, opening: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (!item.isFolder) Text(listOfNotNull(Files.humanSize(item.size), item.modifiedAt.takeIf { it > 0 }?.let { formatRelative(it) }, note?.let { "on this device" }).joinToString(" · "))
        },
        leadingContent = {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (item.isFolder) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(if (item.isFolder) Icons.Default.Folder else Icons.Default.PictureAsPdf, null, tint = if (item.isFolder) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer)
            }
        },
        trailingContent = { if (opening) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else StatusBadge(note) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun ItemCard(item: CloudItemEntity, note: NoteEntity?, opening: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), elevation = CardDefaults.cardElevation(0.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (item.isFolder) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                if (opening) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(if (item.isFolder) Icons.Default.Folder else Icons.Default.PictureAsPdf, null, tint = if (item.isFolder) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!item.isFolder) Text(
                    listOfNotNull(Files.humanSize(item.size), note?.let { "on this device" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
            StatusBadge(note)
        }
    }
}

// --------------------------------------------------------------------------- recent

@Composable
private fun RecentPane(state: RecentState, vm: HomeViewModel, wide: Boolean, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SyncBanner(state, onSync = { vm.syncNow() })
        if (state.notes.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.Draw, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Nothing opened yet", style = MaterialTheme.typography.titleLarge)
                Text("PDFs you open from Browse appear here and stay available offline.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (wide) 188.dp else 160.dp),
                contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.notes, key = { it.id }) { note -> NoteCard(note, vm) }
            }
        }
    }
}

@Composable
private fun SyncBanner(state: RecentState, onSync: () -> Unit) {
    val (icon, text, tint) = when {
        state.sync is SyncStatus.Running -> Triple(Icons.Default.Sync, (state.sync as SyncStatus.Running).message, MaterialTheme.colorScheme.primary)
        state.sync is SyncStatus.Failed -> Triple(Icons.Default.Warning, "Last sync failed: ${(state.sync as SyncStatus.Failed).message}", MaterialTheme.colorScheme.error)
        state.dirtyCount > 0 -> Triple(Icons.Default.CloudUpload, "${state.dirtyCount} note${if (state.dirtyCount == 1) "" else "s"} waiting to upload", MaterialTheme.colorScheme.tertiary)
        else -> Triple(Icons.Default.CloudDone, "Synced · ${formatRelative(state.lastSyncAt)}", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp))
            .background(if (state.sync is SyncStatus.Failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onSync).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NoteCard(note: NoteEntity, vm: HomeViewModel) {
    var thumb by remember(note.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(note.id, note.modifiedAt) { thumb = vm.thumbnails.load(note) }
    var menu by remember { mutableStateOf(false) }
    Card(onClick = { vm.openRecent(note) }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), elevation = CardDefaults.cardElevation(0.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.8f).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            val b = thumb
            if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alignment = Alignment.TopCenter)
            else Icon(Icons.Default.PictureAsPdf, null, Modifier.align(Alignment.Center).size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            AssistChip(onClick = { vm.openRecent(note) }, label = { Text(note.provider.label, style = MaterialTheme.typography.labelSmall) }, border = null,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).height(26.dp))
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Share annotated PDF") }, onClick = { menu = false; vm.export(note) })
                    DropdownMenuItem(text = { Text("Remove from device") }, leadingIcon = { Icon(Icons.Default.CloudDownload, null) }, onClick = { menu = false; vm.removeLocal(note) })
                }
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) { StatusBadge(note) }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(note.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(note.pathLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${note.pageCount} pages · ${formatRelative(note.lastOpenedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
