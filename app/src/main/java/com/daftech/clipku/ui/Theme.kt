package com.daftech.clipku.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ClipKuColors = darkColorScheme(
    primary = Color(0xFFF59E0B),
    onPrimary = Color(0xFF0B1220),
    background = Color(0xFF0B1220),
    surface = Color(0xFF111827),
    onBackground = Color(0xFFF9FAFB),
    onSurface = Color(0xFFF9FAFB),
    secondary = Color(0xFFFDE68A)
)

@Composable
fun ClipKuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ClipKuColors, content = content)
}
