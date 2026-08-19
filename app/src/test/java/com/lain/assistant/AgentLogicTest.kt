package com.lain.assistant

import com.lain.assistant.agent.TaskState
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
        val state = TaskState()
        val args = """{"text":"Send"}"""
        assertFalse(state.isExhausted("tap_text", args))

        state.record("tap_text", args, succeeded = false, failure = FailureKind.INVALID_INPUT, note = "not found")
        assertFalse("one failure should still allow a retry", state.isExhausted("tap_text", args))

        state.record("tap_text", args, succeeded = false, failure = FailureKind.INVALID_INPUT, note = "not found")
        assertTrue("two identical failures means stop", state.isExhausted("tap_text", args))
    }

    @Test
    fun `differing arguments are tracked independently`() {
        val state = TaskState()
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
        val state = TaskState()
        state.record("open_app", "{}", true, null, "ok")
        state.record("close_app", "{}", true, null, "ok")
        state.record("read_screen", "{}", true, null, "ok")
        assertEquals(2, state.appSwitches)
    }

    @Test
    fun `progress note stays silent early and reports failures once drifting`() {
        val state = TaskState()
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
        val state = TaskState()
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
