package com.lain.assistant.network

import kotlinx.serialization.json.JsonObject

/** Provider-agnostic chat turn passed into an LlmClient. */
data class LlmMessage(
    val role: Role,
    val text: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall> = emptyList()
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

interface LlmClient {
    suspend fun send(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<LlmMessage>,
        tools: List<ToolDefinition>
    ): LlmResult
}
