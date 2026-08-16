package com.lain.assistant.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainNavyDeep

/**
 * The card that hovers over the base of Lain's portrait — every onboarding
 * question and the chat input bar all sit inside one of these, sharp-cornered
 * on top only so it reads as "docked" to the bottom edge, pixel-thick border.
 */
@Composable
fun HoveringPanel(
    modifier: Modifier = Modifier,
    content: ColumnScopeContent
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(LainNavyDeep.copy(alpha = 0.92f))
                .border(3.dp, LainCream.copy(alpha = 0.15f), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            content()
        }
    }
}

private typealias ColumnScopeContent = @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
