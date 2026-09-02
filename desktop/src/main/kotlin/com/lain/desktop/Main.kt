package com.lain.desktop

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Lain Desktop — the same assistant, on a machine, running on Hermes Agent.
 *
 * Deliberately not a port of the phone app. The phone's whole design is about doing
 * things *to a phone*: the router exists so a torch does not cost a network call, and
 * none of that means anything here. What carries over is the voice and the honesty —
 * a desktop that says plainly when Hermes isn't installed rather than showing an
 * empty box that never answers.
 *
 * It drives the `hermes` CLI, which is the interface Nous Research publishes. The
 * dashboard's HTTP API is deliberately untouched: it is gated by a token injected
 * into its own HTML at startup, which makes it internal, and building on it would
 * work right up until it quietly didn't.
 */
private val Ink = Color(0xFF15161D)
private val Navy = Color(0xFF232634)
private val Cream = Color(0xFFF2E8DC)
private val Salmon = Color(0xFFE08D79)
private val Muted = Color(0xFF8A8FA3)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(width = 900.dp, height = 700.dp),
        title = "Lain Desktop"
    ) {
        LainDesktop()
    }
}

private data class Exchange(val prompt: String, val reply: String, val failed: Boolean = false)

@Composable
private fun LainDesktop() {
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("hermes") }
    var availability by remember { mutableStateOf<HermesAgent.Availability?>(null) }
    var input by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf("") }
    var running by remember { mutableStateOf<Job?>(null) }
    val history = remember { mutableStateListOf<Exchange>() }
    val conversation = remember { HermesAgent.Conversation() }

    // Probed once at startup, and again whenever the command is changed — someone
    // who has just corrected the path expects the next check to use it.
    LaunchedEffect(command) {
        availability = null
        availability = HermesAgent(command).probe()
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Ink).padding(20.dp)
    ) {
        Text("Lain Desktop", style = MaterialTheme.typography.headlineSmall, color = Salmon)
        Spacer(Modifier.height(4.dp))

        when (val state = availability) {
            null -> Text("Looking for Hermes Agent…", color = Muted)

            is HermesAgent.Availability.Missing -> {
                Text(state.reason, color = Muted)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Path to the hermes command") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            is HermesAgent.Availability.Ready -> Text(
                state.configuredModel?.let { "${state.version} · model: $it" } ?: state.version,
                color = Muted
            )
        }

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            history.forEach { exchange ->
                Bubble(exchange.prompt, mine = true)
                Bubble(exchange.reply, mine = false, failed = exchange.failed)
            }
            if (streaming.isNotBlank()) Bubble(streaming, mine = false)
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Ask her something") },
                enabled = availability is HermesAgent.Availability.Ready,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))

            if (running != null) {
                Button(
                    onClick = { running?.cancel(); running = null; streaming = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = Navy)
                ) { Text("Stop", color = Cream) }
            } else {
                Button(
                    onClick = {
                        val prompt = input.trim()
                        if (prompt.isEmpty()) return@Button
                        input = ""
                        streaming = ""
                        running = scope.launch {
                            val collected = StringBuilder()
                            var failed = false
                            HermesAgent(command).ask(prompt, conversation.asContext())
                                .collect { line ->
                                    if (line.isError) failed = true
                                    collected.appendLine(line.text)
                                    streaming = collected.toString().trim()
                                }
                            val reply = collected.toString().trim()
                                .ifBlank { "Hermes returned nothing." }
                            history += Exchange(prompt, reply, failed)
                            if (!failed) conversation.remember(prompt, reply)
                            streaming = ""
                            running = null
                        }
                    },
                    enabled = availability is HermesAgent.Availability.Ready && input.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Salmon)
                ) { Text("Send", color = Ink) }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, failed: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (mine) Cream else Navy)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            SelectionContainer {
                Text(
                    text,
                    color = when {
                        mine -> Ink
                        failed -> Salmon
                        else -> Cream
                    },
                    // Monospace throughout: what comes back is CLI output, and its
                    // alignment is part of what it says.
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
