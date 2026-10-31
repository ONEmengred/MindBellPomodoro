package com.mindfulfocus.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 奶蛙 palette — warm amber on cream white.
 * The whole app is intentionally light-only: a soft, sun-lit page is what makes
 * the timers feel calm rather than alarming.
 */
private val WarmColors = lightColorScheme(
    primary = Color(0xFFF2A43A),
    onPrimary = Color(0xFF432C05),
    primaryContainer = Color(0xFFFFE7BC),
    onPrimaryContainer = Color(0xFF4A3105),

    secondary = Color(0xFFB98244),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFBE6C4),
    onSecondaryContainer = Color(0xFF3E2A0B),

    tertiary = Color(0xFF8FA35F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE9F0D6),
    onTertiaryContainer = Color(0xFF2C3413),

    background = Color(0xFFFFFBF2),
    onBackground = Color(0xFF3B3222),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF3B3222),
    surfaceVariant = Color(0xFFFFF1DA),
    onSurfaceVariant = Color(0xFF6E5C40),

    // Warm tints for every surface level, otherwise Material 3's default
    // lavender-grey containers would creep into cards and menus.
    surfaceDim = Color(0xFFFBEED8),
    surfaceBright = Color(0xFFFFFDF8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFCF5),
    surfaceContainer = Color(0xFFFFF8EC),
    surfaceContainerHigh = Color(0xFFFFF3E1),
    surfaceContainerHighest = Color(0xFFFFEEDA),

    outline = Color(0xFFE2CFAC),
    outlineVariant = Color(0xFFF4E7CF),

    surfaceTint = Color(0xFFF2A43A),
    inverseSurface = Color(0xFF3B3222),
    inverseOnSurface = Color(0xFFFFF3DE),
    inversePrimary = Color(0xFFFFD48A),
    scrim = Color(0xFF000000)
)

/** Slightly larger, rounder type — gentle rather than clinical. */
private val WarmTypography = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun MindFocusTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }
    MaterialTheme(
        colorScheme = WarmColors,
        typography = WarmTypography,
        content = content
    )
}
