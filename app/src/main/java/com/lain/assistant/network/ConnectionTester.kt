package com.lain.assistant.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.lain.assistant.data.ApiKeys
import com.lain.assistant.data.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * A one-tap "is this actually going to work" check.
 *
 * Sends a real (tiny) request to the configured provider and model, so it
 * catches the things that only show up in anger: no DNS, a blocked background
 * connection, a bad key, a model slug that doesn't exist on that account.
 */
class ConnectionTester(private val context: Context) {

    // Shares the app's pool, so a successful test also leaves a warm connection
    // behind for the first real message.
    private val http = Http.quick

    suspend fun test(provider: Provider, modelId: String?, apiKey: String?): String = withContext(Dispatchers.IO) {
        if (!hasNetwork()) {
            return@withContext "No usable network. Wi-Fi/data is off or has no internet."
        }
        if (apiKey.isNullOrBlank()) return@withContext "No API key saved for ${provider.displayName}."
        if (modelId.isNullOrBlank()) return@withContext "No model selected."

        // What is actually sent, and what was wrong with what was typed. The repair
        // is reported either way: a key that only works after cleaning is a key the
        // user should know was cleaned, and a key that fails anyway is much easier to
        // think about once the invisible characters are ruled out.
        val key = ApiKeys.clean(apiKey)
        if (key.isEmpty()) {
            return@withContext "That isn't a usable key — there's nothing in it but spaces or punctuation."
        }
        val note = listOfNotNull(ApiKeys.problem(apiKey), ApiKeys.mismatch(provider, apiKey))
            .joinToString(" ")
            .takeIf { it.isNotEmpty() }

        val isAnthropic = provider == Provider.ANTHROPIC
        val url = provider.apiBaseUrl.trimEnd('/') + if (isAnthropic) "/messages" else "/chat/completions"

        val body = buildJsonObject {
            put("model", modelId)
            put("max_tokens", 8)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "hi")
                })
            })
        }

        val request = try {
            val builder = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
            if (isAnthropic) {
                builder.addHeader("x-api-key", key).addHeader("anthropic-version", "2023-06-01")
            } else {
                builder.addHeader("Authorization", "Bearer $key")
            }
            builder.build()
        } catch (t: Throwable) {
            // OkHttp refuses a header value it cannot legally send. That used to throw
            // out of here into a coroutine with nothing catching it, so a key with one
            // stray character took the screen down instead of explaining itself.
            return@withContext "That key has a character that can't be sent in a request " +
                "header. Delete it and paste it again — a copy from a web page often " +
                "brings an invisible one along."
        }

        val outcome = http.executeWithRetry(request, maxAttempts = 2).fold(
            onSuccess = { response ->
                response.use {
                    val raw = it.body?.string().orEmpty()
                    if (it.isSuccessful) "Working — ${provider.displayName} replied on $modelId."
                    // Whatever went wrong, the provider said why. Repeating its own
                    // words beats a canned line about the key — which for a 403 was
                    // simply untrue, and sent people to re-paste a working key.
                    else ProviderError.describe(it.code, raw, provider)
                }
            },
            onFailure = { describeNetworkFailure(it) }
        )
        if (note == null) outcome else "$outcome\n\n$note"
    }

    private fun hasNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
