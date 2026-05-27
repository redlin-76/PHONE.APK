package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CyberColorScheme = darkColorScheme(
    primary = NeonCyan,
    secondary = NeonTeal,
    tertiary = NeonMagenta,
    background = CyberBg,
    surface = CyberCard,
    onPrimary = Color(0xFF0B0F19),
    onSecondary = Color(0xFF0B0F19),
    onBackground = Color.White,
    onSurface = Color.White,
    error = AlertCoral,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force Dark Cyberpunk mode by default
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CyberColorScheme,
        typography = Typography,
        content = content
    )
}
