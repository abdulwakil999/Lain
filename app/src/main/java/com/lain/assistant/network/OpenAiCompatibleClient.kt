package com.lain.assistant.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * One client for every provider that speaks the OpenAI `chat/completions`
 * dialect: OpenRouter, OpenAI itself, xAI Grok, and Gemini via its
 * OpenAI-compatibility endpoint.
 */
class OpenAiCompatibleClient(
    private val baseUrl: String,
    private val httpReferer: String? = null,
    private val appTitle: String? = null
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun send(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<LlmMessage>,
        tools: List<ToolDefinition>
    ): LlmResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildMessagesArray(systemPrompt, history))
            if (tools.isNotEmpty()) {
                put("tools", buildToolsArray(tools))
                put("tool_choice", "auto")
            }
        }

        val requestBuilder = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        httpReferer?.let { requestBuilder.addHeader("HTTP-Referer", it) }
        appTitle?.let { requestBuilder.addHeader("X-Title", it) }

        try {
            http.newCall(requestBuilder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext LlmResult.Error("HTTP ${response.code}: ${raw.take(500)}")
                }
                parseResponse(raw)
            }
        } catch (t: Throwable) {
            LlmResult.Error(t.message ?: "Network error")
        }
    }

    private fun buildMessagesArray(systemPrompt: String, history: List<LlmMessage>): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("role", "system")
            put("content", systemPrompt)
        })
        for (msg in history) {
            add(buildJsonObject {
                put("role", when (msg.role) {
                    LlmMessage.Role.SYSTEM -> "system"
                    LlmMessage.Role.USER -> "user"
                    LlmMessage.Role.ASSISTANT -> "assistant"
                    LlmMessage.Role.TOOL -> "tool"
                })
                put("content", msg.text)
                if (msg.role == LlmMessage.Role.TOOL && msg.toolCallId != null) {
                    put("tool_call_id", msg.toolCallId)
                }
                if (msg.toolCalls.isNotEmpty()) {
                    put("tool_calls", buildJsonArray {
                        for (call in msg.toolCalls) {
                            add(buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", call.name)
                                    put("arguments", call.argumentsJson)
                                })
                            })
                        }
                    })
                }
            })
        }
    }

    private fun buildToolsArray(tools: List<ToolDefinition>): JsonArray = buildJsonArray {
        for (tool in tools) {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                })
            })
        }
    }

    private fun parseResponse(raw: String): LlmResult {
        val root = json.parseToJsonElement(raw).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return LlmResult.Error("Empty response")
        val message = choice["message"]?.jsonObject ?: return LlmResult.Error("Malformed response")

        val toolCallsJson = message["tool_calls"]?.jsonArray
        if (!toolCallsJson.isNullOrEmpty()) {
            val calls = toolCallsJson.map { entry ->
                val obj = entry.jsonObject
                val function = obj["function"]!!.jsonObject
                ToolCall(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = function["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    argumentsJson = function["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                )
            }
            return LlmResult.ToolCalls(calls)
        }

        val content = message["content"]?.jsonPrimitive?.contentOrNull ?: ""
        return LlmResult.Message(content)
    }
}
