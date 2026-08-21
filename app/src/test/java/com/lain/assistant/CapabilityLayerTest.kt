package com.lain.assistant

import com.lain.assistant.data.ModelCapabilityRegistry
import com.lain.assistant.data.ModelInfo
import com.lain.assistant.data.Provider
import com.lain.assistant.data.ReasoningTier
import com.lain.assistant.tools.ToolDefinitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capability layer decides how much every request asks of the model. Getting
 * it wrong in either direction is costly: overestimate and a small model drowns,
 * underestimate and a capable one is wasted.
 */
class CapabilityLayerTest {

    @Test
    fun `catalogue facts beat guessing from the model name`() {
        // A free model the registry has never heard of, with real numbers attached.
        val info = ModelInfo(
            id = "vendor/unknown-120b:free",
            label = "Unknown 120B (Free)",
            provider = Provider.OPENROUTER,
            isFree = true,
            supportsVision = true,
            contextTokens = 262_144
        )
        val caps = ModelCapabilityRegistry.forModel(info.id, Provider.OPENROUTER, info)

        assertEquals(262_144, caps.contextTokens)
        assertTrue("vision should come from the catalogue, not the slug", caps.supportsVision)
        assertTrue(caps.isFree)
        assertEquals("Unknown 120B (Free)", caps.label)
    }

    @Test
    fun `a large free model is not treated as weak just because it is free`() {
        // The whole point: free must not be a synonym for incapable, or capable free
        // models get handicapped for no reason.
        val big = ModelCapabilityRegistry.forModel(
            "nvidia/nemotron-3-ultra-550b-a55b:free",
            Provider.OPENROUTER,
            ModelInfo(
                id = "nvidia/nemotron-3-ultra-550b-a55b:free",
                label = "Nemotron 3 Ultra 550B (Free)",
                provider = Provider.OPENROUTER,
                isFree = true,
                contextTokens = 1_000_000
            )
        )
        assertEquals(ReasoningTier.GOOD, big.reasoning)
        assertTrue(big.handlesMultiStepAutomation)
    }

    @Test
    fun `a small free model gets tight budgets and honest limitations`() {
        val small = ModelCapabilityRegistry.forModel(
            "vendor/small-9b:free",
            Provider.OPENROUTER,
            ModelInfo(
                id = "vendor/small-9b:free",
                label = "Small 9B (Free)",
                provider = Provider.OPENROUTER,
                isFree = true,
                contextTokens = 128_000
            )
        )
        assertEquals(ReasoningTier.BASIC, small.reasoning)
        assertFalse(small.handlesMultiStepAutomation)
        assertTrue(small.useCompactPrompt)
        assertTrue(small.limitations.isNotEmpty())
        // Budgets must be smaller than a strong model's, but never zero — a crippled
        // free experience is the outcome this whole design exists to avoid.
        assertTrue(small.memoryBudget in 1..6)
        assertTrue(small.recentWindow in 4..10)
        assertTrue(small.maxToolRounds in 4..8)
    }

    @Test
    fun `known strong models keep the full surface`() {
        val strong = ModelCapabilityRegistry.forModel("anthropic/claude-sonnet-5", Provider.OPENROUTER)
        assertEquals(ReasoningTier.STRONG, strong.reasoning)
        assertFalse(strong.useCompactToolDescriptions)
        assertEquals(Int.MAX_VALUE, strong.toolBudget)
        assertTrue(strong.handlesMultiStepAutomation)
    }

    @Test
    fun `a tiny advertised context caps the history window`() {
        val cramped = ModelCapabilityRegistry.forModel(
            "vendor/cramped:free",
            Provider.OPENROUTER,
            ModelInfo(
                id = "vendor/cramped:free", label = "Cramped", provider = Provider.OPENROUTER,
                isFree = true, contextTokens = 8_000
            )
        )
        // 8k of context cannot hold eight turns plus a prompt plus a screen listing.
        assertTrue("window ${cramped.recentWindow} too big for 8k", cramped.recentWindow <= 5)
    }

    @Test
    fun `no model selected still yields usable, honest defaults`() {
        val none = ModelCapabilityRegistry.forModel(null)
        assertTrue(none.limitations.any { it.contains("No model selected") })
        assertTrue(none.maxToolRounds > 0)
    }
}

/**
 * Measures the context actually saved by shaping the tool surface — the claim that
 * this is architecture rather than a longer prompt has to be checkable.
 */
class ToolSurfaceBudgetTest {

    private fun payloadChars(tools: List<com.lain.assistant.network.ToolDefinition>) =
        tools.sumOf { it.name.length + it.description.length + it.parameters.toString().length }

    @Test
    fun `a weak model gets a much smaller tool payload than a strong one`() {
        val strong = ModelCapabilityRegistry.forModel("anthropic/claude-sonnet-5", Provider.OPENROUTER)
        val weak = ModelCapabilityRegistry.forModel(
            "vendor/small-9b:free", Provider.OPENROUTER,
            ModelInfo(
                id = "vendor/small-9b:free", label = "Small 9B (Free)",
                provider = Provider.OPENROUTER, isFree = true, contextTokens = 128_000
            )
        )

        val strongTools = ToolDefinitions.forCapabilities(strong, accessibilityReady = true)
        val weakTools = ToolDefinitions.forCapabilities(weak, accessibilityReady = true)

        val strongChars = payloadChars(strongTools)
        val weakChars = payloadChars(weakTools)
        println(
            "tool payload: strong ${strongTools.size} tools / $strongChars chars, " +
                "weak ${weakTools.size} tools / $weakChars chars " +
                "(${100 - weakChars * 100 / strongChars}% smaller)"
        )

        assertTrue("weak surface should be smaller", weakChars < strongChars / 2)
        // But not empty: a free model must still be able to do things.
        assertTrue("weak model kept too few tools", weakTools.size >= 12)
    }

    @Test
    fun `the tools that finish a whole job survive the trim`() {
        val weak = ModelCapabilityRegistry.forModel(
            "vendor/small-9b:free", Provider.OPENROUTER,
            ModelInfo(
                id = "vendor/small-9b:free", label = "Small 9B (Free)",
                provider = Provider.OPENROUTER, isFree = true, contextTokens = 128_000
            )
        )
        val names = ToolDefinitions.forCapabilities(weak, accessibilityReady = true).map { it.name }
        // These are the ones that turn a multi-round task into a single call, which is
        // exactly what a weak model needs most.
        listOf("message_contact", "open_app", "web_search", "make_call", "remember").forEach {
            assertTrue("weak model lost $it", it in names)
        }
    }

    @Test
    fun `screen tools are withheld when accessibility is off`() {
        val strong = ModelCapabilityRegistry.forModel("anthropic/claude-sonnet-5", Provider.OPENROUTER)
        val names = ToolDefinitions.forCapabilities(strong, accessibilityReady = false).map { it.name }
        listOf("tap_text", "read_screen", "type_text", "swipe_screen").forEach {
            assertFalse("$it should not be offered without accessibility", it in names)
        }
        assertTrue("non-screen tools should remain", "web_search" in names)
    }

    @Test
    fun `vision tools only go to models that can see`() {
        val blind = ModelCapabilityRegistry.forModel(
            "vendor/text-only-120b:free", Provider.OPENROUTER,
            ModelInfo(
                id = "vendor/text-only-120b:free", label = "Text Only 120B (Free)",
                provider = Provider.OPENROUTER, isFree = true, supportsVision = false, contextTokens = 262_144
            )
        )
        val names = ToolDefinitions.forCapabilities(blind, accessibilityReady = true).map { it.name }
        assertFalse("look_at_screen" in names)
        assertFalse("take_photo" in names)
    }
}
