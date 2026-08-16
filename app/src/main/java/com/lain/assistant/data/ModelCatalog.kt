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
    val supportsTools: Boolean = true
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
        ModelInfo("deepseek/deepseek-r1:free", "DeepSeek R1 (Free)", Provider.OPENROUTER, isFree = true, recommended = true),
        ModelInfo("deepseek/deepseek-chat:free", "DeepSeek V3 (Free)", Provider.OPENROUTER, isFree = true),
        ModelInfo("qwen/qwen-2.5-72b-instruct:free", "Qwen 2.5 72B (Free)", Provider.OPENROUTER, isFree = true),
        ModelInfo("meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B (Free)", Provider.OPENROUTER, isFree = true),
        ModelInfo("google/gemini-2.0-flash-exp:free", "Gemini 2.0 Flash (Free)", Provider.OPENROUTER, isFree = true),

        // --- OpenRouter: paid, top-tier via a single unified key ---
        ModelInfo("anthropic/claude-sonnet-5", "Claude Sonnet 5", Provider.OPENROUTER),
        ModelInfo("openai/gpt-5", "GPT-5", Provider.OPENROUTER),
        ModelInfo("google/gemini-3-pro", "Gemini 3 Pro", Provider.OPENROUTER),

        // --- Anthropic direct ---
        ModelInfo("claude-opus-5", "Claude Opus 5", Provider.ANTHROPIC, recommended = true),
        ModelInfo("claude-sonnet-5", "Claude Sonnet 5", Provider.ANTHROPIC),
        ModelInfo("claude-haiku-4-5-20251001", "Claude Haiku 4.5", Provider.ANTHROPIC),

        // --- OpenAI direct ---
        ModelInfo("gpt-5", "GPT-5", Provider.OPENAI, recommended = true),
        ModelInfo("gpt-5-mini", "GPT-5 Mini", Provider.OPENAI),

        // --- Gemini direct ---
        ModelInfo("gemini-3-pro", "Gemini 3 Pro", Provider.GEMINI, recommended = true),
        ModelInfo("gemini-3-flash", "Gemini 3 Flash", Provider.GEMINI),

        // --- Grok direct ---
        ModelInfo("grok-4", "Grok 4", Provider.GROK, recommended = true),
        ModelInfo("grok-4-mini", "Grok 4 Mini", Provider.GROK)
    )

    fun forProvider(provider: Provider): List<ModelInfo> = models.filter { it.provider == provider }

    fun recommendedFor(provider: Provider): ModelInfo? =
        forProvider(provider).firstOrNull { it.recommended } ?: forProvider(provider).firstOrNull()
}
