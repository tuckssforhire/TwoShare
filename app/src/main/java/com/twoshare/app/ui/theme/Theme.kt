package com.twoshare.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// TwoShare uses a calm violet/rose identity: personal enough for a private app,
// but restrained so photos, messages and calls remain the focus.
private val TwoShareLightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFFB3266E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD8E8),
    onSecondaryContainer = Color(0xFF3E0021),
    tertiary = Color(0xFF4B607C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3E4FF),
    onTertiaryContainer = Color(0xFF041C35),
    background = Color(0xFFFFF9FC),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFF1ECF2),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

private val TwoShareDarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFFFAFD1),
    onSecondary = Color(0xFF65003B),
    secondaryContainer = Color(0xFF8D1655),
    onSecondaryContainer = Color(0xFFFFD8E8),
    tertiary = Color(0xFFB3C8E8),
    onTertiary = Color(0xFF1C314B),
    tertiaryContainer = Color(0xFF344861),
    onTertiaryContainer = Color(0xFFD3E4FF),
    background = Color(0xFF121014),
    onBackground = Color(0xFFE8E0E8),
    surface = Color(0xFF19171B),
    onSurface = Color(0xFFE8E0E8),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun TwoShareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) TwoShareDarkColors else TwoShareLightColors,
        typography = Typography(),
        content = content
    )
}
