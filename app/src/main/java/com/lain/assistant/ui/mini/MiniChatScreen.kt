package com.lain.assistant.ui.mini

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lain.assistant.data.Sender
import com.lain.assistant.ui.chat.ChatViewModel
import com.lain.assistant.ui.common.PixelTextField
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainInk
import com.lain.assistant.ui.theme.LainMuted
import com.lain.assistant.ui.theme.LainNavyDeep
import com.lain.assistant.ui.theme.LainSalmon
import com.lain.assistant.ui.theme.LainSalmonDeep

/**
 * The lightweight surface the widget, Quick Settings tile and wake word open:
 * just an input bar and a faded strip of recent messages docked to the bottom,
 * over a transparent scrim rather than the whole app. Tapping the scrim
 * dismisses it, so it behaves like a sheet, not a launch.
 */
@Composable
fun MiniChatScreen(viewModel: ChatViewModel, autoListen: Boolean, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.attachTts(context)
        if (autoListen) viewModel.startVoiceInput()
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim: dismisses on tap, and deliberately see-through so whatever app the
        // user was already looking at stays visible behind Lain.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LainInk.copy(alpha = 0.55f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(LainNavyDeep.copy(alpha = 0.97f))
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lain", style = MaterialTheme.typography.titleMedium, color = LainSalmon)
                Spacer(Modifier.weight(1f))
                Text(
                    "Close",
                    style = MaterialTheme.typography.labelLarge,
                    color = LainMuted,
                    modifier = Modifier.clickable { onDismiss() }.padding(4.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages.takeLast(8), key = { it.id }) { message ->
                    val isUser = message.sender == Sender.USER
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isUser) LainCream.copy(alpha = 0.85f) else LainSalmonDeep.copy(alpha = 0.85f)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = message.text,
                                color = if (isUser) LainInk else LainCream,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            state.statusLine?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = LainMuted, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(visible = state.isBusy) {
                    Row {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(LainSalmonDeep)
                                .clickable { viewModel.stop() }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Text("STOP", color = LainCream, style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (state.isListening) LainSalmonDeep else LainSalmon.copy(alpha = 0.85f))
                        .clickable(enabled = !state.isBusy) { viewModel.startVoiceInput() }
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Text(if (state.isListening) "..." else "MIC", color = LainInk, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(6.dp))
                PixelTextField(
                    value = state.input,
                    onValueChange = viewModel::onInputChange,
                    placeholder = "what's up niceo",
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (state.input.isBlank()) LainSalmon.copy(alpha = 0.4f) else LainSalmon)
                        .clickable(enabled = state.input.isNotBlank() && !state.isSending) { viewModel.send() }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text("Send", color = LainInk, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
