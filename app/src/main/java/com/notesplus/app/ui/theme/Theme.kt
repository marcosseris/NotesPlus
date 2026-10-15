package com.notesplus.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF1F4E79), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4F7), onPrimaryContainer = Color(0xFF0B1D33),
    secondary = Color(0xFFE8542F), onSecondary = Color.White,
    surface = Color(0xFFFBFBFD), background = Color(0xFFF4F5F8)
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA3C9F2), onPrimary = Color(0xFF07294A),
    primaryContainer = Color(0xFF1F4E79), onPrimaryContainer = Color(0xFFD3E4F7),
    secondary = Color(0xFFFF8A65), onSecondary = Color(0xFF3A1206)
)

@Composable
fun NotesPlusTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
