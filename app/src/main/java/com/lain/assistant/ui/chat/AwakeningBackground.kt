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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lain.assistant.R
import com.lain.assistant.ui.theme.LainNavyDeep
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
 * Both source images are square (1:1), so they're fit to the screen's full
 * width — top-aligned, whole character visible, no cropping — with the
 * letterboxed strip below painted the same navy as the art's own backdrop
 * so the seam is invisible.
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

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val artSize = screenWidth // square art, fit to full width so the whole figure is visible
        val stripWidth = artSize / STRIP_COUNT

        Box(modifier = Modifier.fillMaxSize().background(LainNavyDeep))

        Image(
            painter = painterResource(id = R.drawable.bg_lain_focus),
            contentDescription = null,
            modifier = Modifier.width(artSize).height(artSize).align(Alignment.TopCenter),
            contentScale = ContentScale.FillBounds
        )

        Row(modifier = Modifier.width(artSize).height(artSize).align(Alignment.TopCenter)) {
            repeat(STRIP_COUNT) { i ->
                val goesUp = i % 2 == 0
                val target = if (revealed) (if (goesUp) -screenHeight else screenHeight) else 0.dp
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
