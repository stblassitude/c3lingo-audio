package org.c3lingo.audio

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Yellow = Color(0xFFFFF200)
private val Ink = Color(0xFF1D1D1B)

// Surfaces match the frontend's theme colours.
private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Yellow,
    background = Color.White,
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Yellow,
    onPrimary = Ink,
    background = Ink,
    surface = Ink,
)

@Composable
fun C3lingoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
