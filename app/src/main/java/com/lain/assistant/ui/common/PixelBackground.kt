package com.lain.assistant.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.lain.assistant.R
import com.lain.assistant.ui.theme.LainArtBackdrop

/**
 * Full portrait of Lain, sitting behind whatever UI hovers on top. The square
 * artwork is drawn at full screen width and top-aligned so the whole figure is
 * visible, over the art's own backdrop slate.
 *
 * The lower part of the square is faded into that slate: the image's bottom
 * edge is the character's near-black clothing, so without this it meets the
 * lighter page in a hard horizontal line that reads as the portrait being cut
 * in half.
 */
@Composable
fun PixelBackground(modifier: Modifier = Modifier, artSize: Dp = Dp.Unspecified) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(LainArtBackdrop)) {
        // Sized by the caller so the onboarding panel can be anchored to the same number.
        val art: Dp = if (artSize != Dp.Unspecified) artSize else maxWidth
        Image(
            painter = painterResource(id = R.drawable.bg_lain_awake),
            contentDescription = null,
            modifier = Modifier
                .width(art)
                .height(art)
                .align(Alignment.TopCenter),
            contentScale = ContentScale.FillBounds
        )
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
