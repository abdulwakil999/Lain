package com.lain.assistant.network

import kotlinx.serialization.json.JsonObject

/** Provider-agnostic chat turn passed into an LlmClient. */
data class LlmMessage(
    val role: Role,
    val text: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    /** Base64-encoded JPEG frames (no data-URI prefix) attached to a USER turn — screenshots, camera stills. */
    val images: List<String> = emptyList()
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
}

/** A function Lain can call, described once and handed to every provider. */
data class ToolDefinition(
    val name: String,
    val description: String,
    /** JSON Schema for the function's arguments object. */
    val parameters: JsonObject
)

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

sealed class LlmResult {
    data class Message(val text: String) : LlmResult()
    data class ToolCalls(val calls: List<ToolCall>) : LlmResult()
    data class Error(val message: String) : LlmResult()
}

/**
 * Per-request shaping. These are the two knobs that most affect how long a turn
 * takes, and both were previously left at the provider's default.
 */
data class RequestTuning(
    /**
     * Ceiling on the reply. Unbounded, a reasoning-heavy free model will happily
     * spend thirty seconds and several thousand tokens deliberating before emitting
     * a one-line tool call — which is most of why a simple phone task felt slow.
     */
    val maxTokens: Int = 1200,
    /**
     * Whether the step needs real deliberation. Mid-task tool selection almost never
     * does; the final answer to a hard question does. Providers that expose a
     * reasoning-effort control get told which this is.
     */
    val deliberate: Boolean = false
) {
    companion object {
        /** Picking the next tool call: short, decisive, no essay. */
        val TOOL_STEP = RequestTuning(maxTokens = 700, deliberate = false)

        /** Answering the user in prose. */
        val ANSWER = RequestTuning(maxTokens = 1400, deliberate = true)

        /** Spoken replies are two sentences by design, so the ceiling can be tight. */
        val SPOKEN = RequestTuning(maxTokens = 400, deliberate = false)

        /** Background summarisation and fact extraction. */
        val HOUSEKEEPING = RequestTuning(maxTokens = 400, deliberate = false)
    }
}

/**
 * Incremental output from a streaming request.
 *
 * The point of streaming is not that the whole answer arrives sooner — it doesn't
 * — but that the first words do. Non-streaming, time-to-first-word equals total
 * generation time, which on a free model is several seconds of a blank screen.
 */
sealed class StreamEvent {
    /** A fragment of the assistant's prose, in order. */
    data class Delta(val text: String) : StreamEvent()

    /** The model asked for tools instead of answering. Emitted once, at the end. */
    data class Tools(val calls: List<ToolCall>) : StreamEvent()

    /** Generation finished cleanly. [text] is the complete assembled message. */
    data class Done(val text: String) : StreamEvent()

    data class Failed(val message: String) : StreamEvent()
}

interface LlmClient {
    suspend fun send(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<LlmMessage>,
        tools: List<ToolDefinition>,
        tuning: RequestTuning = RequestTuning()
    ): LlmResult

    /**
     * Same request, delivered incrementally.
     *
     * Default implementation runs the blocking path and emits the result in one
     * go, so a provider without streaming support still works — it just doesn't
     * get the latency benefit.
     */
    fun sendStreaming(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<LlmMessage>,
        tools: List<ToolDefinition>,
        tuning: RequestTuning = RequestTuning()
    ): kotlinx.coroutines.flow.Flow<StreamEvent> = kotlinx.coroutines.flow.flow {
        when (val result = send(apiKey, model, systemPrompt, history, tools, tuning)) {
            is LlmResult.Message -> {
                emit(StreamEvent.Delta(result.text))
                emit(StreamEvent.Done(result.text))
            }
            is LlmResult.ToolCalls -> emit(StreamEvent.Tools(result.calls))
            is LlmResult.Error -> emit(StreamEvent.Failed(result.message))
        }
    }
}
