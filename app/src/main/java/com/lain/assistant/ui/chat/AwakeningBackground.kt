package com.lain.assistant.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lain.assistant.R
import com.lain.assistant.ui.theme.LainArtBackdrop
import kotlinx.coroutines.delay

private const val STRIP_COUNT = 6
private const val HOLD_BEFORE_REVEAL_MS = 900L
private const val STRIP_DURATION_MS = 1100
private const val STRIP_STAGGER_MS = 130

/**
 * Full-bleed backdrop for the chat screen.
 *
 * Lain's "awake" portrait is held long enough to actually register, then
 * slices into six vertical strips: odd strips (1st, 3rd, 5th) slide up and
 * out, even strips (2nd, 4th, 6th) slide down and out, staggered, uncovering
 * her "focused" portrait that was sitting behind the whole time.
 *
 * The square artwork is drawn at full screen width and top-aligned, and the
 * *entire* composable is painted in [LainArtBackdrop] — the exact slate the
 * art's own background uses — so the area below the square is indistinguishable
 * from the picture instead of showing a hard seam. Both layers are sized off
 * the measured constraints, so this scales to any screen without fixed dp.
 */
@Composable
fun AwakeningBackground(
    awake: Boolean,
    modifier: Modifier = Modifier,
    /** Edge length of the square portrait. Unspecified falls back to the full window width. */
    artSize: Dp = Dp.Unspecified
) {
    var revealed by remember { mutableStateOf(false) }

    LaunchedEffect(awake) {
        if (awake) {
            delay(HOLD_BEFORE_REVEAL_MS)
            revealed = true
        } else {
            revealed = false
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(LainArtBackdrop)) {
        // The caller decides how much of the window the portrait may claim (see
        // LainWindow.artSize); this composable only has to agree with it exactly, or the
        // strips reveal the wrong slices.
        val art: Dp = if (artSize != Dp.Unspecified) artSize else maxWidth
        val stripWidth = art / STRIP_COUNT
        val travel = maxHeight + art // guarantees a strip fully clears the viewport

        Image(
            painter = painterResource(id = R.drawable.bg_lain_focus),
            contentDescription = null,
            modifier = Modifier
                .width(art)
                .height(art)
                .align(Alignment.TopCenter),
            contentScale = ContentScale.FillBounds
        )

        Row(
            modifier = Modifier
                .width(art)
                .height(art)
                .align(Alignment.TopCenter)
        ) {
            repeat(STRIP_COUNT) { i ->
                val goesUp = i % 2 == 0
                val target = if (revealed) (if (goesUp) -travel else travel) else 0.dp
                val offsetY by animateDpAsState(
                    targetValue = target,
                    animationSpec = tween(
                        durationMillis = STRIP_DURATION_MS,
                        delayMillis = i * STRIP_STAGGER_MS,
                        easing = FastOutSlowInEasing
                    ),
                    label = "strip_$i"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .offset(y = offsetY)
                        .clipToBounds()
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.bg_lain_awake),
                        contentDescription = null,
                        // Sized to the FULL artwork and shifted left by this strip's index, so each
                        // narrow clipped window shows its own correct slice of the picture rather
                        // than a squashed copy of the whole thing.
                        modifier = Modifier
                            .width(art)
                            .height(art)
                            .offset(x = -(stripWidth * i)),
                        contentScale = ContentScale.FillBounds
                    )
                }
            }
        }

        // The artwork's bottom edge is the character's near-black clothing while the page
        // around it is light slate; butted together that hard line is what read as the
        // portrait being cut in half. Dissolve the lower part of the square into the
        // backdrop instead. Drawn last so it fades both portraits during the reveal.
        Box(
            modifier = Modifier
                .width(art)
                .height(art)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        0.82f to LainArtBackdrop.copy(alpha = 0.65f),
                        1.0f to LainArtBackdrop
                    )
                )
        )
    }
}
