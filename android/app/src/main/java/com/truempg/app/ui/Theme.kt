package com.truempg.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF10B981)
private val GreenDark = Color(0xFF0E3B2E)

private val DarkColors = darkColorScheme(
    primary = Green,
    secondary = Color(0xFF6EE7B7),
    background = Color(0xFF0B1512),
    surface = Color(0xFF13201B),
)

private val LightColors = lightColorScheme(
    primary = GreenDark,
    secondary = Green,
)

@Composable
fun TrueMpgTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
