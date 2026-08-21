package com.lain.assistant.network

import com.lain.assistant.data.ModelInfo
import com.lain.assistant.data.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
 * the picker is guaranteed to exist today rather than reflecting whatever
 * was true when this code was written.
 *
 * "Exists" is not the same as "works", though, which is the other half of the
 * problem: the endpoint happily lists 2-billion-parameter models that accept a
 * tools array and then never emit a tool call. Those are filtered and the rest
 * are ordered so the ones most likely to survive a multi-step task are at the
 * top of the picker, where someone scanning the list will land on them.
 */
class OpenRouterModelsClient {

    companion object {
        /**
         * Below this, a model cannot hold the system prompt, a recent window and a
         * screen listing at the same time — it will start dropping the earlier half of
         * its own instructions mid-task.
         */
        private const val MIN_USABLE_CONTEXT = 60_000

        /**
         * Parameter counts aren't in the API, but they're in the names, and size is the
         * best available proxy for whether a model will actually chain tool calls
         * rather than describing what it would do. Anything advertising under ~8B is
         * excluded: they are reliably unable to drive the phone.
         */
        private val tinyModel = Regex("\\b([0-7](?:\\.\\d)?)b\\b", RegexOption.IGNORE_CASE)
    }

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

    internal fun parse(raw: String): List<ModelInfo> {
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

            val context = obj["context_length"]?.jsonPrimitive?.intOrNull ?: 0
            if (context < MIN_USABLE_CONTEXT) return@mapNotNull null

            val rawName = obj["name"]?.jsonPrimitive?.contentOrNull?.substringBefore(" (free)") ?: id
            if (tinyModel.containsMatchIn(rawName)) return@mapNotNull null

            // Vision comes straight from the catalogue rather than being guessed from
            // the slug, so look_at_screen is only offered to models that can see.
            val vision = obj["architecture"]?.jsonObject
                ?.get("input_modalities")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.contains("image") == true

            ModelInfo(
                id = id,
                label = "$rawName (Free)",
                provider = Provider.OPENROUTER,
                isFree = true,
                supportsVision = vision,
                contextTokens = context
            )
        }
            // Roomiest first. Context length is the one capability signal the API gives
            // us, and it tracks tool-following ability closely enough to be a better
            // default order than whatever order the endpoint happened to return.
            .sortedByDescending { it.contextTokens }
    }
}
