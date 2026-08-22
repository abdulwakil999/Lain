package com.lain.assistant.agent

import com.lain.assistant.tools.FailureKind

/**
 * Layer 1 of the memory architecture: what is happening *right now*.
 *
 * The other three layers answer "what do I know" (long-term), "what were we
 * saying" (recent) and "what happened earlier in this thread" (summary). None of
 * them answer the question that actually derails multi-step tasks: *what am I in
 * the middle of doing, and what has already been tried?*
 *
 * Without that held explicitly, a model has to re-derive it from the raw
 * transcript on every round. Strong models mostly manage; weak ones lose the
 * thread and start repeating a step that already failed, which is the single most
 * common way a free model turns a five-step job into fifteen. Holding it here
 * costs nothing at inference time and lets a compact, factual digest go back
 * instead of the whole history.
 *
 * Everything here is deliberately observed rather than asserted: steps are marked
 * done because a tool reported success, not because the model said so. That is
 * what makes the digest trustworthy enough to act on.
 */
class WorkingMemory {

    /** One thing the agent tried, and what actually came back. */
    data class Attempt(
        val tool: String,
        val args: String,
        val succeeded: Boolean,
        val failure: FailureKind? = null,
        val note: String = ""
    )

    /** A step in the plan for the current objective. */
    data class Step(val description: String, var done: Boolean = false)

    companion object {
        /** Beyond this a repeated identical call is a loop, not persistence. */
        private const val EXHAUSTED_AFTER = 2

        /** Attempts kept verbatim. Older ones survive only as counts in the digest. */
        private const val MAX_ATTEMPTS = 40

        /** Keeps one tool's chatter from crowding the digest. */
        private const val MAX_RESULTS_IN_DIGEST = 4
        private const val RESULT_CHARS = 120
    }

    // ------------------------------------------------------------- objective

    /** What the user actually asked for, in their words. */
    var objective: String = ""
        private set

    private val steps = mutableListOf<Step>()
    private val attempts = mutableListOf<Attempt>()
    private val failureCounts = mutableMapOf<String, Int>()

    /** Facts established by tools this turn — the answers the task is accumulating. */
    private val findings = mutableListOf<String>()

    var appSwitches = 0
        private set

    /** Starts a new task. Everything from the previous one is dropped. */
    fun begin(objective: String) {
        reset()
        this.objective = objective.trim().take(300)
    }

    // ----------------------------------------------------------------- plan

    /**
     * Records the steps the agent intends to take.
     *
     * Only used when the model volunteers a plan; nothing here forces one. A plan
     * that exists gives the digest something to mark off, which is what stops a
     * long task drifting.
     */
    fun setPlan(descriptions: List<String>) {
        steps.clear()
        descriptions.map { it.trim() }.filter { it.isNotEmpty() }.take(8).forEach {
            steps += Step(it.take(120))
        }
    }

    fun completeStep(description: String) {
        val needle = description.trim().lowercase()
        steps.firstOrNull { !it.done && it.description.lowercase().contains(needle) }?.done = true
    }

    val unresolvedSteps: List<String> get() = steps.filter { !it.done }.map { it.description }
    val completedSteps: List<String> get() = steps.filter { it.done }.map { it.description }

    // -------------------------------------------------------------- attempts

    fun record(tool: String, args: String, succeeded: Boolean, failure: FailureKind?, note: String) {
        attempts += Attempt(tool, args, succeeded, failure, note.take(RESULT_CHARS))
        if (attempts.size > MAX_ATTEMPTS) attempts.removeAt(0)

        if (tool == "open_app" || tool == "close_app") appSwitches++
        if (succeeded) {
            // A successful action is evidence a planned step is done, so the plan
            // stays honest without the model having to keep telling us.
            completeStep(tool)
            if (note.isNotBlank()) {
                findings += note.take(RESULT_CHARS)
                if (findings.size > MAX_RESULTS_IN_DIGEST * 2) findings.removeAt(0)
            }
        } else {
            val key = signature(tool, args)
            failureCounts[key] = (failureCounts[key] ?: 0) + 1
        }
    }

    fun signature(tool: String, args: String) = "$tool::$args"

    /** How many times this exact call has already failed. */
    fun failuresFor(tool: String, args: String): Int = failureCounts[signature(tool, args)] ?: 0

    /** True once an approach has demonstrably stopped working. */
    fun isExhausted(tool: String, args: String): Boolean = failuresFor(tool, args) >= EXHAUSTED_AFTER

    fun hasSucceeded(tool: String): Boolean = attempts.any { it.tool == tool && it.succeeded }

    val attemptCount: Int get() = attempts.size

    // ---------------------------------------------------------------- digest

    /**
     * The working-memory block injected into context.
     *
     * Written as flat statements of fact rather than instructions: it tells the
     * model what the state *is* and lets it decide. Instructions here would
     * compete with the system prompt, and a weak model handles one voice better
     * than two.
     *
     * @return null when there is nothing worth saying — a first round, or a task
     *   that hasn't done anything yet. An empty block is noise that costs tokens.
     */
    fun digest(): String? {
        if (attempts.isEmpty() && steps.isEmpty()) return null

        return buildString {
            append("CURRENT TASK\n")
            if (objective.isNotBlank()) append("Goal: ").append(objective).append('\n')

            if (steps.isNotEmpty()) {
                val done = completedSteps
                val left = unresolvedSteps
                if (done.isNotEmpty()) append("Done: ").append(done.joinToString("; ")).append('\n')
                if (left.isNotEmpty()) append("Still to do: ").append(left.joinToString("; ")).append('\n')
            }

            val succeeded = attempts.filter { it.succeeded }
            if (succeeded.isNotEmpty()) {
                append("Worked: ")
                append(succeeded.map { it.tool }.distinct().joinToString(", "))
                append('\n')
            }

            // What the tools actually established, which is the part the model needs
            // to answer with and the part most likely to fall out of a short window.
            if (findings.isNotEmpty()) {
                append("Found so far:\n")
                findings.takeLast(MAX_RESULTS_IN_DIGEST).forEach {
                    append("- ").append(it).append('\n')
                }
            }

            val failed = attempts.filter { !it.succeeded }
            if (failed.isNotEmpty()) {
                append("Failed: ")
                append(
                    failed.groupBy { it.tool }
                        .map { (tool, list) -> "$tool x${list.size} (${list.last().failure?.label ?: "failed"})" }
                        .joinToString(", ")
                )
                append('\n')
            }

            val exhausted = failureCounts.filter { it.value >= EXHAUSTED_AFTER }.keys
                .map { it.substringBefore("::") }
                .distinct()
            if (exhausted.isNotEmpty()) {
                append("Already exhausted (do not retry): ").append(exhausted.joinToString(", ")).append('\n')
            }
        }.trimEnd()
    }

    /**
     * Nudge injected once a task starts wandering, separate from [digest] because
     * it is advice rather than state.
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
        objective = ""
        steps.clear()
        attempts.clear()
        failureCounts.clear()
        findings.clear()
        appSwitches = 0
    }
}
