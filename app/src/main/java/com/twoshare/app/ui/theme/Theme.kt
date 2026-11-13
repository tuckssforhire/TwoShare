package com.twoshare.app.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val THEME_TOGETHER = "together"
const val THEME_ROSE = "rose"
const val THEME_MIDNIGHT = "midnight"

private val TogetherLight = lightColorScheme(
    primary=Color(0xFF6750A4),onPrimary=Color.White,primaryContainer=Color(0xFFEADDFF),onPrimaryContainer=Color(0xFF21005D),
    secondary=Color(0xFFB3266E),onSecondary=Color.White,secondaryContainer=Color(0xFFFFD8E8),onSecondaryContainer=Color(0xFF3E0021),
    tertiary=Color(0xFF4B607C),onTertiary=Color.White,tertiaryContainer=Color(0xFFD3E4FF),onTertiaryContainer=Color(0xFF041C35),
    background=Color(0xFFFFF9FC),onBackground=Color(0xFF1D1B20),surface=Color(0xFFFFFBFE),onSurface=Color(0xFF1D1B20),
    surfaceVariant=Color(0xFFF1ECF2),onSurfaceVariant=Color(0xFF49454F),outline=Color(0xFF79747E),outlineVariant=Color(0xFFCAC4D0),error=Color(0xFFBA1A1A),onError=Color.White)
private val TogetherDark = darkColorScheme(
    primary=Color(0xFFD0BCFF),onPrimary=Color(0xFF381E72),primaryContainer=Color(0xFF4F378B),onPrimaryContainer=Color(0xFFEADDFF),
    secondary=Color(0xFFFFAFD1),onSecondary=Color(0xFF65003B),secondaryContainer=Color(0xFF8D1655),onSecondaryContainer=Color(0xFFFFD8E8),
    tertiary=Color(0xFFB3C8E8),onTertiary=Color(0xFF1C314B),tertiaryContainer=Color(0xFF344861),onTertiaryContainer=Color(0xFFD3E4FF),
    background=Color(0xFF121014),onBackground=Color(0xFFE8E0E8),surface=Color(0xFF19171B),onSurface=Color(0xFFE8E0E8),
    surfaceVariant=Color(0xFF49454F),onSurfaceVariant=Color(0xFFCAC4D0),outline=Color(0xFF938F99),outlineVariant=Color(0xFF49454F),error=Color(0xFFFFB4AB),onError=Color(0xFF690005))

private val RoseLight = lightColorScheme(
    primary=Color(0xFFB83280),onPrimary=Color.White,primaryContainer=Color(0xFFFFD8EA),onPrimaryContainer=Color(0xFF3D0026),
    secondary=Color(0xFF8F4A66),onSecondary=Color.White,secondaryContainer=Color(0xFFFFD9E3),onSecondaryContainer=Color(0xFF3A071F),
    tertiary=Color(0xFF7B5733),onTertiary=Color.White,tertiaryContainer=Color(0xFFFFDDB8),onTertiaryContainer=Color(0xFF2C1600),
    background=Color(0xFFFFF8FA),onBackground=Color(0xFF24191E),surface=Color(0xFFFFF8FA),onSurface=Color(0xFF24191E),
    surfaceVariant=Color(0xFFF7E6ED),onSurfaceVariant=Color(0xFF554149),outline=Color(0xFF88717A),outlineVariant=Color(0xFFDBC1CB))
private val RoseDark = darkColorScheme(
    primary=Color(0xFFFFADD4),onPrimary=Color(0xFF650044),primaryContainer=Color(0xFF8D155F),onPrimaryContainer=Color(0xFFFFD8EA),
    secondary=Color(0xFFFFB1C9),onSecondary=Color(0xFF57152F),secondaryContainer=Color(0xFF713047),onSecondaryContainer=Color(0xFFFFD9E3),
    tertiary=Color(0xFFEFBF8C),onTertiary=Color(0xFF452B0B),tertiaryContainer=Color(0xFF5E4120),onTertiaryContainer=Color(0xFFFFDDB8),
    background=Color(0xFF1B1116),onBackground=Color(0xFFF2DDE5),surface=Color(0xFF21171C),onSurface=Color(0xFFF2DDE5),
    surfaceVariant=Color(0xFF554149),onSurfaceVariant=Color(0xFFDBC1CB),outline=Color(0xFFA68A95),outlineVariant=Color(0xFF554149))

private val MidnightLight = lightColorScheme(
    primary=Color(0xFF285A9B),onPrimary=Color.White,primaryContainer=Color(0xFFD7E3FF),onPrimaryContainer=Color(0xFF001B3E),
    secondary=Color(0xFF4F5F7A),onSecondary=Color.White,secondaryContainer=Color(0xFFD9E3FF),onSecondaryContainer=Color(0xFF0B1C33),
    tertiary=Color(0xFF006A67),onTertiary=Color.White,tertiaryContainer=Color(0xFF9CF1EC),onTertiaryContainer=Color(0xFF00201F),
    background=Color(0xFFF9F9FF),onBackground=Color(0xFF191C20),surface=Color(0xFFF9F9FF),onSurface=Color(0xFF191C20),
    surfaceVariant=Color(0xFFE1E2EC),onSurfaceVariant=Color(0xFF44474F),outline=Color(0xFF74777F),outlineVariant=Color(0xFFC4C6D0))
private val MidnightDark = darkColorScheme(
    primary=Color(0xFFA9C7FF),onPrimary=Color(0xFF003063),primaryContainer=Color(0xFF0B477F),onPrimaryContainer=Color(0xFFD7E3FF),
    secondary=Color(0xFFB8C7E8),onSecondary=Color(0xFF213149),secondaryContainer=Color(0xFF37475F),onSecondaryContainer=Color(0xFFD9E3FF),
    tertiary=Color(0xFF80D5D0),onTertiary=Color(0xFF003735),tertiaryContainer=Color(0xFF00504D),onTertiaryContainer=Color(0xFF9CF1EC),
    background=Color(0xFF0D1117),onBackground=Color(0xFFE2E2E9),surface=Color(0xFF121820),onSurface=Color(0xFFE2E2E9),
    surfaceVariant=Color(0xFF44474F),onSurfaceVariant=Color(0xFFC4C6D0),outline=Color(0xFF8E9099),outlineVariant=Color(0xFF44474F))

fun loadTheme(context: Context, userId: String?): String {
    if (userId.isNullOrBlank()) return THEME_TOGETHER
    return context.getSharedPreferences("twoshare_themes", Context.MODE_PRIVATE).getString("theme_$userId", THEME_TOGETHER) ?: THEME_TOGETHER
}
fun saveTheme(context: Context, userId: String?, theme: String) {
    if (userId.isNullOrBlank()) return
    context.getSharedPreferences("twoshare_themes", Context.MODE_PRIVATE).edit().putString("theme_$userId", theme).apply()
}

@Composable
fun TwoShareTheme(theme: String = THEME_TOGETHER, darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = when (theme) {
        THEME_ROSE -> if (darkTheme) RoseDark else RoseLight
        THEME_MIDNIGHT -> if (darkTheme) MidnightDark else MidnightLight
        else -> if (darkTheme) TogetherDark else TogetherLight
    }
    MaterialTheme(colorScheme=colors.withTintedSurfaces(darkTheme), typography=TwoShareTypography, shapes=TwoShareShapes, content=content)
}

/**
 * Material 3 falls back to the baseline (violet-grey) surface container colors when a
 * scheme does not set them, so the Rose and Midnight themes ended up with purple cards,
 * sheets and navigation bars. Derive every container tone from the theme's own surface
 * and primary color so each theme stays consistent from page background to dialogs.
 */
private fun ColorScheme.withTintedSurfaces(dark: Boolean): ColorScheme {
    fun tint(base: Color, amount: Float) = lerp(base, primary, amount)
    return if (dark) copy(
        surfaceDim = background,
        surfaceContainerLowest = lerp(background, Color.Black, .35f),
        surfaceContainerLow = tint(surface, .04f),
        surfaceContainer = tint(surface, .07f),
        surfaceContainerHigh = tint(surface, .10f),
        surfaceContainerHighest = tint(surface, .14f),
        surfaceBright = tint(surface, .18f),
        surfaceTint = primary
    ) else copy(
        surfaceBright = surface,
        // Page background behind cards: a soft tint so white cards stand out.
        surfaceContainerLowest = tint(background, .035f),
        surfaceContainerLow = tint(surface, .045f),
        surfaceContainer = tint(surface, .075f),
        surfaceContainerHigh = tint(surface, .10f),
        surfaceContainerHighest = tint(surface, .135f),
        surfaceDim = tint(surface, .16f),
        surfaceTint = primary
    )
}

private val BaseTypography = Typography()

/** Slightly bolder headings and labels for a warmer, more confident hierarchy. */
val TwoShareTypography = BaseTypography.copy(
    displaySmall = BaseTypography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = BaseTypography.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = BaseTypography.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(lineHeight = 21.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = BaseTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
)

/** Softer, rounder corners for buttons, cards, menus and dialogs. */
val TwoShareShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
