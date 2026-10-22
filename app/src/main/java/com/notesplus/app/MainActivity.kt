package com.notesplus.app

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.notesplus.app.ui.editor.EditorScreen
import com.notesplus.app.ui.home.HomeScreen
import com.notesplus.app.ui.settings.SettingsScreen
import com.notesplus.app.ui.theme.NotesPlusTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NotesPlusTheme { AppNav() } }
    }

    override fun onResume() {
        super.onResume()
        val app = application as NotesPlusApp
        app.container.authManagers.forEach { it.cancelPendingSignIn() }
        // Pull remote changes whenever the app comes to the foreground.
        if (app.container.cloudRepository.signedInProviders().isNotEmpty()) app.appScope.launch { app.container.syncScheduler.requestSync(delayMs = 0L) }
    }

    /**
     * Requests the display's highest refresh rate (the Honor Pad 9 keeps third-party apps at
     * 60 Hz otherwise) while [enabled]; with false the system picks the rate again. The editor
     * turns this on for smooth ink and off when it closes, so browsing does not burn battery.
     */
    fun setHighRefreshRate(enabled: Boolean) {
        val display: Display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else null)
            ?: getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val modeId = if (enabled) {
            val current = display.mode
            display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                .maxByOrNull { it.refreshRate }?.modeId ?: return
        } else 0
        if (window.attributes.preferredDisplayModeId != modeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = modeId }
        }
    }
}

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val EDITOR = "editor/{noteId}"
    fun editor(noteId: String) = "editor/$noteId"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(onOpenNote = { nav.navigate(Routes.editor(it)) }, onOpenSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.EDITOR, arguments = listOf(navArgument("noteId") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("noteId") ?: return@composable
            EditorScreen(noteId = id, onBack = { nav.popBackStack() })
        }
    }
}
