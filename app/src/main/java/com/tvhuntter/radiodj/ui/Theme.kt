package com.tvhuntter.radiodj.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LiveRed = Color(0xFFE53935)
val OkGreen = Color(0xFF2E7D32)
val BrandBlue = Color(0xFF1E88E5)
val Amber = Color(0xFFFFB300)

private val scheme = darkColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    secondary = LiveRed,
    onSecondary = Color.White,
    background = Color(0xFF0A0F1E),
    onBackground = Color(0xFFE8ECF5),
    surface = Color(0xFF121A2F),
    onSurface = Color(0xFFE8ECF5),
    surfaceVariant = Color(0xFF1B2540),
    onSurfaceVariant = Color(0xFFB8C2DA),
    surfaceContainer = Color(0xFF121A2F),
)

@Composable
fun RadioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
