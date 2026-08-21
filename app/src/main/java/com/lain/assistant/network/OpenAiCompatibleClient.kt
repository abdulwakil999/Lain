package com.lain.assistant.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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

    private val isOpenRouter = baseUrl.contains("openrouter.ai")

    // Shared across the app so connections and TLS sessions survive between turns.
    // This client used to be rebuilt per request, which threw both away every time.
    private val http = Http.shared

    override suspend fun send(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<LlmMessage>,
        tools: List<ToolDefinition>,
        tuning: RequestTuning
    ): LlmResult = withContext(Dispatchers.IO) {
        http.executeWithRetry(buildRequest(apiKey, model, systemPrompt, history, tools, tuning, stream = false)).fold(
            onSuccess = { response ->
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        LlmResult.Error("HTTP ${it.code}: ${raw.take(500)}")
                    } else {
                        try {
                            parseResponse(raw)
                        } catch (t: Throwable) {
                            LlmResult.Error("Couldn't read the model's reply: ${t.message}")
                        }
                    }
                }
            },
            onFailure = { LlmResult.Error(describeNetworkFailure(it)) }
        )
    }

    private fun buildRequest(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<LlmMessage>,
        tools: List<ToolDefinition>,
        tuning: RequestTuning,
        stream: Boolean
    ): Request {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildMessagesArray(systemPrompt, history))
            // Without a ceiling, a reasoning model spends as long as it likes before
            // emitting a one-line tool call. This is the single cheapest latency win
            // available and costs nothing in answer quality at these sizes.
            put("max_tokens", tuning.maxTokens)
            if (stream) put("stream", true)
            if (tools.isNotEmpty()) {
                put("tools", buildToolsArray(tools))
                put("tool_choice", "auto")
            }
            // OpenRouter's normalised reasoning control. Providers that don't
            // recognise it ignore unknown top-level fields, so this is safe to send.
            if (isOpenRouter) {
                put("reasoning", buildJsonObject {
                    put("effort", if (tuning.deliberate) "medium" else "low")
                })
            }
        }

        val builder = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (stream) builder.addHeader("Accept", "text/event-stream")
        httpReferer?.let { builder.addHeader("HTTP-Referer", it) }
        appTitle?.let { builder.addHeader("X-Title", it) }
        return builder.build()
    }

    /**
     * Server-sent events, read line by line as they arrive.
     *
     * The blocking path calls `body.string()`, which does not return until the model
     * has finished generating — so the user stares at nothing for the entire
     * generation. Here the socket is read incrementally and each `delta` is emitted
     * the moment it lands, which is what actually moves time-to-first-word from
     * "however long the whole answer takes" to "one network round trip".
     *
     * Tool calls arrive in the same stream, fragmented: the name comes in one chunk
     * and the JSON arguments dribble in across many more, keyed by index. They're
     * accumulated and emitted once at the end, because a half-parsed argument object
     * is useless to the caller.
     */
    override fun sendStreaming(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<LlmMessage>,
        tools: List<ToolDefinition>,
        tuning: RequestTuning
    ): Flow<StreamEvent> = flow {
        val request = buildRequest(apiKey, model, systemPrompt, history, tools, tuning, stream = true)
        val response = try {
            http.newCall(request).execute()
        } catch (t: Throwable) {
            emit(StreamEvent.Failed(describeNetworkFailure(t)))
            return@flow
        }

        response.use {
            if (!it.isSuccessful) {
                emit(StreamEvent.Failed("HTTP ${it.code}: ${it.body?.string().orEmpty().take(500)}"))
                return@flow
            }
            val source = it.body?.source()
            if (source == null) {
                emit(StreamEvent.Failed("Empty response"))
                return@flow
            }

            val text = StringBuilder()
            val toolAccumulator = sortedMapOf<Int, PartialToolCall>()

            while (true) {
                val line = try {
                    source.readUtf8Line()
                } catch (t: Throwable) {
                    emit(StreamEvent.Failed(describeNetworkFailure(t)))
                    return@flow
                } ?: break

                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break

                val delta = runCatching { parseStreamChunk(payload, toolAccumulator) }.getOrNull() ?: continue
                if (delta.isNotEmpty()) {
                    text.append(delta)
                    emit(StreamEvent.Delta(delta))
                }
            }

            val calls = toolAccumulator.values.mapNotNull { partial ->
                val name = partial.name ?: return@mapNotNull null
                ToolCall(
                    id = partial.id ?: name,
                    name = name,
                    argumentsJson = partial.arguments.toString().ifBlank { "{}" }
                )
            }
            when {
                calls.isNotEmpty() -> emit(StreamEvent.Tools(calls))
                else -> emit(StreamEvent.Done(text.toString()))
            }
        }
    }.flowOn(Dispatchers.IO)

    /** A tool call being assembled across many stream chunks. */
    private class PartialToolCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    /** @return the prose fragment in this chunk, if any. Tool fragments go into [into]. */
    private fun parseStreamChunk(payload: String, into: MutableMap<Int, PartialToolCall>): String {
        val choice = json.parseToJsonElement(payload).jsonObject["choices"]
            ?.jsonArray?.firstOrNull()?.jsonObject ?: return ""
        val delta = choice["delta"]?.jsonObject ?: return ""

        delta["tool_calls"]?.jsonArray?.forEach { entry ->
            val obj = entry.jsonObject
            // The index is what ties fragments of the same call together; without it a
            // parallel tool call would have its arguments interleaved with another's.
            val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
            val partial = into.getOrPut(index) { PartialToolCall() }
            obj["id"]?.jsonPrimitive?.contentOrNull?.let { partial.id = it }
            obj["function"]?.jsonObject?.let { fn ->
                fn["name"]?.jsonPrimitive?.contentOrNull?.let { partial.name = it }
                fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { partial.arguments.append(it) }
            }
        }

        return delta["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
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
                if (msg.role == LlmMessage.Role.USER && msg.images.isNotEmpty()) {
                    put("content", buildJsonArray {
                        if (msg.text.isNotBlank()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", msg.text)
                            })
                        }
                        for (image in msg.images) {
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject {
                                    put("url", "data:image/jpeg;base64,$image")
                                })
                            })
                        }
                    })
                } else {
                    put("content", msg.text)
                }
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
