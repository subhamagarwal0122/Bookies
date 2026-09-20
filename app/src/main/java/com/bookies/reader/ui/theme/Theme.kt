package com.bookies.reader.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Paper = Color(0xFFF7F2E8)
private val Ink = Color(0xFF1C1A17)
private val Spine = Color(0xFF7A4A2F)
private val Shelf = Color(0xFF3E2C20)

private val LightColors = lightColorScheme(
    primary = Spine, background = Paper, surface = Paper,
    onBackground = Ink, onSurface = Ink, surfaceVariant = Shelf
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD9A57B), background = Color(0xFF14120F), surface = Color(0xFF1C1A17),
    onBackground = Paper, onSurface = Paper, surfaceVariant = Color(0xFF2A211A)
)

@Composable
fun BookiesTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        // The status bar icons follow the system's idea of light/dark unless told
        // otherwise, which over a cream background means white icons on near-white:
        // a clock and a battery that are simply not there.
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
