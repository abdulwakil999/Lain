package com.lain.assistant.network

import android.os.Build
import com.lain.assistant.BuildConfig
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The one HTTP stack the whole app shares.
 *
 * Every client used to build its own `OkHttpClient`, and `LlmClientFactory`
 * built a fresh client for every single turn. That quietly defeated connection
 * pooling: the pool, the dispatcher's thread pool and — most expensively — the
 * TLS session cache all live *on the client instance*, so each user message paid
 * for a new TCP handshake and a full TLS negotiation before a byte of the request
 * was sent. On mobile that is a few hundred milliseconds of pure overhead per
 * message, on top of whatever the model then takes.
 *
 * One shared instance means the second message onward reuses a warm, authenticated
 * connection. OkHttp is explicitly designed to be used this way — a single client
 * shared across an app, with per-request configuration done on the request.
 */
object Http {

    /**
     * Streaming responses stay open for as long as the model is generating, so the
     * read timeout has to be generous — but only for the stream. A *connect* timeout
     * should be short: if the socket hasn't opened in a few seconds, the network is
     * the problem and waiting longer won't fix it.
     */
    /**
     * Who is calling, said properly.
     *
     * Left alone, OkHttp identifies itself as "okhttp/4.x" and nothing else. Edge
     * networks in front of these APIs — and the DNS filters, VPNs and school or ISP
     * proxies users sit behind — treat an unidentified library client as a bot far
     * more readily than an app that says what it is, and the way that arrives is a
     * 403 with an HTML body that looks exactly like the provider rejecting a key.
     *
     * This is not a proven cause of any particular report. It is the cheapest way to
     * stop being the anonymous client, and it costs one header.
     */
    private val identify = Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Lain/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"
                )
                .build()
        )
    }

    val shared: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(identify)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        // Keep connections alive well past a single exchange: a multi-step task is
        // several requests to the same host inside a minute or two.
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    /**
     * For requests that should fail fast rather than hang — the connection test and
     * the model-catalogue fetch. Shares the parent's pool and dispatcher, so it costs
     * nothing extra.
     */
    val quick: OkHttpClient = shared.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * Warms the connection to a host so the first real request doesn't pay for the
     * handshake.
     *
     * Called when the app comes to the foreground: by the time someone has typed a
     * message or finished speaking, the TLS session is usually already established,
     * which takes the handshake off the critical path entirely for the first message
     * too — the one where the delay is most noticeable.
     */
    fun prewarm(baseUrl: String) {
        runCatching {
            val host = baseUrl.toHttpUrlOrNull() ?: return
            // A HEAD to the API root is enough to open and negotiate the socket. The
            // response is irrelevant and deliberately ignored — a 404 warms the
            // connection exactly as well as a 200.
            val request = okhttp3.Request.Builder().url(host).head().build()
            shared.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = Unit
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                }
            })
        }
    }
}
