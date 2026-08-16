package com.lain.assistant.network

import com.lain.assistant.data.Provider

object LlmClientFactory {
    fun create(provider: Provider): LlmClient = when (provider) {
        Provider.ANTHROPIC -> AnthropicClient(provider.apiBaseUrl)
        Provider.OPENROUTER -> OpenAiCompatibleClient(
            baseUrl = provider.apiBaseUrl,
            httpReferer = "https://lain.app",
            appTitle = "Lain"
        )
        Provider.OPENAI, Provider.GEMINI, Provider.GROK -> OpenAiCompatibleClient(provider.apiBaseUrl)
    }
}
