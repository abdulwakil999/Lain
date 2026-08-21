package com.lain.assistant.network

import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException

/**
 * Shared retry for the LLM clients.
 *
 * DNS on mobile is genuinely flaky: coming off Wi-Fi, waking from doze, or a
 * moment of weak signal all produce "No address associated with hostname"
 * (UnknownHostException) on a request that succeeds a second later. A single
 * attempt turns that into a hard failure mid-task, so transient network errors
 * are retried with backoff before giving up.
 */
internal suspend fun OkHttpClient.executeWithRetry(
    request: Request,
    maxAttempts: Int = 2
): Result<Response> {
    var lastError: IOException? = null
    repeat(maxAttempts) { attempt ->
        try {
            return Result.success(newCall(request).execute())
        } catch (e: IOException) {
            lastError = e
            // Only name-resolution and connection failures are worth repeating. A read
            // timeout means the server accepted the request and is still working on it,
            // so retrying re-does the whole generation — three attempts against a 45s
            // read timeout was over two minutes before the user saw an error, and it
            // billed the same request three times. Fail fast and say so instead.
            if (!isWorthRetrying(e) || attempt == maxAttempts - 1) {
                return Result.failure(e)
            }
            delay(400L * (attempt + 1))
        }
    }
    return Result.failure(lastError ?: IOException("Request failed"))
}

/**
 * DNS on mobile is genuinely flaky — coming off Wi-Fi, waking from doze, or a
 * moment of weak signal all produce a resolution failure on a request that
 * succeeds a second later. Those are worth one more go; nothing else is.
 */
private fun isWorthRetrying(e: IOException): Boolean =
    e is UnknownHostException || e is java.net.ConnectException || e is java.net.NoRouteToHostException

/** Turns a raw exception into something a person can act on. */
internal fun describeNetworkFailure(t: Throwable): String = when (t) {
    is UnknownHostException ->
        "Can't reach the API — the phone couldn't resolve the address. Check the internet connection; " +
            "if it's on, check Data Saver, a VPN, or Private DNS, any of which can block this."
    else -> t.message ?: "Network error"
}
