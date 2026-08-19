package com.lain.assistant.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun test(provider: Provider, modelId: String?, apiKey: String?): String = withContext(Dispatchers.IO) {
        if (!hasNetwork()) {
            return@withContext "No usable network. Wi-Fi/data is off or has no internet."
        }
        if (apiKey.isNullOrBlank()) return@withContext "No API key saved for ${provider.displayName}."
        if (modelId.isNullOrBlank()) return@withContext "No model selected."

        val isAnthropic = provider == Provider.ANTHROPIC
        val url = provider.apiBaseUrl.trimEnd('/') + if (isAnthropic) "/messages" else "/chat/completions"

        val body = buildJsonObject {
            put("model", modelId)
            put("max_tokens", 8)
            if (isAnthropic) {
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", "hi")
                    })
                })
            } else {
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", "hi")
                    })
                })
            }
        }

        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (isAnthropic) {
            builder.addHeader("x-api-key", apiKey).addHeader("anthropic-version", "2023-06-01")
        } else {
            builder.addHeader("Authorization", "Bearer $apiKey")
        }

        http.executeWithRetry(builder.build(), maxAttempts = 2).fold(
            onSuccess = { response ->
                response.use {
                    val raw = it.body?.string().orEmpty()
                    when {
                        it.isSuccessful -> "Working — ${provider.displayName} replied on $modelId."
                        it.code == 401 || it.code == 403 -> "Key rejected (HTTP ${it.code}). Check the API key."
                        it.code == 404 -> "Model \"$modelId\" not found on this account (HTTP 404). Pick another model."
                        it.code == 402 -> "Out of credit on ${provider.displayName} (HTTP 402)."
                        it.code == 429 -> "Rate limited (HTTP 429) — common on free models. Try a ★ model."
                        else -> "HTTP ${it.code}: ${raw.take(200)}"
                    }
                }
            },
            onFailure = { describeNetworkFailure(it) }
        )
    }

    private fun hasNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
