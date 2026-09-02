package com.lain.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File

/**
 * Drives Nous Research's Hermes Agent through its command line.
 *
 * The CLI is the interface Hermes Agent publishes — `hermes chat`, `hermes model`,
 * `hermes config`, and a `~/.hermes/config.yaml` it reads. It also runs a local
 * dashboard with an HTTP API, and that API is deliberately not used here: it is
 * gated by a session token injected into the dashboard's own HTML at startup, which
 * makes it an internal detail rather than a contract. Building against it would work
 * until the day it silently didn't.
 *
 * So this starts `hermes chat` as a child process and talks to it the way a person
 * would. It is slower than an API and it cannot be broken by one, which for a wrapper
 * around software maintained by somebody else is the right trade.
 *
 * Nothing here assumes Hermes is installed. [probe] is the first thing the app calls,
 * and an absent binary produces instructions rather than a stack trace.
 */
class HermesAgent(
    /** Overridable, because people install CLIs in places PATH does not know about. */
    private val command: String = "hermes"
) {

    /** What the machine has, checked before anything is promised to the user. */
    sealed class Availability {
        data class Ready(val version: String, val configuredModel: String?) : Availability()
        data class Missing(val reason: String) : Availability()
    }

    /**
     * Whether Hermes Agent is here and usable.
     *
     * Runs `hermes --version` rather than looking for a file, because a binary on
     * PATH that cannot execute — wrong architecture, missing runtime, half-finished
     * install — is a different problem from an absent one and should read differently.
     */
    suspend fun probe(): Availability = withContext(Dispatchers.IO) {
        val version = runCatching {
            val process = ProcessBuilder(command, "--version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (process.exitValue() == 0) output else null
        }.getOrNull()

        if (version.isNullOrBlank()) {
            return@withContext Availability.Missing(
                "Hermes Agent isn't on this machine, or isn't on PATH. It's Nous Research's " +
                    "agent — install it, run \"hermes setup\", then reopen this. If it's installed " +
                    "somewhere unusual, point the command at it in Settings."
            )
        }
        Availability.Ready(version = version.lines().first(), configuredModel = configuredModel())
    }

    /**
     * The model Hermes is set to use, read from its own config.
     *
     * Parsed loosely on purpose: this is somebody else's file, it is shown rather
     * than acted on, and a YAML library pulled in to read one line would be a
     * dependency that breaks the day they restructure the file. Unreadable simply
     * means the field is not shown.
     */
    fun configuredModel(): String? = runCatching {
        val config = File(System.getProperty("user.home"), ".hermes/config.yaml")
        if (!config.isFile) return null
        config.readLines()
            .firstOrNull { it.trimStart().startsWith("model:") }
            ?.substringAfter("model:")
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** A line of output, tagged so the UI can tell an answer from a failure. */
    data class Line(val text: String, val isError: Boolean = false)

    /**
     * Sends one prompt to a `hermes chat` session and streams what comes back.
     *
     * A process per exchange rather than one long-lived session. That loses
     * conversational context inside Hermes, and buys something worth more for a
     * wrapper: no state to get out of sync, no half-dead child process to detect, and
     * a cancelled request that is actually cancelled. Context is kept on this side,
     * in [Conversation], and sent with the prompt.
     */
    fun ask(prompt: String, context: String = ""): Flow<Line> = channelFlow {
        val payload = if (context.isBlank()) prompt else "$context\n\n$prompt"

        val process = runCatching {
            ProcessBuilder(command, "chat")
                .redirectErrorStream(true)
                .start()
        }.getOrElse {
            send(Line("Couldn't start $command: ${it.message}", isError = true))
            return@channelFlow
        }

        val reader = launch(Dispatchers.IO) {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (!isActive) return@forEachLine
                    channel.trySend(Line(line))
                }
            }
        }

        runCatching {
            withContext(Dispatchers.IO) {
                val writer: BufferedWriter = process.outputStream.bufferedWriter()
                writer.write(payload)
                writer.newLine()
                writer.flush()
                // Closing stdin is what tells an interactive REPL the turn is over.
                // Without it the child waits for more input and the UI waits forever.
                writer.close()
                process.waitFor()
            }
        }.onFailure {
            send(Line("Hermes stopped: ${it.message}", isError = true))
        }

        reader.join()

        // Killed rather than left behind: a cancelled turn that leaves a child process
        // holding a model session is how a desktop app quietly eats a machine.
        invokeOnClose { runCatching { process.destroy() } }
    }

    /** Keeps the thread of a conversation on this side, since each ask is a new process. */
    class Conversation(private val maxTurns: Int = 8) {
        private val turns = ArrayDeque<Pair<String, String>>()

        fun remember(prompt: String, reply: String) {
            turns.addLast(prompt to reply)
            while (turns.size > maxTurns) turns.removeFirst()
        }

        fun clear() = turns.clear()

        /** The recent exchange, formatted for prepending to the next prompt. */
        fun asContext(): String =
            if (turns.isEmpty()) "" else turns.joinToString("\n") { (q, a) -> "You asked: $q\nYou answered: $a" }
    }
}
