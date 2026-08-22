package com.lain.assistant

import com.lain.assistant.agent.IntentClassifier
import com.lain.assistant.agent.WorkingMemory
import com.lain.assistant.agent.TurnIntent
import com.lain.assistant.network.OpenRouterModelsClient
import com.lain.assistant.data.ModelCapabilityRegistry
import com.lain.assistant.data.ReasoningTier
import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLogicTest {

    // ---------------------------------------------------------- tool results

    @Test
    fun `success is clearly distinguishable from failure in model text`() {
        val ok = ToolResult.ok("Opened Spotify.")
        assertTrue(ok.toModelText().startsWith("SUCCESS"))

        val bad = ToolResult.fail(FailureKind.PERMISSION, "Needs CALL_PHONE.")
        val text = bad.toModelText()
        assertTrue(text.startsWith("FAILED"))
        assertTrue("failure kind must be visible to the agent", text.contains("permission"))
    }

    @Test
    fun `retryability follows the failure kind`() {
        assertTrue(ToolResult.fail(FailureKind.NETWORK, "x").retryable)
        assertTrue(ToolResult.fail(FailureKind.RATE_LIMIT, "x").retryable)
        // Retrying a permission problem can never succeed, so it must not be marked retryable.
        assertFalse(ToolResult.fail(FailureKind.PERMISSION, "x").retryable)
        assertFalse(ToolResult.fail(FailureKind.INVALID_INPUT, "x").retryable)
    }

    @Test
    fun `screen payload rides along without being folded into the prose`() {
        val r = ToolResult.ok("Tapped Search.", data = mapOf("screen" to "[BUTTON] Go @(10,20)"))
        assertEquals("Tapped Search.", r.result)
        assertTrue(r.toModelText().contains("Screen now:"))
    }

    // ------------------------------------------------------------ task state

    @Test
    fun `an approach is exhausted after two identical failures`() {
        val state = WorkingMemory()
        val args = """{"text":"Send"}"""
        assertFalse(state.isExhausted("tap_text", args))

        state.record("tap_text", args, succeeded = false, failure = FailureKind.INVALID_INPUT, note = "not found")
        assertFalse("one failure should still allow a retry", state.isExhausted("tap_text", args))

        state.record("tap_text", args, succeeded = false, failure = FailureKind.INVALID_INPUT, note = "not found")
        assertTrue("two identical failures means stop", state.isExhausted("tap_text", args))
    }

    @Test
    fun `differing arguments are tracked independently`() {
        val state = WorkingMemory()
        repeat(2) {
            state.record("tap_text", """{"text":"Send"}""", false, FailureKind.INVALID_INPUT, "no")
        }
        assertTrue(state.isExhausted("tap_text", """{"text":"Send"}"""))
        assertFalse(
            "a different target is a different approach",
            state.isExhausted("tap_text", """{"text":"Submit"}""")
        )
    }

    @Test
    fun `app switches are counted so churn can be capped`() {
        val state = WorkingMemory()
        state.record("open_app", "{}", true, null, "ok")
        state.record("close_app", "{}", true, null, "ok")
        state.record("read_screen", "{}", true, null, "ok")
        assertEquals(2, state.appSwitches)
    }

    @Test
    fun `progress note stays silent early and reports failures once drifting`() {
        val state = WorkingMemory()
        state.record("open_app", "{}", true, null, "ok")
        assertNull("no note before there is anything to say", state.progressNote())

        state.record("tap_text", """{"text":"a"}""", false, FailureKind.INVALID_INPUT, "missing")
        state.record("tap_text", """{"text":"b"}""", false, FailureKind.INVALID_INPUT, "missing")
        val note = state.progressNote()
        assertNotNull(note)
        assertTrue(note!!.contains("open_app"))
        assertTrue(note.contains("tap_text"))
    }

    @Test
    fun `reset clears state between requests`() {
        val state = WorkingMemory()
        state.record("open_app", "{}", false, FailureKind.APP_UNAVAILABLE, "no")
        state.reset()
        assertEquals(0, state.appSwitches)
        assertFalse(state.isExhausted("open_app", "{}"))
    }

    // ----------------------------------------------------- model capabilities

    @Test
    fun `free models get a smaller budget than strong ones`() {
        val free = ModelCapabilityRegistry.forModel("nvidia/nemotron-3-nano-30b-a3b:free")
        val strong = ModelCapabilityRegistry.forModel("anthropic/claude-sonnet-5")

        assertEquals(ReasoningTier.BASIC, free.reasoning)
        assertEquals(ReasoningTier.STRONG, strong.reasoning)
        assertTrue(free.memoryBudget < strong.memoryBudget)
        assertTrue(free.recentWindow < strong.recentWindow)
        assertTrue(free.maxToolRounds < strong.maxToolRounds)
        assertTrue("weak models need the trimmed prompt", free.useCompactPrompt)
        assertFalse(strong.useCompactPrompt)
    }

    @Test
    fun `unknown models fall back conservatively rather than optimistically`() {
        val unknown = ModelCapabilityRegistry.forModel("some-vendor/never-seen-before")
        assertTrue(unknown.reasoning != ReasoningTier.STRONG)
        assertTrue(unknown.limitations.isNotEmpty())

        val unknownFree = ModelCapabilityRegistry.forModel("some-vendor/tiny:free")
        assertEquals(ReasoningTier.BASIC, unknownFree.reasoning)
        assertTrue(unknownFree.isFree)
    }

    @Test
    fun `null model does not crash capability lookup`() {
        val caps = ModelCapabilityRegistry.forModel(null)
        assertTrue(caps.maxToolRounds > 0)
    }
}

/**
 * The classifier decides whether a message skips the agent loop, so its bias
 * matters more than its accuracy: sending a real instruction down the chat path
 * would produce a wrong answer, while sending chat down the agent path only
 * costs time. Every case below checks that bias holds.
 */
class IntentClassifierTest {

    @Test
    fun `plain conversation skips the tool loop`() {
        listOf(
            "good morning",
            "hey",
            "how are you doing",
            "thanks, that worked",
            "that's hilarious",
            "explain how an accessibility service works",
            "what do you think of kotlin coroutines"
        ).forEach {
            assertEquals(it, TurnIntent.CHAT, IntentClassifier.classify(it))
        }
    }

    @Test
    fun `device instructions take the full path`() {
        listOf(
            "open whatsapp",
            "text Ade that I'm running late",
            "call mum",
            "play something by Burna Boy",
            "what's my battery at",
            "turn on bluetooth",
            "set a reminder for 6pm",
            "scroll down"
        ).forEach {
            assertEquals(it, TurnIntent.ACT, IntentClassifier.classify(it))
        }
    }

    @Test
    fun `anything depending on today takes the full path`() {
        listOf(
            "what's the latest on the election",
            "how much is a dollar right now",
            "who won the match",
            "check https://example.com for me",
            "what's the weather"
        ).forEach {
            assertEquals(it, TurnIntent.ACT, IntentClassifier.classify(it))
        }
    }

    @Test
    fun `a long musing containing an action word is still conversation`() {
        val musing = "I read somewhere that people who write their own tools tend to understand " +
            "their problems better, which matches how I feel about most of the software I use daily"
        assertEquals(TurnIntent.CHAT, IntentClassifier.classify(musing, accessibilityReady = false))
    }
}

/**
 * The picker's contents come straight from this parser, so a regression here is
 * how a user ends up selecting a model that cannot drive the phone.
 */
class OpenRouterModelParsingTest {

    private fun catalogue(vararg entries: String) = """{"data":[${entries.joinToString(",")}]}"""

    private fun model(
        id: String,
        name: String,
        context: Int,
        price: String = "0",
        tools: Boolean = true,
        image: Boolean = false
    ) = """
        {"id":"$id","name":"$name","context_length":$context,
         "pricing":{"prompt":"$price","completion":"$price"},
         "architecture":{"input_modalities":["text"${if (image) ",\"image\"" else ""}]},
         "supported_parameters":[${if (tools) "\"tools\"" else "\"temperature\""}]}
    """.trimIndent()

    private val client = OpenRouterModelsClient()

    @Test
    fun `keeps only free, tool-capable, usably-large models`() {
        val raw = catalogue(
            model("good/big:free", "Big Model 120B (free)", 262_144),
            model("paid/one:free", "Paid One (free)", 262_144, price = "0.0000005"),
            model("no/tools:free", "No Tools 70B (free)", 262_144, tools = false),
            model("tiny/ctx:free", "Small Context 70B (free)", 8_000),
            model("not/free", "Not Free", 262_144)
        )
        assertEquals(listOf("good/big:free"), client.parse(raw).map { it.id })
    }

    @Test
    fun `drops models too small to chain tool calls`() {
        val raw = catalogue(
            model("vendor/lfm-2-6b:free", "LFM2.5-2.6B (free)", 128_000),
            model("vendor/nano-9b:free", "Nano 9B (free)", 128_000)
        )
        assertEquals(listOf("vendor/nano-9b:free"), client.parse(raw).map { it.id })
    }

    @Test
    fun `orders by context so the roomiest is offered first`() {
        val raw = catalogue(
            model("mid/model-30b:free", "Mid 30B (free)", 128_000),
            model("big/model-120b:free", "Big 120B (free)", 1_000_000),
            model("small/model-20b:free", "Small 20B (free)", 65_536)
        )
        assertEquals(
            listOf("big/model-120b:free", "mid/model-30b:free", "small/model-20b:free"),
            client.parse(raw).map { it.id }
        )
    }

    @Test
    fun `reads vision support from the catalogue rather than the slug`() {
        val raw = catalogue(
            model("seeing/model-30b:free", "Seeing 30B (free)", 262_144, image = true),
            model("blind/model-30b:free", "Blind 30B (free)", 262_144)
        )
        val parsed = client.parse(raw).associateBy { it.id }
        assertEquals(true, parsed.getValue("seeing/model-30b:free").supportsVision)
        assertEquals(false, parsed.getValue("blind/model-30b:free").supportsVision)
    }
}
