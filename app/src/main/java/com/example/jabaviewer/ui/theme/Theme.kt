package com.example.jabaviewer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9BC2E3),
    onPrimary = Color(0xFF0D2A3F),
    secondary = Color(0xFFF1B496),
    onSecondary = Color(0xFF2E1F11),
    tertiary = Color(0xFF79D0C6),
    onTertiary = Color(0xFF0B3330),
    background = Color(0xFF141210),
    onBackground = Color(0xFFECE3D9),
    surface = Color(0xFF1B1816),
    onSurface = Color(0xFFECE3D9),
    surfaceVariant = Color(0xFF2A2622),
    onSurfaceVariant = Color(0xFFCAC0B4),
    outline = Color(0xFF4D453D),
)

private val LightColorScheme = lightColorScheme(
    primary = InkBlue,
    onPrimary = Color(0xFFFDFBFF),
    secondary = Sunstone,
    onSecondary = Color(0xFF2D1F0E),
    tertiary = Moss,
    onTertiary = Color(0xFF0B2E2A),
    background = Sand,
    onBackground = Color(0xFF1C1B1F),
    surface = Shell,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE6DED4),
    onSurfaceVariant = Umber,
    outline = Color(0xFFCABFB1),
)

@Composable
fun JabaViewerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
