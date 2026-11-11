package com.system.location.service.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PortalGreen = Color(0xFF4F9D45)
private val PortalGreenDark = Color(0xFF8ED27F)
private val PortalGreenContainer = Color(0xFFDDF7D5)
private val PortalSurface = Color(0xFFFFFFFF)
private val PortalSurfaceDark = Color(0xFF1B281D)
private val PortalBackground = Color(0xFFF3FAF0)
private val PortalBackgroundDark = Color(0xFF101A12)
private val PortalInk = Color(0xFF0F172A)
private val PortalInkMuted = Color(0xFF5F7160)
private val PortalOutline = Color(0xFFD5E5D0)

private val PortalTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

private val PortalShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

object PortalSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
}

private val LightColors = lightColorScheme(
    primary = PortalGreen,
    onPrimary = Color.White,
    primaryContainer = PortalGreenContainer,
    onPrimaryContainer = Color(0xFF163A18),
    secondary = Color(0xFF6AAE5D),
    surface = PortalSurface,
    onSurface = PortalInk,
    surfaceVariant = Color(0xFFEAF5E7),
    onSurfaceVariant = PortalInkMuted,
    outline = Color(0xFF7B8495),
    outlineVariant = PortalOutline,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEAF5E7),
    surfaceContainer = Color(0xFFE5F0E2),
    surfaceContainerHigh = Color(0xFFDDEBD9),
    surfaceContainerHighest = Color(0xFFD5E5D0),
    background = PortalBackground,
    onBackground = PortalInk,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = PortalGreenDark,
    onPrimary = Color(0xFF143018),
    primaryContainer = Color(0xFF285C2D),
    onPrimaryContainer = Color(0xFFDDF7D5),
    secondary = Color(0xFFA6D99B),
    surface = PortalSurfaceDark,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF8995A9),
    outlineVariant = Color(0xFF334155),
    surfaceContainerLowest = Color(0xFF0B1220),
    surfaceContainerLow = Color(0xFF172235),
    surfaceContainer = PortalSurfaceDark,
    surfaceContainerHigh = Color(0xFF28364C),
    surfaceContainerHighest = Color(0xFF334155),
    background = PortalBackgroundDark,
    onBackground = Color(0xFFF1F5F9),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun PortalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PortalTypography,
        shapes = PortalShapes,
        content = content,
    )
}
