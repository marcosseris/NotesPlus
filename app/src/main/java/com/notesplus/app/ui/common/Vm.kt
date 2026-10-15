package com.notesplus.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.notesplus.app.NotesPlusApp

@Composable
fun app(): NotesPlusApp = LocalContext.current.applicationContext as NotesPlusApp

/** Obtains a ViewModel built from the app container without a DI framework. */
@Composable
inline fun <reified T : ViewModel> appViewModel(key: String? = null, crossinline create: (NotesPlusApp) -> T): T {
    val application = app()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(application) } })
}

fun formatRelative(epochMillis: Long): String {
    if (epochMillis <= 0L) return "never"
    val diff = System.currentTimeMillis() - epochMillis
    val m = diff / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        m < 60 * 24 * 7 -> "${m / (60 * 24)} d ago"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(epochMillis))
    }
}
