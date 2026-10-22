package com.notesplus.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.RadioButton
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
import com.notesplus.app.cloud.SyncStatus
import com.notesplus.app.cloud.auth.AuthState
import com.notesplus.app.data.WriteBackMode
import com.notesplus.app.data.db.Provider
import com.notesplus.app.update.UpdateState
import androidx.compose.material3.LinearProgressIndicator
import com.notesplus.app.ui.common.appViewModel
import com.notesplus.app.ui.common.formatRelative

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("Accounts")
            Provider.entries.forEach { p ->
                AccountRow(p, ui.auth[p] ?: AuthState.SignedOut, clientIdPresent = ui.settings.clientId(p).isNotBlank(),
                    onSignIn = { vm.signIn(p, context) }, onSignOut = { vm.signOut(p) })
                ClientIdEditor(
                    label = "${p.label} client ID",
                    current = if (p == Provider.ONEDRIVE) ui.settings.oneDriveClientIdOverride else ui.settings.googleClientIdOverride,
                    builtIn = if (p == Provider.ONEDRIVE) BuildConfig.ONEDRIVE_CLIENT_ID else BuildConfig.GOOGLE_CLIENT_ID,
                    hint = if (p == Provider.ONEDRIVE) "Azure app registration → Application (client) ID" else "Google Cloud → OAuth client (Android) → Client ID",
                    onSave = { vm.saveClientId(p, it) }
                )
                if (p == Provider.ONEDRIVE) HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Sync")
            SwitchRow("Sync automatically", "Upload annotations a few seconds after you stop writing and check for changes every 15 minutes", ui.settings.autoSync, vm::setAutoSync)
            SwitchRow("Pre-download on Wi-Fi", "Downloads up to 15 PDFs (40 MB each) of the folder you're browsing so they open instantly and work offline", ui.settings.prefetch, vm::setPrefetch)
            SwitchRow("Sync on mobile data", "Turn off to sync only on Wi-Fi", ui.settings.syncOnMeteredNetworks, vm::setMetered)
            Text("Show annotations on other devices", Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp), style = MaterialTheme.typography.titleSmall)
            Text("Your pen strokes always sync as a small file NotesPlus understands. This controls whether they are also baked into a PDF that any viewer can show.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            WriteBackMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().clickable { vm.setWriteBack(mode) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = ui.settings.writeBack == mode, onClick = { vm.setWriteBack(mode) })
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                        Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            ListItem(
                headlineContent = { Text("Last sync") },
                supportingContent = {
                    val s = ui.sync
                    Text(when (s) { is SyncStatus.Running -> s.message; is SyncStatus.Failed -> "Failed: ${s.message}"; else -> "${formatRelative(ui.settings.lastSyncAt)} · ${ui.settings.lastSyncStatus.ifBlank { "—" }}" })
                },
                trailingContent = { TextButton(onClick = { vm.syncNow() }, enabled = ui.auth.values.any { it is AuthState.SignedIn } && ui.sync !is SyncStatus.Running) { Text("Sync now") } }
            )
            ListItem(headlineContent = { Text("Folder cache") }, supportingContent = { Text("Cached folder listings make browsing instant and work offline") },
                trailingContent = { TextButton(onClick = { vm.clearCache() }) { Text("Clear") } })

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Pen & touch")
            SwitchRow("Palm guard", "One finger never moves the page or draws; scroll and zoom with two fingers. Large contacts and touches near the pen are ignored either way.", ui.settings.palmGuard, vm::setPalmGuard)
            SwitchRow("Draw with finger", "When off, fingers only scroll and zoom (recommended with a stylus)", ui.settings.fingerDrawing, vm::setFingerDrawing)
            SwitchRow("Stylus button erases", "Hold the pen's side button to erase", ui.settings.stylusButtonErases, vm::setStylusButtonErases)
            SwitchRow("Snap shapes when you pause", "Hold the pen still at the end of a stroke to turn it into a straight line, rectangle, circle or triangle", ui.settings.autoShape, vm::setAutoShape)
            SwitchRow("Straight highlighter", "Roughly straight highlighter strokes become ruler-straight bars when you lift the pen", ui.settings.straightHighlighter, vm::setStraightHighlighter)
            SwitchRow("Predict pen movement", "Draws a short predicted tail so ink keeps up with the pen; turn off if strokes overshoot", ui.settings.motionPrediction, vm::setMotionPrediction)
            SwitchRow("Tool rail on the right", "For left-handed use on tablets", ui.settings.railOnRight, vm::setRailOnRight)
            ListItem(
                headlineContent = { Text("Colour palettes") },
                supportingContent = { Text("Long-press a swatch in the editor to change it. Reset restores the built-in colours.") },
                trailingContent = { TextButton(onClick = { vm.resetPalettes() }) { Text("Reset") } }
            )
            Text("Paper for inserted pages", Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.notesplus.app.ink.PaperStyle.entries.forEach { p ->
                    androidx.compose.material3.FilterChip(selected = ui.settings.insertPaper == p, onClick = { vm.setInsertPaper(p) }, label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section("Updates")
            if (!vm.updatesSupported) {
                Text("Automatic updates work in release builds installed from GitHub Releases (this is a debug build).", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SwitchRow("Update automatically", "Check GitHub for new builds when the app opens and install them", ui.settings.autoUpdate, vm::setAutoUpdate)
                val u = ui.update
                ListItem(
                    headlineContent = { Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})") },
                    supportingContent = {
                        when (u) {
                            is UpdateState.Checking -> Text("Checking…")
                            is UpdateState.UpToDate -> Text("Up to date · last checked ${formatRelative(ui.settings.lastUpdateCheck)}")
                            is UpdateState.Available -> Text("${u.release.versionName} is available")
                            is UpdateState.Downloading -> Column { Text("Downloading ${u.release.versionName}…"); LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) }
                            is UpdateState.Installing -> Text("Installing…")
                            is UpdateState.Error -> Text(u.message, color = MaterialTheme.colorScheme.error)
                            UpdateState.Idle -> Text("Last checked ${formatRelative(ui.settings.lastUpdateCheck)}")
                        }
                    },
                    trailingContent = {
                        when (u) {
                            is UpdateState.Available -> Button(onClick = { vm.installUpdate() }) { Text("Install") }
                            is UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Installing -> CircularProgressIndicator(Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
                            else -> TextButton(onClick = { vm.checkForUpdates() }) { Text("Check now") }
                        }
                    }
                )
                GithubTokenEditor(present = ui.hasGithubToken, onSave = vm::saveGithubToken)
            }

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
}

@Composable
private fun AccountRow(p: Provider, state: AuthState, clientIdPresent: Boolean, onSignIn: () -> Unit, onSignOut: () -> Unit) {
    when (state) {
        is AuthState.SignedIn -> ListItem(headlineContent = { Text(p.label) }, supportingContent = { Text(state.accountName) },
            trailingContent = { OutlinedButton(onClick = onSignOut) { Text("Sign out") } })
        is AuthState.SigningIn -> ListItem(headlineContent = { Text(p.label) }, supportingContent = { Text("Waiting for sign-in…") },
            trailingContent = { CircularProgressIndicator(Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp) })
        is AuthState.Error -> ListItem(headlineContent = { Text(p.label, color = MaterialTheme.colorScheme.error) }, supportingContent = { Text(state.message) },
            trailingContent = { Button(onClick = onSignIn) { Text("Retry") } })
        AuthState.SignedOut -> ListItem(headlineContent = { Text(p.label) },
            supportingContent = { Text(if (clientIdPresent) "Not signed in" else "Add the client ID below first") },
            trailingContent = { Button(onClick = onSignIn, enabled = clientIdPresent) { Text("Sign in") } })
    }
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) }, modifier = Modifier.clickable { onChange(!checked) })
}

@Composable
private fun GithubTokenEditor(present: Boolean, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("GitHub token (private repository)") },
            placeholder = { Text(if (present) "Token saved · paste a new one to replace it" else "github_pat_…") },
            supportingText = { Text("Fine-grained token with read-only Contents access to marcosseris/NotesPlus. Stored encrypted on this device.") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (present) TextButton(onClick = { onSave(""); text = "" }) { Text("Remove") }
            TextButton(onClick = { onSave(text); text = "" }, enabled = text.isNotBlank()) { Text("Save") }
        }
    }
}

@Composable
private fun ClientIdEditor(label: String, current: String, builtIn: String, hint: String, onSave: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            placeholder = { Text(if (builtIn.isNotBlank()) "Built in: ${builtIn.take(8)}…" else hint) },
            supportingText = { Text(if (builtIn.isBlank() && current.isBlank()) "Required once; see the README for the 3-minute setup." else "Leave empty to use the ID compiled into the app.") }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onSave(text) }, enabled = text.trim() != current) { Text("Save") }
        }
    }
}
