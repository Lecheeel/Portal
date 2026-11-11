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

private val PortalGreen = Color(0xFF326B2D)
private val PortalGreenDark = Color(0xFF8ED27F)
private val PortalGreenContainer = Color(0xFFDDF7D5)
private val PortalSurface = Color(0xFFFFFFFF)
private val PortalSurfaceDark = Color(0xFF1B281D)
private val PortalBackground = Color(0xFFF3FAF0)
private val PortalBackgroundDark = Color(0xFF101A12)
private val PortalInk = Color(0xFF182018)
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
    secondary = Color(0xFF416B3A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5EBCD),
    onSecondaryContainer = Color(0xFF1F361B),
    tertiary = Color(0xFF49685C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDEBDD),
    onTertiaryContainer = Color(0xFF122F24),
    surfaceTint = PortalGreen,
    inverseSurface = Color(0xFF2D352B),
    inverseOnSurface = Color(0xFFEDF5E9),
    inversePrimary = PortalGreenDark,
    surface = PortalSurface,
    onSurface = PortalInk,
    surfaceVariant = Color(0xFFEAF5E7),
    onSurfaceVariant = PortalInkMuted,
    outline = Color(0xFF73836F),
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
    onSecondary = Color(0xFF173511),
    secondaryContainer = Color(0xFF304D2A),
    onSecondaryContainer = Color(0xFFD5EBCD),
    tertiary = Color(0xFFACCFBE),
    onTertiary = Color(0xFF18392C),
    tertiaryContainer = Color(0xFF304F41),
    onTertiaryContainer = Color(0xFFCDEBDD),
    surfaceTint = PortalGreenDark,
    inverseSurface = Color(0xFFE5EEE1),
    inverseOnSurface = Color(0xFF253122),
    inversePrimary = PortalGreen,
    surface = PortalSurfaceDark,
    onSurface = Color(0xFFE5EEE1),
    surfaceVariant = Color(0xFF354B37),
    onSurfaceVariant = Color(0xFFBFCFBC),
    outline = Color(0xFF8E9F8A),
    outlineVariant = Color(0xFF354B37),
    surfaceContainerLowest = Color(0xFF0B140D),
    surfaceContainerLow = Color(0xFF152118),
    surfaceContainer = PortalSurfaceDark,
    surfaceContainerHigh = Color(0xFF293D2C),
    surfaceContainerHighest = Color(0xFF354B37),
    background = PortalBackgroundDark,
    onBackground = Color(0xFFE5EEE1),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

enum class PortalColorStyle { GREEN, CLASSIC }

@Composable
fun PortalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorStyle: PortalColorStyle = PortalColorStyle.GREEN,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = when (colorStyle) {
            PortalColorStyle.GREEN -> if (darkTheme) DarkColors else LightColors
            PortalColorStyle.CLASSIC -> if (darkTheme) ClassicDarkColors else ClassicLightColors
        },
        typography = PortalTypography,
        shapes = PortalShapes,
        content = content,
    )
}
