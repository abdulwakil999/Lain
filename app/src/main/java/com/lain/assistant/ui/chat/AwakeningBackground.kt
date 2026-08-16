package com.lain.assistant.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lain.assistant.R

private const val STRIP_COUNT = 6

/**
 * The reveal: Lain's "awake" portrait starts as the background, sliced into
 * six vertical strips. Odd strips (1st, 3rd, 5th) slide up and out; even
 * strips (2nd, 4th, 6th) slide down and out — alternating, staggered
 * slightly — uncovering her "focused" portrait sitting behind the whole
 * time. Once revealed it stays revealed; this only plays once per session
 * via [awake].
 */
@Composable
fun AwakeningBackground(awake: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        val stripWidth = screenWidth / STRIP_COUNT

        Image(
            painter = painterResource(id = R.drawable.bg_lain_focus),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Row(modifier = Modifier.fillMaxSize()) {
            repeat(STRIP_COUNT) { i ->
                val goesUp = i % 2 == 0
                val target = if (awake) (if (goesUp) -screenHeight else screenHeight) else 0.dp
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
                            .width(screenWidth)
                            .height(screenHeight)
                            .offset(x = -(stripWidth * i)),
                        contentScale = ContentScale.FillBounds
                    )
                }
            }
        }
    }
}
