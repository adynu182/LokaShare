package com.lokashare.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentBlue,
    secondary = GlassBorder,
    background = DarkBg,
    surface = CardBg,
    onPrimary = TextOnAccent,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun LokaShareTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
