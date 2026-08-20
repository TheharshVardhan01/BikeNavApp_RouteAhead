package com.h2km33t.routeahead.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Instrument-cluster palette.
 *
 * The app is dark-only by design rather than by omission: it is read at a glance from a
 * handlebar mount, often at night, and a white screen at 60 km/h in the dark is genuinely
 * dangerous. There is deliberately no light scheme to fall back to.
 */
object Ink {
    val Background = Color(0xFF070A0E)
    val Surface = Color(0xFF121821)
    val SurfaceElevated = Color(0xFF1A222D)
    val SurfacePressed = Color(0xFF222C39)
    val Outline = Color(0xFF2A3542)
    val OutlineSoft = Color(0xFF1E2733)

    val Accent = Color(0xFF34E06A)
    val AccentSoft = Color(0xFF1B7A3C)
    val OnAccent = Color(0xFF04170B)

    val Warning = Color(0xFFFFB020)
    val Danger = Color(0xFFFF5A5F)

    val TextPrimary = Color(0xFFF4F7FA)
    val TextSecondary = Color(0xFF97A4B4)
    val TextTertiary = Color(0xFF5F6D7C)
}

private val ColorScheme = darkColorScheme(
    primary = Ink.Accent,
    onPrimary = Ink.OnAccent,
    primaryContainer = Ink.AccentSoft,
    onPrimaryContainer = Ink.TextPrimary,

    secondary = Ink.Warning,
    onSecondary = Ink.Background,

    background = Ink.Background,
    onBackground = Ink.TextPrimary,

    surface = Ink.Surface,
    onSurface = Ink.TextPrimary,
    surfaceVariant = Ink.SurfaceElevated,
    onSurfaceVariant = Ink.TextSecondary,
    surfaceContainerHighest = Ink.SurfacePressed,

    error = Ink.Danger,
    onError = Ink.Background,

    outline = Ink.Outline,
    outlineVariant = Ink.OutlineSoft
)

/**
 * Every style is declared rather than inheriting Material defaults, so a change of scale
 * in one place can't leave two screens disagreeing about what "titleMedium" means.
 *
 * The display sizes are tuned for numerals read at arm's length: tight letter spacing and
 * heavy weight, because at a glance the digit shape carries the information, not the text.
 */
private val Type = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 72.sp, lineHeight = 76.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-3).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 48.sp, lineHeight = 52.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp
    ),
    displaySmall = TextStyle(
        fontSize = 34.sp, lineHeight = 40.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-1).sp
    ),
    headlineLarge = TextStyle(
        fontSize = 30.sp, lineHeight = 36.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),

    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),

    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),

    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    // Section headers: small, wide-tracked caps. The tracking is what makes them read as
    // labels rather than as very small body text.
    labelSmall = TextStyle(
        fontSize = 11.sp, lineHeight = 15.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp
    )
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun RouteAheadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = Type,
        shapes = AppShapes,
        content = content
    )
}
