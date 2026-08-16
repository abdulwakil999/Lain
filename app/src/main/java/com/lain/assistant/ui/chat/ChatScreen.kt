package com.lain.assistant.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lain.assistant.data.ChatMessage
import com.lain.assistant.data.Sender
import com.lain.assistant.ui.common.AccessibilityServiceBanner
import com.lain.assistant.ui.common.HoveringPanel
import com.lain.assistant.ui.common.PixelTextField
import com.lain.assistant.ui.common.RequestCorePermissionsOnce
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainInk
import com.lain.assistant.ui.theme.LainSalmon
import com.lain.assistant.ui.theme.LainSalmonDeep

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.attachTts(context)
        viewModel.onAwaken()
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    RequestCorePermissionsOnce()

    Box(modifier = Modifier.fillMaxSize()) {
        AwakeningBackground(awake = state.hasAwakened)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 48.dp, bottom = 160.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { AccessibilityServiceBanner(modifier = Modifier.fillMaxWidth()) }
            items(state.messages, key = { it.id }) { message -> MessageBubble(message) }
            if (state.isSending) {
                item { ThinkingBubble() }
            }
        }

        HoveringPanel(modifier = Modifier.align(Alignment.BottomCenter)) {
            state.error?.let {
                Text(it, color = LainSalmon, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(2.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelTextField(
                    value = state.input,
                    onValueChange = viewModel::onInputChange,
                    placeholder = "what's up niceo",
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (state.input.isBlank()) LainSalmon.copy(alpha = 0.4f) else LainSalmon)
                        .clickable(enabled = state.input.isNotBlank()) { viewModel.send() }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text("Send", color = LainInk, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.sender == Sender.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
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

@Composable
private fun ThinkingBubble() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(LainSalmonDeep.copy(alpha = 0.92f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(2.dp), color = LainCream, strokeWidth = 2.dp)
        }
    }
}
