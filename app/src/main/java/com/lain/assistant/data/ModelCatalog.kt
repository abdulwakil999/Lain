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
    val contextTokens: Int = 0,
    /**
     * Whether the model thinks in tokens before it answers.
     *
     * Not a quality signal — a mechanical one, and it decides how much room a turn
     * needs. Reasoning tokens are generated against the same `max_tokens` ceiling as
     * the answer even when the provider is asked to withhold them, so a reasoning
     * model given the 700 tokens that suit a direct one spends the lot thinking and
     * emits no tool call at all. That is "it ran out of room before it got to the
     * action", and it is why nearly every free model looked incapable of acting.
     */
    val emitsReasoning: Boolean = false
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
        // Verified against OpenRouter's live catalogue on the day of writing: every
        // slug below resolves, is priced at zero, and advertises function-calling.
        // Three that used to be here (glm-5.2:free, nemotron-3-nano-30b:free,
        // gpt-oss-20b:free) had already stopped being free, which is what the
        // picker's "stopped existing" notice was reporting.
        // The free tier is ordered to put the ones that hold a character first, not
        // the ones with the biggest benchmark. A reasoning model asked to be somebody
        // narrates its way there and arrives flat; an instruction-tuned chat model
        // just answers in voice, which is the whole job on this tier. Gemma leads for
        // that reason, with Dots3-Note behind it on length.
        ModelInfo("google/gemma-4-31b-it:free", "Gemma 4 31B (Free)", Provider.OPENROUTER, isFree = true, supportsVision = true, contextTokens = 262_144, emitsReasoning = true),
        ModelInfo("google/gemma-4-26b-a4b-it:free", "Gemma 4 26B (Free)", Provider.OPENROUTER, isFree = true, supportsVision = true, contextTokens = 262_144, emitsReasoning = true),
        // Inkling and Inkling Small are deliberately absent. They are listed as free
        // and answer a request for their metadata like any other model, but asked to
        // actually generate they return 403: "only available on agentic harnesses".
        // Nothing in the catalogue says so — the refusal is the only place it is
        // stated — so the app learns it at runtime and hides them, and there is no
        // sense shipping a list that puts them in front of somebody first.
        ModelInfo("nvidia/nemotron-3-ultra-550b-a55b:free", "Nemotron 3 Ultra 550B (Free)", Provider.OPENROUTER, isFree = true, contextTokens = 1_000_000, emitsReasoning = true),
        ModelInfo("nvidia/nemotron-3.5-lightning:free", "Nemotron 3.5 Lightning (Free)", Provider.OPENROUTER, isFree = true, contextTokens = 1_000_000, emitsReasoning = true),
        ModelInfo("dots-studio/dots-3-note-preview:free", "Dots3-Note (Free)", Provider.OPENROUTER, isFree = true, supportsVision = true, contextTokens = 512_000, emitsReasoning = true),
        ModelInfo("nvidia/nemotron-3-super-120b-a12b:free", "Nemotron 3 Super 120B (Free)", Provider.OPENROUTER, isFree = true, contextTokens = 262_144, emitsReasoning = true),
        ModelInfo("inclusionai/ling-3.0-flash-sante:free", "Ling 3.0 Flash (Free)", Provider.OPENROUTER, isFree = true, contextTokens = 262_144, emitsReasoning = true),
        ModelInfo("cohere/north-mini-code:free", "North Mini (Free)", Provider.OPENROUTER, isFree = true, contextTokens = 256_000, emitsReasoning = true),
        ModelInfo("liquid/lfm-2.5-2.6b:free", "LFM 2.5 (Free)", Provider.OPENROUTER, isFree = true, contextTokens = 65_536, emitsReasoning = true),

        // --- OpenRouter: paid, and the only tier that reliably drives multi-step
        // automation. Slugs verified against OpenRouter's live catalogue. ---
        ModelInfo("anthropic/claude-sonnet-5", "Claude Sonnet 5", Provider.OPENROUTER, recommended = true, strongAtTools = true, supportsVision = true, contextTokens = 1_000_000, emitsReasoning = true),
        ModelInfo("openai/gpt-6-astra", "GPT-6 Astra", Provider.OPENROUTER, strongAtTools = true, supportsVision = true, contextTokens = 1_050_000, emitsReasoning = true),
        ModelInfo("openai/gpt-6-astra-pro", "GPT-6 Astra Pro", Provider.OPENROUTER, strongAtTools = true, supportsVision = true, contextTokens = 1_050_000, emitsReasoning = true),
        ModelInfo("anthropic/claude-fable-5.1", "Claude Fable 5.1", Provider.OPENROUTER, strongAtTools = true, supportsVision = true, contextTokens = 1_000_000, emitsReasoning = true),
        ModelInfo("google/gemini-3.7-flash", "Gemini 3.7 Flash", Provider.OPENROUTER, strongAtTools = true, supportsVision = true, contextTokens = 1_048_576, emitsReasoning = true),
        ModelInfo("openai/gpt-5-mini", "GPT-5 Mini (cheap)", Provider.OPENROUTER, strongAtTools = true, supportsVision = true, contextTokens = 400_000, emitsReasoning = true),
        ModelInfo("openai/gpt-5", "GPT-5", Provider.OPENROUTER, strongAtTools = true, supportsVision = true, contextTokens = 400_000, emitsReasoning = true),
        ModelInfo("anthropic/claude-opus-5", "Claude Opus 5", Provider.OPENROUTER, strongAtTools = true, supportsVision = true, contextTokens = 1_000_000, emitsReasoning = true),
        ModelInfo("x-ai/grok-4.6", "Grok 4.6", Provider.OPENROUTER, strongAtTools = true, supportsVision = true, contextTokens = 500_000, emitsReasoning = true),

        // --- Anthropic direct ---
        ModelInfo("claude-sonnet-5", "Claude Sonnet 5", Provider.ANTHROPIC, recommended = true, strongAtTools = true, emitsReasoning = true),
        ModelInfo("claude-opus-5", "Claude Opus 5", Provider.ANTHROPIC, strongAtTools = true, emitsReasoning = true),
        ModelInfo("claude-fable-5-1", "Claude Fable 5.1", Provider.ANTHROPIC, strongAtTools = true, emitsReasoning = true),
        ModelInfo("claude-haiku-4-5-20251001", "Claude Haiku 4.5", Provider.ANTHROPIC, strongAtTools = true, emitsReasoning = true),

        // --- OpenAI direct ---
        ModelInfo("gpt-6-astra", "GPT-6 Astra", Provider.OPENAI, recommended = true, strongAtTools = true, emitsReasoning = true),
        ModelInfo("gpt-6-astra-pro", "GPT-6 Astra Pro", Provider.OPENAI, strongAtTools = true, emitsReasoning = true),
        ModelInfo("gpt-5", "GPT-5", Provider.OPENAI, strongAtTools = true, emitsReasoning = true),
        ModelInfo("gpt-5-mini", "GPT-5 Mini", Provider.OPENAI, strongAtTools = true, emitsReasoning = true),

        // --- Gemini direct ---
        ModelInfo("gemini-3.7-flash", "Gemini 3.7 Flash", Provider.GEMINI, recommended = true, strongAtTools = true, emitsReasoning = true),

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
