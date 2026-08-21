package com.lain.assistant.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How wide the window is, in the three bands that actually change a layout
 * decision. These are the standard Material breakpoints, resolved here rather
 * than pulled in as a dependency because Lain only needs the numbers.
 */
enum class WidthClass {
    /** Phones in portrait, and small foldables closed. */
    COMPACT,

    /** Large phones in landscape, small tablets, phones in split-screen. */
    MEDIUM,

    /** Tablets in landscape, desktop-class windows. */
    EXPANDED
}

/** Vertical room. Short covers landscape phones and genuinely tiny devices. */
enum class HeightClass { SHORT, REGULAR }

/**
 * The measurements every Lain screen lays itself out from.
 *
 * Sizing off raw `maxWidth` is fine on one phone and wrong everywhere else: the
 * portrait is a square drawn at the full width, so on an 800dp tablet it became
 * an 800dp-tall picture that pushed the conversation off the bottom of the
 * screen, and on a 320dp palmtop the chat bubbles had no room left. Everything
 * derived here is a function of both dimensions, so the same layout holds from a
 * watch-sized window up to a landscape tablet.
 */
data class LainWindow(
    val width: WidthClass,
    val height: HeightClass,
    val widthDp: Dp,
    val heightDp: Dp
) {
    /** Screens are readable, not stretched: content stops widening past this and centres. */
    val contentMaxWidth: Dp
        get() = when (width) {
            WidthClass.COMPACT -> widthDp
            WidthClass.MEDIUM -> 560.dp
            WidthClass.EXPANDED -> 680.dp
        }

    /** Horizontal page margin. */
    val gutter: Dp
        get() = when (width) {
            WidthClass.COMPACT -> if (widthDp < 340.dp) 12.dp else 16.dp
            WidthClass.MEDIUM -> 24.dp
            WidthClass.EXPANDED -> 32.dp
        }

    /** Vertical rhythm between sections; squeezed when there's no height to spare. */
    val sectionGap: Dp get() = if (height == HeightClass.SHORT) 12.dp else 20.dp

    /** Top inset for screens that start with a title rather than artwork. */
    val topInset: Dp get() = if (height == HeightClass.SHORT) 20.dp else 44.dp

    /**
     * How big the square portrait is allowed to be.
     *
     * Capped by height as well as width, because the art competes with the
     * conversation for vertical space: on a short or wide window it shrinks
     * rather than swallowing the screen, and it never exceeds the readable
     * content width on a tablet.
     */
    val artSize: Dp
        get() {
            val byWidth = minOf(widthDp, contentMaxWidth)
            val byHeight = heightDp * if (height == HeightClass.SHORT) 0.34f else 0.46f
            return minOf(byWidth, byHeight)
        }

    /** Chat bubbles wrap rather than running the full width of a wide window. */
    val bubbleMaxWidth: Dp get() = contentMaxWidth * 0.82f

    /**
     * Multiplier applied to every text style. Small screens get slightly smaller
     * type so a bubble still fits a sentence per line; large ones get slightly
     * larger so the app doesn't read as a blown-up phone layout.
     */
    val fontScale: Float
        get() = when {
            widthDp < 340.dp -> 0.88f
            width == WidthClass.EXPANDED -> 1.12f
            width == WidthClass.MEDIUM -> 1.06f
            else -> 1f
        }

    /** Touch targets stay comfortable on a tablet, where everything else grew. */
    val controlPadding: Dp get() = if (width == WidthClass.COMPACT) 14.dp else 16.dp
}

@Composable
@ReadOnlyComposable
private fun currentWindow(): LainWindow {
    val config = LocalConfiguration.current
    val w = config.screenWidthDp.dp
    val h = config.screenHeightDp.dp
    return LainWindow(
        width = when {
            w < 600.dp -> WidthClass.COMPACT
            w < 840.dp -> WidthClass.MEDIUM
            else -> WidthClass.EXPANDED
        },
        height = if (h < 480.dp) HeightClass.SHORT else HeightClass.REGULAR,
        widthDp = w,
        heightDp = h
    )
}

/** Recomputed on rotation, fold, and split-screen resize, since it reads the configuration. */
@Composable
fun rememberLainWindow(): LainWindow {
    val window = currentWindow()
    return remember(window) { window }
}
