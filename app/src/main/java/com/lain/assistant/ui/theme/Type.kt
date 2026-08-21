package com.lain.assistant.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Brand font is "Bebas Note" (the chunky, rounded hand-drawn face from the
 * reference art). We don't have that font file in this repo, so this falls
 * back to the platform's built-in rounded/cursive family as an honest
 * placeholder — no network font provider, no fabricated certs, nothing that
 * can silently fail at runtime.
 *
 * To switch to the real thing once you have the .ttf:
 *   1. Drop it at app/src/main/res/font/bebas_note.ttf
 *   2. Replace the line below with:
 *        FontFamily(Font(R.font.bebas_note))
 *      (uncomment the two imports above it: androidx.compose.ui.text.font.Font, com.lain.assistant.R)
 */
val displayFontFamily = FontFamily.Cursive

val bodyFontFamily = FontFamily.Default

/**
 * Type scaled for the window it's being drawn in.
 *
 * Fixed sp sizes are correct on the device they were tuned on and wrong either
 * side of it: 16sp body text on a 320dp palmtop wraps every three words, and the
 * same 16sp on a landscape tablet reads as a phone screenshot that's been
 * stretched. [scale] comes from LainWindow.fontScale, so the whole ramp moves
 * together and the relationships between the styles are preserved.
 */
fun lainTypography(scale: Float = 1f): Typography {
    fun s(size: Float) = (size * scale).sp
    return Typography(
        displayLarge = TextStyle(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold, fontSize = s(40f), lineHeight = s(44f)),
        displayMedium = TextStyle(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold, fontSize = s(32f), lineHeight = s(36f)),
        headlineMedium = TextStyle(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold, fontSize = s(26f), lineHeight = s(30f)),
        titleLarge = TextStyle(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold, fontSize = s(22f), lineHeight = s(26f)),
        titleMedium = TextStyle(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold, fontSize = s(18f), lineHeight = s(22f)),
        bodyLarge = TextStyle(fontFamily = bodyFontFamily, fontWeight = FontWeight.Normal, fontSize = s(16f), lineHeight = s(22f)),
        bodyMedium = TextStyle(fontFamily = bodyFontFamily, fontWeight = FontWeight.Normal, fontSize = s(14f), lineHeight = s(20f)),
        labelLarge = TextStyle(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold, fontSize = s(16f), lineHeight = s(20f))
    )
}

/** Unscaled default, kept for previews and any call site outside a Lain window. */
val LainTypography: Typography = lainTypography()
