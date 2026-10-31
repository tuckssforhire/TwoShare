package com.twoshare.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TwoShareColors = lightColorScheme(
    primary = Color(0xFF6650D8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E0FF),
    onPrimaryContainer = Color(0xFF21105B),
    secondary = Color(0xFFE84A8A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E7),
    onSecondaryContainer = Color(0xFF5B1230),
    tertiary = Color(0xFF1677C8),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6E9FF),
    background = Color(0xFFF9F7FF),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFF0EDF6),
    outline = Color(0xFF7B7785)
)

@Composable
fun TwoShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TwoShareColors, content = content)
}
