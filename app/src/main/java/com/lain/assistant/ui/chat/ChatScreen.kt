package com.lain.assistant.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lain.assistant.AppContainer
import com.lain.assistant.data.ChatMessage
import com.lain.assistant.data.Sender
import com.lain.assistant.ui.LainViewModelFactory
import com.lain.assistant.ui.common.AccessibilityServiceBanner
import com.lain.assistant.ui.common.HoveringPanel
import com.lain.assistant.ui.common.rememberLainWindow
import com.lain.assistant.ui.common.PixelTextField
import com.lain.assistant.ui.common.RequestCorePermissionsOnce
import com.lain.assistant.ui.settings.SettingsScreen
import com.lain.assistant.ui.settings.SettingsViewModel
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainInk
import com.lain.assistant.ui.theme.LainMuted
import com.lain.assistant.ui.theme.LainSalmon
import com.lain.assistant.ui.theme.LainSalmonDeep

@Composable
fun ChatScreen(viewModel: ChatViewModel, container: AppContainer, autoListenToken: Long? = null) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.attachTts(context)
        viewModel.onAwaken()
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    // Streaming text grows the last item rather than adding one, so it needs its own
    // trigger to keep the newest words on screen. Scrolling without animation here —
    // an animated scroll per token fights itself and stutters.
    LaunchedEffect(state.streamingText) {
        if (state.streamingText != null && state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.size)
        }
    }

    LaunchedEffect(autoListenToken) {
        if (autoListenToken != null) viewModel.startVoiceInput()
    }

    RequestCorePermissionsOnce()

    if (showSettings) {
        val factory = remember { LainViewModelFactory(container) }
        val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
        SettingsScreen(viewModel = settingsViewModel, onBack = { showSettings = false })
        return
    }

    val window = rememberLainWindow()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The portrait is square, and how big it's allowed to be is a function of both
        // screen dimensions — see LainWindow.artSize. Drawing it at the full window width
        // is what made it swallow a tablet screen and crowd out the conversation.
        val artHeight: Dp = window.artSize

        // Full-bleed backdrop painted in the artwork's own slate, so the portrait and the
        // space beneath it read as one continuous image rather than a picture with a seam.
        AwakeningBackground(awake = state.hasAwakened, artSize = artHeight)

        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.fillMaxWidth().height(artHeight))

            LazyColumn(
                state = listState,
                // Centred and width-capped so lines stay readable instead of stretching
                // across a tablet; on a phone contentMaxWidth is the full width anyway.
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = window.contentMaxWidth),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 150.dp,
                    start = window.gutter,
                    end = window.gutter
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { AccessibilityServiceBanner(modifier = Modifier.fillMaxWidth()) }
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message, maxWidth = window.bubbleMaxWidth)
                }
                // The reply currently being generated, rendered token by token. It lives
                // outside `messages` so a cancelled turn leaves nothing behind, and it is
                // what turns "several seconds of nothing" into text that starts moving
                // after one round trip.
                state.streamingText?.let { partial ->
                    item(key = "streaming") {
                        StreamingBubble(partial, maxWidth = window.bubbleMaxWidth)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = window.topInset, end = window.gutter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallPill(
                text = if (state.conversationMode) "HANDS-FREE" else "TAP-TO-TALK",
                onClick = { viewModel.setConversationMode(!state.conversationMode) },
                dimmed = !state.conversationMode
            )
            Spacer(Modifier.width(8.dp))
            SmallPill(
                text = if (state.isMuted) "MUTED" else "VOICE",
                onClick = viewModel::toggleMute,
                dimmed = state.isMuted
            )
            Spacer(Modifier.width(8.dp))
            SmallPill(text = "Settings", onClick = { showSettings = true })
        }

        HoveringPanel(
            modifier = Modifier.align(Alignment.BottomCenter),
            maxWidth = window.contentMaxWidth,
            horizontalPadding = window.gutter
        ) {
            // An irreversible action waiting on an answer. Shown as the exact thing that
            // will happen, so approving is an informed decision rather than a reflex.
            state.pendingConfirmation?.let { question ->
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Text(question, color = LainCream, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(LainSalmon)
                                .clickable { viewModel.send("yes") }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Yes", color = LainInk, style = MaterialTheme.typography.labelLarge)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(LainSalmonDeep.copy(alpha = 0.5f))
                                .clickable { viewModel.send("no") }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No", color = LainCream, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            // Stop-speaking. Distinct from STOP (which kills the task) and from the MUTED
            // pill (a lasting preference): this shuts up the sentence currently being read
            // aloud and nothing else, which is what someone wants when they've finished
            // reading a long answer before Lain has finished saying it.
            AnimatedVisibility(
                visible = state.isSpeaking,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(LainSalmon)
                            .clickable { viewModel.silence() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "◼  STOP SPEAKING",
                            color = LainInk,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            // Live status so a long automation run never looks frozen.
            AnimatedVisibility(visible = state.statusLine != null) {
                Text(
                    text = state.statusLine.orEmpty(),
                    color = LainMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            state.error?.let {
                Text(it, color = LainSalmon, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // The stop button grows out of the bar only while something is running.
                AnimatedVisibility(
                    visible = state.isBusy,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally()
                ) {
                    Row {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(LainSalmonDeep)
                                .clickable { viewModel.stop() }
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Text("STOP", color = LainCream, style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (state.isListening) LainSalmonDeep else LainSalmon.copy(alpha = 0.85f))
                        .clickable(enabled = !state.isBusy) { viewModel.startVoiceInput() }
                        .padding(horizontal = 14.dp, vertical = 14.dp)
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
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text("Send", color = LainInk, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun SmallPill(text: String, onClick: () -> Unit, dimmed: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(LainSalmonDeep.copy(alpha = if (dimmed) 0.5f else 0.85f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, color = LainCream, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The in-flight reply. Styled like one of Lain's messages so the transition to the
 * finished bubble is invisible, with a caret so it reads as still being written.
 */
@Composable
private fun StreamingBubble(text: String, maxWidth: Dp) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(RoundedCornerShape(10.dp))
                .background(LainSalmonDeep.copy(alpha = 0.92f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = if (text.isEmpty()) "…" else "$text▍",
                color = LainCream,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, maxWidth: Dp) {
    val isUser = message.sender == Sender.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                // Derived from the window rather than a fixed 300dp, which was a third of
                // a tablet and most of a small phone.
                .widthIn(max = maxWidth)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isUser) LainCream.copy(alpha = 0.92f) else LainSalmonDeep.copy(alpha = 0.92f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.text,
                color = if (isUser) LainInk else LainCream,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
