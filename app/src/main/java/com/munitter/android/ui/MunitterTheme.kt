package com.munitter.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MunitterDarkColors = darkColorScheme(
    primary = Color(0xFFD8B4A0),
    onPrimary = Color(0xFF30211A),
    background = Color(0xFF24211E),
    onBackground = Color(0xFFFFF8F2),
    surface = Color(0xFF302B27),
    onSurface = Color(0xFFFFF8F2),
    error = Color(0xFFFFB4AB),
)

@Composable
fun MunitterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MunitterDarkColors,
        content = content,
    )
}
