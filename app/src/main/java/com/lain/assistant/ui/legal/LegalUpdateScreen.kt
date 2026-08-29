package com.lain.assistant.ui.legal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lain.assistant.legal.LegalText
import com.lain.assistant.ui.common.PixelButton
import com.lain.assistant.ui.common.PixelChoiceChip
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainNavy
import com.lain.assistant.ui.theme.LainNavyDeep
import com.lain.assistant.ui.theme.LainSalmon

/**
 * The documents again, for someone who already agreed to an older version.
 *
 * The privacy policy says a changed policy is shown again before you carry on using
 * the app. Nothing in the code did that — [LegalText.VERSION] existed and was read
 * by nobody — so the sentence was a claim about behaviour that did not happen, in
 * the one document where that matters most.
 *
 * What changed comes first and the full text underneath, because a re-consent screen
 * that opens with three thousand unchanged words is how a policy gets accepted
 * unread for the second time. There is no dismiss: the alternative to agreeing is
 * uninstalling, and pretending otherwise would be the same dishonesty in a different
 * shape.
 */
@Composable
fun LegalUpdateScreen(onAccept: () -> Unit) {
    var showing by remember { mutableStateOf(Doc.CHANGES) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LainNavyDeep)
            .padding(20.dp)
    ) {
        Text(
            "The privacy policy and terms have changed",
            style = MaterialTheme.typography.titleLarge,
            color = LainSalmon
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "You agreed to an earlier version. Here's what's different, and both " +
                "documents in full.",
            style = MaterialTheme.typography.bodyMedium,
            color = LainCream
        )
        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelChoiceChip(
                "What changed",
                showing == Doc.CHANGES,
                { showing = Doc.CHANGES },
                modifier = Modifier.weight(1f)
            )
            PixelChoiceChip(
                "Privacy",
                showing == Doc.PRIVACY,
                { showing = Doc.PRIVACY },
                modifier = Modifier.weight(1f)
            )
            PixelChoiceChip(
                "Terms",
                showing == Doc.TERMS,
                { showing = Doc.TERMS },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Takes whatever is left rather than a fixed height, so the text gets
                // the room a tablet has and still fits on a small phone.
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(LainNavy)
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    when (showing) {
                        Doc.CHANGES -> LegalText.WHATS_CHANGED
                        Doc.PRIVACY -> LegalText.PRIVACY
                        Doc.TERMS -> LegalText.TERMS
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = LainCream
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        PixelButton(
            text = "I've read them. Continue.",
            onClick = onAccept,
            modifier = Modifier.semantics {
                contentDescription = "Accept the updated privacy policy and terms"
            }
        )
    }
}

private enum class Doc { CHANGES, PRIVACY, TERMS }
