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

val LainTypography = Typography(
    displayLarge = TextStyle(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp),
    displayMedium = TextStyle(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = bodyFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = bodyFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = displayFontFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp)
)
