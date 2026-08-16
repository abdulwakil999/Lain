package com.lain.assistant.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

class AnthropicClient(private val baseUrl: String) : LlmClient {

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
            put("max_tokens", 2048)
            put("system", systemPrompt)
            put("messages", buildMessagesArray(history))
            if (tools.isNotEmpty()) {
                put("tools", buildToolsArray(tools))
            }
        }

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            http.newCall(request).execute().use { response ->
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

    private fun buildMessagesArray(history: List<LlmMessage>): JsonArray = buildJsonArray {
        for (msg in history) {
            when (msg.role) {
                LlmMessage.Role.SYSTEM -> continue // Anthropic takes system separately.
                LlmMessage.Role.USER -> add(buildJsonObject {
                    put("role", "user")
                    if (msg.images.isNotEmpty()) {
                        put("content", buildJsonArray {
                            for (image in msg.images) {
                                add(buildJsonObject {
                                    put("type", "image")
                                    put("source", buildJsonObject {
                                        put("type", "base64")
                                        put("media_type", "image/jpeg")
                                        put("data", image)
                                    })
                                })
                            }
                            if (msg.text.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", msg.text)
                                })
                            }
                        })
                    } else {
                        put("content", msg.text)
                    }
                })
                LlmMessage.Role.ASSISTANT -> add(buildJsonObject {
                    put("role", "assistant")
                    if (msg.toolCalls.isNotEmpty()) {
                        put("content", buildJsonArray {
                            if (msg.text.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", msg.text)
                                })
                            }
                            for (call in msg.toolCalls) {
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", call.id)
                                    put("name", call.name)
                                    put("input", json.parseToJsonElement(call.argumentsJson))
                                })
                            }
                        })
                    } else {
                        put("content", msg.text)
                    }
                })
                LlmMessage.Role.TOOL -> add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", msg.toolCallId ?: "")
                            put("content", msg.text)
                        })
                    })
                })
            }
        }
    }

    private fun buildToolsArray(tools: List<ToolDefinition>): JsonArray = buildJsonArray {
        for (tool in tools) {
            add(buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", tool.parameters)
            })
        }
    }

    private fun parseResponse(raw: String): LlmResult {
        val root = json.parseToJsonElement(raw).jsonObject
        val blocks = root["content"]?.jsonArray ?: return LlmResult.Error("Malformed response")

        val toolCalls = mutableListOf<ToolCall>()
        val textBuilder = StringBuilder()
        for (block in blocks) {
            val obj = block.jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> textBuilder.append(obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "tool_use" -> toolCalls += ToolCall(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    argumentsJson = obj["input"]?.toString() ?: "{}"
                )
            }
        }

        return if (toolCalls.isNotEmpty()) LlmResult.ToolCalls(toolCalls) else LlmResult.Message(textBuilder.toString())
    }
}
