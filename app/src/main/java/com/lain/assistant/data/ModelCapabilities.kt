package com.lain.assistant.data

/**
 * How much agentic complexity a model can actually carry.
 *
 * This is about *reliability under load*, not intelligence in the abstract. A
 * BASIC model can hold a good conversation; what it cannot do is keep a ten-step
 * plan straight while reading screen dumps and choosing between thirty tools.
 */
enum class ReasoningTier { BASIC, GOOD, STRONG }

/** Roughly how long a response takes, which changes what's worth asking for. */
enum class SpeedClass { FAST, MODERATE, SLOW }

/**
 * Everything the application knows about the model it is about to talk to.
 *
 * The premise of this layer is that no two models are interchangeable, and that
 * pretending otherwise is what produces "works great on Sonnet, incoherent on a
 * free model". Rather than writing one prompt for the strongest model and hoping,
 * every request is *shaped* to the model handling it: how much history it sees,
 * how many memories, how many tools, how those tools are described, how many
 * rounds it gets.
 *
 * Critically, this is not a way to cripple free models. It is the opposite — the
 * budgets below exist so a small model is asked a question it can actually answer,
 * instead of being handed a context it will drown in. The work removed from the
 * model is done by the application (see FastRouter, LocalActions, Calculator,
 * MessageFlow), not skipped.
 */
data class ModelCapabilities(
    val modelId: String,
    val provider: Provider,
    /** Human name, for telling the user which model actually handled something. */
    val label: String,
    val contextTokens: Int,
    val supportsTools: Boolean,
    val supportsVision: Boolean,
    /** Whether incremental delivery is available; false means the reply lands all at once. */
    val supportsStreaming: Boolean,
    val reasoning: ReasoningTier,
    val speed: SpeedClass,
    val isFree: Boolean,
    /** Plain-language caveats, shown to the user rather than hidden. */
    val limitations: List<String> = emptyList()
) {

    /**
     * Turns of conversation kept verbatim.
     *
     * Bounded by the tier rather than by the context window, because a large
     * advertised context does not mean a small model *uses* all of it well — recall
     * degrades long before the limit, so filling it makes answers worse, not better.
     */
    val recentWindow: Int
        get() = when (reasoning) {
            ReasoningTier.BASIC -> 8
            ReasoningTier.GOOD -> 18
            ReasoningTier.STRONG -> 24
        }.coerceAtMost(contextTokens / 1500)

    /** Long-term facts injected per request, chosen by relevance. */
    val memoryBudget: Int
        get() = when (reasoning) {
            ReasoningTier.BASIC -> 4
            ReasoningTier.GOOD -> 9
            ReasoningTier.STRONG -> 12
        }

    /**
     * Tool rounds before the loop stops and reports where it got to.
     *
     * Tight for weak models on purpose: past a handful of rounds a BASIC model is
     * almost always looping rather than progressing, and stopping to say so is more
     * useful than fifteen more attempts.
     */
    val maxToolRounds: Int
        get() = when (reasoning) {
            ReasoningTier.BASIC -> 6
            ReasoningTier.GOOD -> 12
            ReasoningTier.STRONG -> 20
        }

    /** Weak models do better with a trimmed brief than an exhaustive one. */
    val useCompactPrompt: Boolean get() = reasoning == ReasoningTier.BASIC

    /** Terse tool descriptions, which saves kilobytes of a small context window. */
    val useCompactToolDescriptions: Boolean get() = reasoning != ReasoningTier.STRONG

    /**
     * How many tools to put in front of the model at once.
     *
     * A long tool list is itself a reasoning task. Weak models pick worse from
     * thirty options than from fifteen, and the application's local router has
     * already removed most of the reasons to need the long tail.
     */
    val toolBudget: Int
        get() = when (reasoning) {
            ReasoningTier.BASIC -> 16
            ReasoningTier.GOOD -> 26
            ReasoningTier.STRONG -> Int.MAX_VALUE
        }

    /** Whether this model can be trusted with long autonomous automation. */
    val handlesMultiStepAutomation: Boolean get() = reasoning != ReasoningTier.BASIC

    /** One line for the settings screen, so capability is visible rather than folklore. */
    fun summary(): String = buildString {
        append(if (isFree) "Free" else "Paid")
        append(" · ")
        append(
            when (reasoning) {
                ReasoningTier.BASIC -> "good for chat and simple commands"
                ReasoningTier.GOOD -> "handles multi-step tasks"
                ReasoningTier.STRONG -> "handles complex autonomous tasks"
            }
        )
        if (contextTokens > 0) append(" · ${contextTokens / 1000}k context")
        if (supportsVision) append(" · can see screenshots")
        append(
            when (speed) {
                SpeedClass.FAST -> " · fast"
                SpeedClass.MODERATE -> ""
                SpeedClass.SLOW -> " · slower"
            }
        )
    }
}

/**
 * Works out what a model can do, preferring facts to guesses.
 *
 * The previous version was a hardcoded map keyed on model ID. That is wrong in a
 * way that gets worse over time: OpenRouter's free tier rotates every few weeks,
 * so the map is mostly misses, and a miss meant a coarse guess from the slug. Now
 * the provider's own catalogue supplies context size, vision support and pricing
 * ([ModelInfo]), and this layer only has to judge the one thing no API reports —
 * how well the model actually follows instructions under load.
 */
object ModelCapabilityRegistry {

    /**
     * Models we have specific knowledge of, by ID. Deliberately short: it exists for
     * the frontier models whose tier can't be inferred from catalogue metadata, not
     * as a registry of everything.
     */
    private val strongIds = setOf(
        "anthropic/claude-sonnet-5", "claude-sonnet-5",
        "anthropic/claude-opus-5", "claude-opus-5",
        "openai/gpt-5", "gpt-5",
        "x-ai/grok-4.6", "grok-4.6"
    )

    private val goodIds = setOf(
        "claude-haiku-4-5-20251001",
        "openai/gpt-5-mini", "gpt-5-mini",
        "google/gemini-3.7-flash", "gemini-3.7-flash"
    )

    /**
     * Parameter counts aren't in any catalogue, but they're in the names, and size
     * is the best available proxy for whether a model will chain tool calls rather
     * than narrate what it would do.
     */
    private val hugeModel = Regex("\\b([2-9]\\d{2}|1\\d{3})b\\b", RegexOption.IGNORE_CASE)
    private val largeModel = Regex("\\b([3-9]\\d|1\\d{2})b\\b", RegexOption.IGNORE_CASE)

    /**
     * @param info the provider's catalogue entry, when the app has one. Everything
     *   factual is taken from it; only [ReasoningTier] is inferred.
     */
    fun forModel(modelId: String?, provider: Provider? = null, info: ModelInfo? = null): ModelCapabilities {
        val id = modelId?.takeIf { it.isNotBlank() } ?: return unknownFree(provider)

        val resolvedProvider = info?.provider ?: provider ?: ModelCatalog.defaultProvider
        val isFree = info?.isFree ?: id.endsWith(":free")
        val context = info?.contextTokens?.takeIf { it > 0 } ?: inferContext(id, isFree)
        val vision = info?.supportsVision ?: inferVision(id)
        val tier = inferTier(id, info, isFree)

        return ModelCapabilities(
            modelId = id,
            provider = resolvedProvider,
            label = info?.label ?: id.substringAfterLast('/').removeSuffix(":free"),
            contextTokens = context,
            supportsTools = info?.supportsTools ?: true,
            supportsVision = vision,
            // Every provider Lain speaks to supports SSE on the chat-completions
            // dialect; the Anthropic path falls back transparently if it doesn't.
            supportsStreaming = true,
            reasoning = tier,
            speed = inferSpeed(id, tier, isFree),
            isFree = isFree,
            limitations = limitationsFor(tier, isFree, context, vision)
        )
    }

    private fun inferTier(id: String, info: ModelInfo?, isFree: Boolean): ReasoningTier {
        if (id in strongIds) return ReasoningTier.STRONG
        if (id in goodIds) return ReasoningTier.GOOD
        // The catalogue's own "reliably drives multi-step automation" flag, where set.
        if (info?.strongAtTools == true) return ReasoningTier.STRONG

        val name = info?.label ?: id
        return when {
            // A 200B+ free model is a genuinely capable tool caller; treating it as
            // BASIC would waste it. This is why the tier is inferred rather than
            // pinned to free/paid — free does not mean weak.
            hugeModel.containsMatchIn(name) -> ReasoningTier.GOOD
            largeModel.containsMatchIn(name) -> if (isFree) ReasoningTier.BASIC else ReasoningTier.GOOD
            isFree -> ReasoningTier.BASIC
            // Unrecognised paid models get the middle tier: under-asking a capable
            // model costs a little efficiency, overwhelming a weak one costs coherence.
            else -> ReasoningTier.GOOD
        }
    }

    private fun inferSpeed(id: String, tier: ReasoningTier, isFree: Boolean): SpeedClass = when {
        id.contains("lightning") || id.contains("flash") || id.contains("mini") ||
            id.contains("nano") || id.contains("haiku") -> SpeedClass.FAST
        // Free endpoints are shared infrastructure and queue under load, whatever the
        // model's own speed.
        isFree -> SpeedClass.MODERATE
        tier == ReasoningTier.STRONG -> SpeedClass.SLOW
        else -> SpeedClass.MODERATE
    }

    private fun inferContext(id: String, isFree: Boolean): Int = when {
        id.contains("gemini") -> 1_000_000
        id.contains("gpt-5") -> 400_000
        id.contains("claude") -> 200_000
        isFree -> 32_000
        else -> 128_000
    }

    private fun inferVision(id: String): Boolean =
        id.contains("-vl") || id.contains("vision") || id.contains("gemini") ||
            id.contains("gemma") || id.contains("claude") || id.contains("gpt-5")

    /**
     * Honest caveats, phrased for a person. These are surfaced in Settings and used
     * to decide when Lain should say "this model can't do that" rather than trying
     * and producing something plausible-looking.
     */
    private fun limitationsFor(
        tier: ReasoningTier,
        isFree: Boolean,
        context: Int,
        vision: Boolean
    ): List<String> = buildList {
        if (tier == ReasoningTier.BASIC) {
            add("Long multi-step phone tasks are unreliable — it tends to repeat a step instead of moving on")
            add("Best at conversation, questions and single actions")
        }
        if (isFree) add("Free endpoints are rate limited without warning")
        if (context in 1 until 60_000) add("Small context window, so long conversations get compressed sooner")
        if (!vision) add("Can't look at screenshots")
    }

    private fun unknownFree(provider: Provider?) = ModelCapabilities(
        modelId = "",
        provider = provider ?: ModelCatalog.defaultProvider,
        label = "No model selected",
        contextTokens = 32_000,
        supportsTools = true,
        supportsVision = false,
        supportsStreaming = true,
        reasoning = ReasoningTier.BASIC,
        speed = SpeedClass.MODERATE,
        isFree = true,
        limitations = listOf("No model selected — open Settings and pick one")
    )
}
