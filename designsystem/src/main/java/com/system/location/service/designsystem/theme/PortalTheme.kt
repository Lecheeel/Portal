package com.system.location.service.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape

private val PortalPurple = Color(0xFF5B4BDB)
private val PortalPurpleDark = Color(0xFFB9B0FF)
private val PortalLavender = Color(0xFFE9E3FF)
private val PortalSurface = Color(0xFFFFFFFF)
private val PortalSurfaceDark = Color(0xFF141218)
private val PortalBackground = Color(0xFFF8F7FC)
private val PortalBackgroundDark = Color(0xFF0D0B12)
private val PortalInk = Color(0xFF1C1B1F)
private val PortalInkMuted = Color(0xFF5E5A66)
private val PortalOutline = Color(0xFFD9D5E3)
private val PortalError = Color(0xFFBA1A1A)

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
    primary = PortalPurple,
    onPrimary = Color.White,
    primaryContainer = PortalLavender,
    onPrimaryContainer = Color(0xFF16005E),
    secondary = Color(0xFF625B71),
    surface = PortalSurface,
    onSurface = PortalInk,
    surfaceVariant = Color(0xFFF1EEF7),
    onSurfaceVariant = PortalInkMuted,
    outline = PortalOutline,
    background = PortalBackground,
    onBackground = PortalInk,
    error = PortalError,
)

private val DarkColors = darkColorScheme(
    primary = PortalPurpleDark,
    onPrimary = Color(0xFF2A1B77),
    primaryContainer = Color(0xFF41349D),
    onPrimaryContainer = Color(0xFFE8DEFF),
    secondary = Color(0xFFCBC2DB),
    surface = PortalSurfaceDark,
    onSurface = Color(0xFFE6E1E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    background = PortalBackgroundDark,
    onBackground = Color(0xFFE6E1E9),
    error = Color(0xFFFFB4AB),
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
