package com.lain.assistant.data

/**
 * @param keyPageUrl where a person actually gets a key for this provider.
 *
 * Held here rather than in the settings screen because it belongs to the provider,
 * not to the layout — and because "enter your API key" with nowhere to get one is
 * the step that stops people using the app at all. Every provider's own key page,
 * one tap from the field that wants it.
 */
enum class Provider(
    val displayName: String,
    val apiBaseUrl: String,
    val keyPageUrl: String
) {
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/", "https://openrouter.ai/keys"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com/v1/", "https://console.anthropic.com/settings/keys"),
    OPENAI("OpenAI", "https://api.openai.com/v1/", "https://platform.openai.com/api-keys"),
    // Uses Gemini's OpenAI-compatibility endpoint so it can share the same
    // chat-completions client as OpenRouter/OpenAI/Grok instead of needing a
    // fourth bespoke request/response schema.
    GEMINI(
        "Google Gemini",
        "https://generativelanguage.googleapis.com/v1beta/openai/",
        "https://aistudio.google.com/apikey"
    ),
    GROK("xAI Grok", "https://api.x.ai/v1/", "https://console.x.ai/")
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
    val strongAtTools: Boolean = false,
    /** True when the model can actually look at a screenshot, from the provider's own catalogue. */
    val supportsVision: Boolean = false,
    /** Advertised context window, used to order the free list by what can hold a real task. */
    val contextTokens: Int = 0
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
        // Verified against OpenRouter's live catalogue: every slug below currently
        // resolves, is priced at zero, and advertises function-calling. Ordered
        // biggest-context-first, which is the order the live fetch also uses, so the
        // picker looks the same whether or not the network call succeeded.
        ModelInfo("nvidia/nemotron-3-ultra-550b-a55b:free", "Nemotron 3 Ultra 550B (Free)", Provider.OPENROUTER, isFree = true, contextTokens = 1_000_000),
        ModelInfo("nvidia/nemotron-3.5-lightning:free", "Nemotron 3.5 Lightning (Free)", Provider.OPENROUTER, isFree = true, contextTokens = 1_000_000),
        ModelInfo("google/gemma-4-31b-it:free", "Gemma 4 31B (Free)", Provider.OPENROUTER, isFree = true, supportsVision = true, contextTokens = 262_144),
        ModelInfo("nvidia/nemotron-3-super-120b-a12b:free", "Nemotron 3 Super 120B (Free)", Provider.OPENROUTER, isFree = true, contextTokens = 262_144),
        ModelInfo("z-ai/glm-5.2:free", "GLM 5.2 (Free)", Provider.OPENROUTER, isFree = true, contextTokens = 256_000),
        ModelInfo("nvidia/nemotron-3-nano-30b-a3b:free", "Nemotron 3 Nano 30B (Free)", Provider.OPENROUTER, isFree = true, contextTokens = 256_000),
        ModelInfo("openai/gpt-oss-20b:free", "GPT-OSS 20B (Free)", Provider.OPENROUTER, isFree = true, contextTokens = 131_072),

        // --- OpenRouter: paid, and the only tier that reliably drives multi-step
        // automation. Slugs verified against OpenRouter's live catalogue. ---
        ModelInfo("anthropic/claude-sonnet-5", "Claude Sonnet 5", Provider.OPENROUTER, recommended = true, strongAtTools = true, supportsVision = true, contextTokens = 1_000_000),
        ModelInfo("google/gemini-3.7-flash", "Gemini 3.7 Flash", Provider.OPENROUTER, strongAtTools = true, supportsVision = true, contextTokens = 1_048_576),
        ModelInfo("openai/gpt-5-mini", "GPT-5 Mini (cheap)", Provider.OPENROUTER, strongAtTools = true, supportsVision = true, contextTokens = 400_000),
        ModelInfo("openai/gpt-5", "GPT-5", Provider.OPENROUTER, strongAtTools = true, supportsVision = true, contextTokens = 400_000),
        ModelInfo("anthropic/claude-opus-5", "Claude Opus 5", Provider.OPENROUTER, strongAtTools = true, supportsVision = true, contextTokens = 1_000_000),
        ModelInfo("x-ai/grok-4.6", "Grok 4.6", Provider.OPENROUTER, strongAtTools = true, supportsVision = true, contextTokens = 500_000),

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

    /**
     * The best free model to fall back on when a chosen one turns out to be dead.
     * Free-tier slugs are the ones that disappear, so this is deliberately the
     * roomiest survivor rather than a fixed favourite.
     */
    fun bestFreeFor(provider: Provider): ModelInfo? =
        forProvider(provider).filter { it.isFree }.maxByOrNull { it.contextTokens }
}
