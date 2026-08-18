package com.lain.assistant.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.lain.assistant.R
import com.lain.assistant.ui.theme.LainArtBackdrop

/**
 * Full portrait of Lain, sitting behind whatever UI hovers on top. The square
 * artwork is drawn at full screen width and top-aligned so the whole figure is
 * visible, and the surrounding area is painted in the art's own backdrop slate
 * ([LainArtBackdrop]) so there's no visible seam where the image ends.
 */
@Composable
fun PixelBackground(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(LainArtBackdrop)) {
        val artSize = maxWidth
        Image(
            painter = painterResource(id = R.drawable.bg_lain_awake),
            contentDescription = null,
            modifier = Modifier
                .width(artSize)
                .height(artSize)
                .align(Alignment.TopCenter),
            contentScale = ContentScale.FillBounds
        )
    }
}
