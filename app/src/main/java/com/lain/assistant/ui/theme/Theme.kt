package com.lain.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val LainColorScheme = darkColorScheme(
    primary = LainSalmon,
    onPrimary = LainInk,
    secondary = LainMuted,
    onSecondary = LainInk,
    background = LainNavyDeep,
    onBackground = LainCream,
    surface = LainNavy,
    onSurface = LainCream,
    surfaceVariant = LainNavyDeep,
    onSurfaceVariant = LainMuted,
    error = LainSalmonDeep,
    onError = LainCream
)

@Composable
fun LainTheme(
    // Lain always runs in its own dark palette — the retro vibe doesn't
    // adapt to system light mode.
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LainColorScheme,
        typography = LainTypography,
        shapes = LainShapes,
        content = content
    )
}
