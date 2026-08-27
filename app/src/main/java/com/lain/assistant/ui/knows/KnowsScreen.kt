package com.lain.assistant.ui.knows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lain.assistant.ui.common.PixelButton
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainInk
import com.lain.assistant.ui.theme.LainMuted
import com.lain.assistant.ui.theme.LainNavy
import com.lain.assistant.ui.theme.LainSalmon

/**
 * What Lain is holding: everything scheduled, and everything remembered.
 *
 * Both were only reachable by asking her, which is a poor way to audit anything —
 * a list you have to request one question at a time isn't a list. It matters more
 * for memory than for alarms: facts accumulate silently across months of
 * conversation, they shape every later answer, and until now the only way to find
 * out what was in there was to guess a search term. Anything on this screen can be
 * deleted from this screen.
 */
@Composable
fun KnowsScreen(viewModel: KnowsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LainInk)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹ Back",
                style = MaterialTheme.typography.labelLarge,
                color = LainSalmon,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(vertical = 6.dp, horizontal = 2.dp)
                    .semantics { contentDescription = "Back to chat" }
            )
            Spacer(Modifier.width(14.dp))
            Text("What I'm holding", style = MaterialTheme.typography.titleMedium, color = LainCream)
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SectionHeader("Scheduled", state.tasks.size)
            }
            if (state.tasks.isEmpty()) {
                item { Empty("Nothing scheduled.") }
            }
            items(state.tasks, key = { it.id }) { task ->
                Entry(
                    title = task.describe(),
                    subtitle = null,
                    onDelete = { viewModel.cancelTask(task.id) },
                    deleteLabel = "Cancel ${task.label}"
                )
            }

            item {
                Spacer(Modifier.height(20.dp))
                SectionHeader("Remembered", state.memories.size)
            }
            if (state.memories.isEmpty()) {
                item { Empty("Nothing remembered yet. Tell her something worth keeping.") }
            }
            items(state.memories, key = { it.id }) { memory ->
                Entry(
                    title = memory.fact,
                    subtitle = "${memory.category.lowercase()} · ${memory.subject}",
                    onDelete = { viewModel.forget(memory.id) },
                    deleteLabel = "Forget ${memory.subject}"
                )
            }

            if (state.memories.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(20.dp))
                    var confirming by remember { mutableStateOf(false) }
                    PixelButton(
                        // Two taps, because there is no undo and this is everything at once.
                        text = if (confirming) "Tap again to forget everything" else "Forget everything",
                        onClick = {
                            if (confirming) {
                                viewModel.forgetAll()
                                confirming = false
                            } else {
                                confirming = true
                            }
                        }
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(text: String, count: Int) {
    Text(
        "$text · $count",
        style = MaterialTheme.typography.labelLarge,
        color = LainSalmon,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = LainMuted)
}

@Composable
private fun Entry(title: String, subtitle: String?, onDelete: () -> Unit, deleteLabel: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LainNavy)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = LainCream)
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = LainMuted)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "✕",
            style = MaterialTheme.typography.labelLarge,
            color = LainSalmon,
            modifier = Modifier
                .clickable { onDelete() }
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .semantics { contentDescription = deleteLabel }
        )
    }
}
