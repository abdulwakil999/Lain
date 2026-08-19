package com.lain.assistant.agent

import com.lain.assistant.tools.FailureKind

/**
 * Working notes for a single multi-step request.
 *
 * Without this the model has to re-derive "what have I already tried?" from the
 * raw transcript every round, which is exactly where weaker models lose the plot
 * and start repeating themselves. Holding it explicitly lets us feed back a short,
 * factual progress note instead.
 */
class TaskState {

    data class Attempt(
        val tool: String,
        val args: String,
        val succeeded: Boolean,
        val failure: FailureKind? = null,
        val note: String = ""
    )

    private val attempts = mutableListOf<Attempt>()
    private val failureCounts = mutableMapOf<String, Int>()

    var appSwitches = 0
        private set

    fun record(tool: String, args: String, succeeded: Boolean, failure: FailureKind?, note: String) {
        attempts += Attempt(tool, args, succeeded, failure, note.take(160))
        if (tool == "open_app" || tool == "close_app") appSwitches++
        if (!succeeded) {
            val key = signature(tool, args)
            failureCounts[key] = (failureCounts[key] ?: 0) + 1
        }
    }

    fun signature(tool: String, args: String) = "$tool::$args"

    /** How many times this exact call has already failed. */
    fun failuresFor(tool: String, args: String): Int = failureCounts[signature(tool, args)] ?: 0

    /** True once an approach has demonstrably stopped working. */
    fun isExhausted(tool: String, args: String): Boolean = failuresFor(tool, args) >= 2

    fun hasSucceeded(tool: String): Boolean = attempts.any { it.tool == tool && it.succeeded }

    /**
     * Compact progress note injected when a task starts wandering. Deliberately
     * factual — it tells the model what happened, not what to think.
     */
    fun progressNote(): String? {
        if (attempts.size < 3) return null
        val done = attempts.filter { it.succeeded }.map { it.tool }.distinct()
        val failed = attempts.filter { !it.succeeded }
        return buildString {
            append("Progress so far — ")
            if (done.isEmpty()) append("nothing has succeeded yet. ")
            else append("succeeded: ${done.joinToString(", ")}. ")
            if (failed.isNotEmpty()) {
                val summary = failed.groupBy { it.tool }
                    .map { (tool, list) -> "$tool x${list.size} (${list.last().failure?.label ?: "failed"})" }
                append("failed: ${summary.joinToString(", ")}. ")
            }
            append("Don't repeat what already failed the same way — change approach or stop and explain.")
        }
    }

    fun reset() {
        attempts.clear()
        failureCounts.clear()
        appSwitches = 0
    }
}
