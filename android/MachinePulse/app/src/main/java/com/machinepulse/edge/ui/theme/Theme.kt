package com.machinepulse.edge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ColorWhite = Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary = SignalGreen,
    onPrimary = ColorWhite,
    primaryContainer = SignalGreenLight,
    onPrimaryContainer = Graphite,
    secondary = Slate,
    onSecondary = ColorWhite,
    tertiary = Amber,
    tertiaryContainer = AmberLight,
    background = Canvas,
    onBackground = Graphite,
    surface = Surface,
    onSurface = Graphite,
    surfaceVariant = Canvas,
    onSurfaceVariant = MutedText,
    outlineVariant = Border,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5EE9B5),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF075B41),
    onPrimaryContainer = Color(0xFFB8F5DB),
    secondary = Color(0xFFB8C7D8),
    tertiary = Color(0xFFF8C56A),
    background = DarkCanvas,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = Color(0xFF20262C),
    onSurfaceVariant = Color(0xFFBBC4CE),
    outlineVariant = DarkBorder,
)

@Composable
fun MachinePulseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        typography = MachinePulseTypography,
        content = content,
    )
}
