package com.woodward.tailcodex.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F0DF),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4C635C),
    background = Color(0xFFF7F7F4),
    surface = Color(0xFFF7F7F4),
    surfaceVariant = Color(0xFFE2EAE5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF98D7C5),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005143),
    onPrimaryContainer = Color(0xFFB7F0DF),
    secondary = Color(0xFFB3CCC3),
    background = Color(0xFF101412),
    surface = Color(0xFF101412),
    surfaceVariant = Color(0xFF29332F),
)

@Composable
fun TailCodexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
