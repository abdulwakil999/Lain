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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.lain.assistant.data.db.ActionLogEntity
import com.lain.assistant.ui.common.PixelButton
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainInk
import com.lain.assistant.ui.theme.LainMuted
import com.lain.assistant.ui.theme.LainNavy
import com.lain.assistant.ui.theme.LainSalmon

/**
 * What Lain is holding: everything scheduled, everything remembered, and
 * everything she did.
 *
 * Both were only reachable by asking her, which is a poor way to audit anything —
 * a list you have to request one question at a time isn't a list. It matters more
 * for memory than for alarms: facts accumulate silently across months of
 * conversation, they shape every later answer, and until now the only way to find
 * out what was in there was to guess a search term. Anything on this screen can be
 * deleted from this screen.
 *
 * The third section is a different kind of thing and reads like one. Agenda and
 * Memoria are what she holds and can be edited; Historial is what she did and can
 * only be cleared entirely. It is written by the dispatcher from the real outcome
 * rather than by the model from what it believes happened — so where it and the
 * chat disagree, this is the one to believe.
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
            Text("Memoria", style = MaterialTheme.typography.titleMedium, color = LainCream)
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                // Spanish headings, English everywhere it matters. Both words read
                // clearly to someone who speaks neither — "agenda" and "memoria" are
                // near-cognates — so the flavour costs nobody comprehension.
                SectionHeader("Agenda", "scheduled", state.tasks.size)
            }
            if (state.tasks.isEmpty()) {
                item { Empty("Nada. Nothing scheduled.") }
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
                SectionHeader("Memoria", "remembered", state.memories.size)
            }
            if (state.memories.isEmpty()) {
                item { Empty("Nada todavía. Tell her something worth keeping.") }
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
                        text = if (confirming) "Tap again — borra todo" else "Forget everything",
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
            item {
                Spacer(Modifier.height(20.dp))
                SectionHeader("Historial", "what she did", state.actions.size)
            }
            if (state.actions.isEmpty()) {
                item { Empty("Nada aún. Actions appear here as she takes them.") }
            }
            items(state.actions, key = { it.id }) { entry ->
                ActionEntry(entry)
            }

            if (state.actions.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    var confirmingLog by remember { mutableStateOf(false) }
                    PixelButton(
                        // Two taps. There is no undo, and this is the only record of
                        // what happened that doesn't depend on her describing it.
                        text = if (confirmingLog) "Tap again — borra el historial" else "Clear the log",
                        onClick = {
                            if (confirmingLog) {
                                viewModel.clearActions()
                                confirmingLog = false
                            } else {
                                confirmingLog = true
                            }
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * One thing she did.
 *
 * Shaped unlike the rows above it, and deliberately: the other two sections are
 * things you can delete, and this one is a record you can only read. Outcome first,
 * because the question actually being asked of this list is almost always "did that
 * work" — and a failure that looks like every other line is a failure nobody finds.
 *
 * The "because" is what makes it an account rather than telemetry: an action with
 * no reason attached is an event log, and an event log is not what was asked for.
 */
@Composable
private fun ActionEntry(entry: ActionLogEntity) {
    val at = remember(entry.at) {
        SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(entry.at))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LainNavy)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics {
                contentDescription = "${if (entry.succeeded) "Worked" else "Failed"}: " +
                    "${entry.action} ${entry.detail}, $at"
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (entry.succeeded) "OK" else "FAILED",
                style = MaterialTheme.typography.labelLarge,
                color = if (entry.succeeded) LainSalmon else LainCream
            )
            Spacer(Modifier.width(10.dp))
            Text(
                entry.action + if (entry.detail.isNotBlank()) " · ${entry.detail}" else "",
                style = MaterialTheme.typography.labelLarge,
                color = LainCream,
                modifier = Modifier.weight(1f)
            )
            Text(at, style = MaterialTheme.typography.bodyMedium, color = LainMuted)
        }
        if (entry.outcome.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(entry.outcome, style = MaterialTheme.typography.bodyMedium, color = LainMuted)
        }
        if (entry.goal.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                "porque: ${entry.goal}",
                style = MaterialTheme.typography.bodyMedium,
                color = LainMuted
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String, gloss: String, count: Int) {
    // The gloss is part of the label rather than a tooltip, so a screen reader
    // announces it too — a heading nobody can translate is a heading nobody can use.
    Text(
        "$text · $gloss · $count",
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
