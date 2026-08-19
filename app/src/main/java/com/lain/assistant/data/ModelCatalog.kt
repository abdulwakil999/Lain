package com.lain.assistant.data

enum class Provider(val displayName: String, val apiBaseUrl: String) {
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com/v1/"),
    OPENAI("OpenAI", "https://api.openai.com/v1/"),
    // Uses Gemini's OpenAI-compatibility endpoint so it can share the same
    // chat-completions client as OpenRouter/OpenAI/Grok instead of needing a
    // fourth bespoke request/response schema.
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/"),
    GROK("xAI Grok", "https://api.x.ai/v1/")
}

data class ModelInfo(
    val id: String,
    val label: String,
    val provider: Provider,
    val isFree: Boolean = false,
    val recommended: Boolean = false,
    val supportsTools: Boolean = true,
    /**
     * Whether this model is actually strong enough to drive multi-step phone
     * automation. Free models can *accept* tool definitions and still be poor at
     * chaining them — they stall, repeat calls, or narrate instead of acting.
     * This is what separates "it works" from "it works reliably".
     */
    val strongAtTools: Boolean = false
)

/**
 * Curated model list per provider. OpenRouter's free-tier roster in
 * particular rotates and gets rate-limited or deprecated fairly often, so
 * treat this as a sane default set, not a permanent source of truth — the
 * app should refresh it from GET {baseUrl}models at runtime and merge in
 * anything new, falling back to this list if that call fails.
 */
object ModelCatalog {

    val defaultProvider = Provider.OPENROUTER

    val models: List<ModelInfo> = listOf(
        // --- OpenRouter: free tier, chosen for reasoning + tool-call reliability ---
        // NOTE: this list is a fallback only. OpenRouter's free tier rotates
        // hard and fast — the previous version of this list (deepseek-r1,
        // llama-3.3-70b, qwen-2.5-72b, gemini-2.0-flash-exp, all :free) was
        // 100% dead within weeks, all 404ing. The app fetches the live list
        // from OpenRouter at runtime (see OpenRouterModelsClient) and only
        // falls back to these hardcoded entries if that call fails, so this
        // set existing works today is a nice-to-have, not load-bearing.
        ModelInfo("openai/gpt-oss-20b:free", "GPT-OSS 20B (Free)", Provider.OPENROUTER, isFree = true),
        ModelInfo("nvidia/nemotron-3-nano-30b-a3b:free", "Nemotron 3 Nano 30B (Free)", Provider.OPENROUTER, isFree = true),
        ModelInfo("nvidia/nemotron-3-super-120b-a12b:free", "Nemotron 3 Super 120B (Free)", Provider.OPENROUTER, isFree = true),
        ModelInfo("google/gemma-4-31b-it:free", "Gemma 4 31B (Free)", Provider.OPENROUTER, isFree = true),
        ModelInfo("nvidia/nemotron-nano-9b-v2:free", "Nemotron Nano 9B (Free)", Provider.OPENROUTER, isFree = true),

        // --- OpenRouter: paid, and the only tier that reliably drives multi-step
        // automation. Slugs verified against OpenRouter's live catalogue. ---
        ModelInfo("anthropic/claude-sonnet-5", "Claude Sonnet 5", Provider.OPENROUTER, recommended = true, strongAtTools = true),
        ModelInfo("openai/gpt-5", "GPT-5", Provider.OPENROUTER, strongAtTools = true),
        ModelInfo("openai/gpt-5-mini", "GPT-5 Mini (cheap)", Provider.OPENROUTER, strongAtTools = true),
        ModelInfo("anthropic/claude-opus-5", "Claude Opus 5", Provider.OPENROUTER, strongAtTools = true),
        ModelInfo("google/gemini-3.7-flash", "Gemini 3.7 Flash", Provider.OPENROUTER, strongAtTools = true),
        ModelInfo("x-ai/grok-4.6", "Grok 4.6", Provider.OPENROUTER, strongAtTools = true),

        // --- Anthropic direct ---
        ModelInfo("claude-sonnet-5", "Claude Sonnet 5", Provider.ANTHROPIC, recommended = true, strongAtTools = true),
        ModelInfo("claude-opus-5", "Claude Opus 5", Provider.ANTHROPIC, strongAtTools = true),
        ModelInfo("claude-haiku-4-5-20251001", "Claude Haiku 4.5", Provider.ANTHROPIC, strongAtTools = true),

        // --- OpenAI direct ---
        ModelInfo("gpt-5", "GPT-5", Provider.OPENAI, recommended = true, strongAtTools = true),
        ModelInfo("gpt-5-mini", "GPT-5 Mini", Provider.OPENAI, strongAtTools = true),

        // --- Gemini direct ---
        ModelInfo("gemini-3.7-flash", "Gemini 3.7 Flash", Provider.GEMINI, recommended = true, strongAtTools = true),

        // --- Grok direct ---
        ModelInfo("grok-4.6", "Grok 4.6", Provider.GROK, recommended = true, strongAtTools = true)
    )

    fun forProvider(provider: Provider): List<ModelInfo> = models.filter { it.provider == provider }

    fun recommendedFor(provider: Provider): ModelInfo? =
        forProvider(provider).firstOrNull { it.recommended } ?: forProvider(provider).firstOrNull()
}
