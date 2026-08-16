package com.lain.assistant.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lain.assistant.R
import kotlinx.coroutines.delay

private const val STRIP_COUNT = 6
private const val REVEAL_DELAY_MS = 300L

/**
 * The reveal: Lain's "awake" portrait sits as the background first — held
 * there just long enough to register — then slices into six vertical
 * strips. Odd strips (1st, 3rd, 5th) slide up and out; even strips (2nd,
 * 4th, 6th) slide down and out, staggered, uncovering her "focused"
 * portrait sitting behind the whole time. Plays once per [awake] flip.
 *
 * Both source images are square (1:1). This composable is a fixed
 * `fillMaxWidth().aspectRatio(1f)` square — NOT the full screen — so its
 * caller must stack it above the scrollable chat content in a Column
 * rather than layering everything in one full-screen Box. That's the fix
 * for messages/banners rendering on top of the portrait instead of below
 * it: previously this drew across the whole screen while the chat list
 * used a guessed, unrelated top-padding, so the two independently-sized
 * things could easily overlap.
 */
@Composable
fun AwakeningBackground(awake: Boolean, modifier: Modifier = Modifier) {
    var revealed by remember { mutableStateOf(false) }

    LaunchedEffect(awake) {
        if (awake) {
            delay(REVEAL_DELAY_MS) // let the static "awake" frame actually be seen first
            revealed = true
        } else {
            revealed = false
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth().aspectRatio(1f)) {
        val artSize = maxWidth // == maxHeight, guaranteed square by aspectRatio(1f)
        val stripWidth = artSize / STRIP_COUNT

        Image(
            painter = painterResource(id = R.drawable.bg_lain_focus),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        Row(modifier = Modifier.fillMaxSize()) {
            repeat(STRIP_COUNT) { i ->
                val goesUp = i % 2 == 0
                // Moving a strip by its own height fully clears its original bounds either way.
                val target = if (revealed) (if (goesUp) -artSize else artSize) else 0.dp
                val offsetY by animateDpAsState(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 650, delayMillis = i * 70, easing = FastOutSlowInEasing),
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
                        // Fixed to the FULL artSize (not fillMaxSize/stripWidth) and shifted left —
                        // this is what makes each narrow clipped strip show its correct slice of the
                        // whole image rather than a squished stripWidth-wide copy of the whole thing.
                        modifier = Modifier
                            .width(artSize)
                            .height(artSize)
                            .offset(x = -(stripWidth * i)),
                        contentScale = ContentScale.FillBounds
                    )
                }
            }
        }
    }
}
