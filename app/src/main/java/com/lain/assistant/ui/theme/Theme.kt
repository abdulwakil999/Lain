package com.lain.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import com.lain.assistant.ui.common.rememberLainWindow

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
    // Type is scaled to the window rather than fixed, so the same layout reads
    // correctly on a 320dp palmtop and a landscape tablet. See LainWindow.fontScale.
    val window = rememberLainWindow()
    MaterialTheme(
        colorScheme = LainColorScheme,
        typography = lainTypography(window.fontScale),
        shapes = LainShapes,
        content = content
    )
}
