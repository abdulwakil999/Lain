package com.lain.assistant.network

import com.lain.assistant.data.Provider
import java.util.concurrent.ConcurrentHashMap

/**
 * One client per provider, created once.
 *
 * This used to construct a new client on every turn. That mattered more than it
 * looks: each one carried its own OkHttp stack, so the connection pool and TLS
 * session cache were thrown away and rebuilt between messages. The clients now
 * share [Http], but they're still cached here so nothing about the request path
 * allocates per turn.
 */
object LlmClientFactory {

    private val cache = ConcurrentHashMap<Provider, LlmClient>()

    fun create(provider: Provider): LlmClient = cache.getOrPut(provider) {
        when (provider) {
            Provider.ANTHROPIC -> AnthropicClient(provider.apiBaseUrl)
            // OpenRouter uses these two for attribution. The referer used to claim
            // "https://lain.app", a domain this project does not own — a made-up
            // origin is exactly the kind of thing an edge network scores against you,
            // and it was pointing at nothing anyway. This one is real.
            Provider.OPENROUTER -> OpenAiCompatibleClient(
                baseUrl = provider.apiBaseUrl,
                httpReferer = "https://github.com/abdulwakil999/Lain",
                appTitle = "Lain"
            )
            Provider.OPENAI, Provider.GEMINI, Provider.GROK -> OpenAiCompatibleClient(provider.apiBaseUrl)
        }
    }
}
