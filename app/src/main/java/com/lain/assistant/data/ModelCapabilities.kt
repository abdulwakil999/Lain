package com.lain.assistant.data

/** How much agentic complexity a model can actually carry. */
enum class ReasoningTier { BASIC, GOOD, STRONG }

/**
 * What a given model can be trusted to do.
 *
 * Treating every model identically is why the same prompt produces a competent
 * assistant on one and a confused one on another. Weak models need a shorter
 * prompt, fewer tools in view and tighter step budgets; strong models can be given
 * the full surface. Nothing here changes *what* Lain is — only how much is asked
 * of the model at once.
 */
data class ModelCapabilities(
    val contextTokens: Int,
    val supportsTools: Boolean,
    val reasoning: ReasoningTier,
    val supportsVision: Boolean,
    val isFree: Boolean,
    /** Rough sense of latency, used to decide whether extra verification passes are worth it. */
    val fast: Boolean,
    val limitations: List<String> = emptyList()
) {
    /** Turns kept verbatim in context. */
    val recentWindow: Int
        get() = when (reasoning) {
            ReasoningTier.BASIC -> 10
            ReasoningTier.GOOD -> 18
            ReasoningTier.STRONG -> 24
        }

    /** Long-term facts injected per request. */
    val memoryBudget: Int
        get() = when (reasoning) {
            ReasoningTier.BASIC -> 5
            ReasoningTier.GOOD -> 9
            ReasoningTier.STRONG -> 12
        }

    /** Tool rounds allowed before the loop stops. */
    val maxToolRounds: Int
        get() = when (reasoning) {
            ReasoningTier.BASIC -> 8
            ReasoningTier.GOOD -> 14
            ReasoningTier.STRONG -> 20
        }

    /** Weak models do better with a trimmed prompt than an exhaustive one. */
    val useCompactPrompt: Boolean get() = reasoning == ReasoningTier.BASIC
}

object ModelCapabilityRegistry {

    private val STRONG = ModelCapabilities(
        contextTokens = 200_000, supportsTools = true, reasoning = ReasoningTier.STRONG,
        supportsVision = true, isFree = false, fast = false
    )
    private val GOOD_FAST = ModelCapabilities(
        contextTokens = 128_000, supportsTools = true, reasoning = ReasoningTier.GOOD,
        supportsVision = true, isFree = false, fast = true
    )
    private val FREE_BASIC = ModelCapabilities(
        contextTokens = 32_000, supportsTools = true, reasoning = ReasoningTier.BASIC,
        supportsVision = false, isFree = true, fast = true,
        limitations = listOf(
            "small context window",
            "tool-calling is unreliable under load",
            "prone to repeating a call instead of progressing",
            "rate limited without warning"
        )
    )

    /** Explicit entries for models we've characterised. */
    private val known: Map<String, ModelCapabilities> = mapOf(
        "anthropic/claude-sonnet-5" to STRONG,
        "anthropic/claude-opus-5" to STRONG.copy(contextTokens = 200_000),
        "claude-sonnet-5" to STRONG,
        "claude-opus-5" to STRONG,
        "claude-haiku-4-5-20251001" to GOOD_FAST,
        "openai/gpt-5" to STRONG.copy(contextTokens = 400_000),
        "openai/gpt-5-mini" to GOOD_FAST.copy(contextTokens = 400_000),
        "gpt-5" to STRONG.copy(contextTokens = 400_000),
        "gpt-5-mini" to GOOD_FAST.copy(contextTokens = 400_000),
        "google/gemini-3.7-flash" to GOOD_FAST.copy(contextTokens = 1_000_000),
        "gemini-3.7-flash" to GOOD_FAST.copy(contextTokens = 1_000_000),
        "x-ai/grok-4.6" to STRONG.copy(contextTokens = 500_000),
        "grok-4.6" to STRONG.copy(contextTokens = 500_000),
    )

    /**
     * Falls back to conservative assumptions for anything unrecognised — better to
     * under-ask a capable model than to overwhelm a weak one, since the failure
     * mode of the latter is incoherence rather than mild inefficiency.
     */
    fun forModel(modelId: String?): ModelCapabilities {
        if (modelId.isNullOrBlank()) return FREE_BASIC
        known[modelId]?.let { return it }

        val free = modelId.endsWith(":free")
        val vision = modelId.contains("-vl") || modelId.contains("vision") || modelId.contains("gemini")
        return if (free) {
            FREE_BASIC.copy(supportsVision = vision)
        } else {
            GOOD_FAST.copy(supportsVision = vision, limitations = listOf("capabilities not verified for this model"))
        }
    }
}
