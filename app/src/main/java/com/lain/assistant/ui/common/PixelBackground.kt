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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.lain.assistant.R
import com.lain.assistant.ui.theme.LainNavyDeep

/**
 * Full portrait of Lain, sitting behind whatever UI is hovering on top.
 * The art is square, so it's fit to the screen's full width and
 * top-aligned — the whole figure stays visible instead of being cropped
 * in tight the way ContentScale.Crop would on a much taller phone screen.
 */
@Composable
fun PixelBackground(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val artSize = maxWidth
        Box(modifier = Modifier.fillMaxSize().background(LainNavyDeep))
        Image(
            painter = painterResource(id = R.drawable.bg_lain_awake),
            contentDescription = null,
            modifier = Modifier.width(artSize).height(artSize).align(Alignment.TopCenter),
            contentScale = ContentScale.FillBounds
        )
    }
}
