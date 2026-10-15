package com.notesplus.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.notesplus.app.ui.editor.EditorScreen
import com.notesplus.app.ui.library.LibraryScreen
import com.notesplus.app.ui.settings.SettingsScreen
import com.notesplus.app.ui.theme.NotesPlusTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** A PDF handed to us by another app (open-with / share). Consumed by the library screen. */
    private val incomingPdf = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            NotesPlusTheme {
                val pending by incomingPdf.collectAsState()
                AppNav(pendingImport = pending, onImportConsumed = { incomingPdf.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val app = application as NotesPlusApp
        app.container.authManager.cancelPendingSignIn()
        // Pull remote changes whenever the app comes to the foreground.
        if (app.container.authManager.isSignedIn) app.appScope.launch { app.container.syncScheduler.requestSync(delayMs = 0L) }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        if (uri != null) incomingPdf.value = uri
    }
}

object Routes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val EDITOR = "editor/{noteId}"
    fun editor(noteId: String) = "editor/$noteId"
}

@Composable
fun AppNav(pendingImport: Uri?, onImportConsumed: () -> Unit) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                pendingImport = pendingImport,
                onImportConsumed = onImportConsumed,
                onOpenNote = { nav.navigate(Routes.editor(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.EDITOR, arguments = listOf(navArgument("noteId") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("noteId") ?: return@composable
            EditorScreen(noteId = id, onBack = { nav.popBackStack() })
        }
    }
}
