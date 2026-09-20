package com.bookies.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
