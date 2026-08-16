package com.lain.assistant.network

import com.lain.assistant.data.ModelInfo
import com.lain.assistant.data.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * OpenRouter's free-tier lineup rotates fast enough that a hardcoded list
 * goes stale in weeks (it already has, twice). This hits OpenRouter's public
 * `GET /models` — no API key required — and keeps only entries that are
 * both actually free (prompt/completion price == 0) and actually support
 * function-calling (Lain is useless without tools), so whatever shows up in
 * the picker is guaranteed to work today rather than reflecting whatever
 * was true when this code was written.
 */
class OpenRouterModelsClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchFreeToolCapableModels(): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("https://openrouter.ai/api/v1/models").get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("HTTP ${response.code}"))
                }
                val raw = response.body?.string().orEmpty()
                Result.success(parse(raw))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun parse(raw: String): List<ModelInfo> {
        val root = json.parseToJsonElement(raw).jsonObject
        val entries = root["data"]?.jsonArray ?: return emptyList()

        return entries.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!id.endsWith(":free")) return@mapNotNull null

            val pricing = obj["pricing"]?.jsonObject
            val promptPrice = pricing?.get("prompt")?.jsonPrimitive?.contentOrNull
            val completionPrice = pricing?.get("completion")?.jsonPrimitive?.contentOrNull
            if (promptPrice != "0" || completionPrice != "0") return@mapNotNull null

            val supportsTools = obj["supported_parameters"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.contains("tools") == true
            if (!supportsTools) return@mapNotNull null

            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.substringBefore(" (free)") ?: id
            ModelInfo(id = id, label = "$name (Free)", provider = Provider.OPENROUTER, isFree = true)
        }
    }
}
