package com.notesplus.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notesplus.app.BuildConfig
import com.notesplus.app.onedrive.SyncStatus
import com.notesplus.app.onedrive.auth.AuthState
import com.notesplus.app.ui.common.appViewModel
import com.notesplus.app.ui.common.formatRelative

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val browser by vm.browser.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("OneDrive account")
            when (val a = ui.auth) {
                is AuthState.SignedIn -> {
                    ListItem(headlineContent = { Text(a.accountName) }, supportingContent = { Text("Signed in") },
                        trailingContent = { OutlinedButton(onClick = { vm.signOut() }) { Text("Sign out") } })
                }
                is AuthState.SigningIn -> ListItem(headlineContent = { Text("Waiting for sign-in…") }, trailingContent = { CircularProgressIndicator(Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp) })
                is AuthState.Error -> {
                    ListItem(headlineContent = { Text("Sign-in failed", color = MaterialTheme.colorScheme.error) }, supportingContent = { Text(a.message) },
                        trailingContent = { Button(onClick = { vm.signIn(context) }) { Text("Retry") } })
                }
                AuthState.SignedOut -> ListItem(
                    headlineContent = { Text("Not signed in") },
                    supportingContent = { Text("Sign in with your Microsoft account to sync notes and annotations.") },
                    trailingContent = { Button(onClick = { vm.signIn(context) }, enabled = ui.settings.effectiveClientId.isNotBlank()) { Text("Sign in") } }
                )
            }
            ClientIdEditor(current = ui.settings.clientIdOverride, builtIn = BuildConfig.ONEDRIVE_CLIENT_ID, onSave = vm::saveClientId)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Sync")
            ListItem(
                headlineContent = { Text("OneDrive folder") },
                supportingContent = { Text(ui.settings.syncFolderPath + if (ui.settings.syncFolderId == null) "  (created on first sync)" else "") },
                trailingContent = { TextButton(onClick = { vm.openBrowser() }, enabled = ui.auth is AuthState.SignedIn) { Text("Change") } }
            )
            SwitchRow("Sync automatically", "Upload edits a few seconds after you stop writing and check for changes every 15 minutes", ui.settings.autoSync, vm::setAutoSync)
            SwitchRow("Sync on mobile data", "Turn off to sync only on Wi-Fi", ui.settings.syncOnMeteredNetworks, vm::setMetered)
            SwitchRow("Also upload an annotated PDF", "Keeps a “<name>.annotated.pdf” next to each note so annotations are visible in any PDF viewer", ui.settings.exportAnnotatedPdfOnSync, vm::setExportOnSync)
            ListItem(
                headlineContent = { Text("Last sync") },
                supportingContent = {
                    val s = ui.sync
                    Text(when (s) { is SyncStatus.Running -> s.message; is SyncStatus.Failed -> "Failed: ${s.message}"; else -> "${formatRelative(ui.settings.lastSyncAt)} · ${ui.settings.lastSyncStatus.ifBlank { "—" }}" })
                },
                trailingContent = { TextButton(onClick = { vm.syncNow() }, enabled = ui.auth is AuthState.SignedIn && ui.sync !is SyncStatus.Running) { Text("Sync now") } }
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Pen & touch")
            SwitchRow("Draw with finger", "When off, fingers only scroll and zoom (recommended with a stylus)", ui.settings.fingerDrawing, vm::setFingerDrawing)
            SwitchRow("Stylus button erases", "Hold the pen's side button to erase", ui.settings.stylusButtonErases, vm::setStylusButtonErases)
            SwitchRow("Snap shapes when you pause", "Hold the pen still at the end of a stroke to turn it into a straight line, rectangle, circle or triangle", ui.settings.autoShape, vm::setAutoShape)
            SwitchRow("Predict pen movement", "Draws a short predicted tail so ink keeps up with the Magic-Pencil; turn off if strokes overshoot", ui.settings.motionPrediction, vm::setMotionPrediction)
            SwitchRow("Tool rail on the right", "For left-handed use on tablets", ui.settings.railOnRight, vm::setRailOnRight)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Sync log")
            if (ui.log.isEmpty()) Text("Nothing yet", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            ui.log.takeLast(30).reversed().forEach { line ->
                Text(line, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(24.dp))
            Text("NotesPlus ${BuildConfig.VERSION_NAME}", Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    browser?.let { b -> FolderPickerDialog(b, vm) }
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) }
    )
}

@Composable
private fun ClientIdEditor(current: String, builtIn: String, onSave: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Azure app (client) ID") },
            placeholder = { Text(if (builtIn.isNotBlank()) "Built in: ${builtIn.take(8)}…" else "e.g. 3f2a1c7e-…") },
            supportingText = {
                Text(
                    if (builtIn.isBlank() && current.isBlank()) "Required once. Register a free app at portal.azure.com (see README) and paste its Application (client) ID here."
                    else "Leave empty to use the ID compiled into the app."
                )
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onSave(text) }, enabled = text.trim() != current) { Text("Save") }
        }
    }
}

@Composable
private fun FolderPickerDialog(b: FolderBrowser, vm: SettingsViewModel) {
    var newFolder by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { vm.closeBrowser() },
        title = { Text("Choose OneDrive folder") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.up() }, enabled = b.path.isNotEmpty()) { Text("↑ Up") }
                    Text(b.currentPath, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                when {
                    b.loading -> CircularProgressIndicator(Modifier.padding(16.dp))
                    b.error != null -> Text(b.error, color = MaterialTheme.colorScheme.error)
                    else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        if (b.folders.isEmpty()) item { Text("No subfolders", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
                        items(b.folders, key = { it.id }) { f ->
                            ListItem(
                                headlineContent = { Text(f.name) },
                                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                                modifier = Modifier.clickable { vm.enter(f) }
                            )
                        }
                    }
                }
                TextButton(onClick = { newFolder = "" }) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text("New folder") }
            }
        },
        confirmButton = { TextButton(onClick = { vm.useCurrentFolder() }, enabled = b.path.isNotEmpty() && !b.loading) { Text("Use this folder") } },
        dismissButton = { TextButton(onClick = { vm.closeBrowser() }) { Text("Cancel") } }
    )
    newFolder?.let { name ->
        AlertDialog(
            onDismissRequest = { newFolder = null },
            title = { Text("New folder") },
            text = { OutlinedTextField(value = name, onValueChange = { newFolder = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) vm.createFolder(name.trim()); newFolder = null }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newFolder = null }) { Text("Cancel") } }
        )
    }
}
