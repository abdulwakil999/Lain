package com.lain.assistant

import com.lain.assistant.agent.DeliveryMode
import com.lain.assistant.agent.PendingConfirmation
import com.lain.assistant.agent.PromptBuilder
import com.lain.assistant.agent.WorkingMemory
import com.lain.assistant.data.Gender
import com.lain.assistant.data.ModelCapabilityRegistry
import com.lain.assistant.data.Provider
import com.lain.assistant.data.UserProfile
import com.lain.assistant.network.ToolCall
import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolDefinitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layer 1 of the memory architecture. Its job is to stop a model re-deriving
 * "what have I already tried" from raw transcript every round, so the digest has
 * to carry state accurately and stay quiet when there's nothing to say.
 */
class WorkingMemoryTest {

    @Test
    fun `a fresh task has nothing to say`() {
        val w = WorkingMemory()
        w.begin("text ade")
        // An empty block would cost tokens to communicate nothing.
        assertNull(w.digest())
    }

    @Test
    fun `the digest carries goal, successes and failures`() {
        val w = WorkingMemory()
        w.begin("send ade a message on whatsapp")
        w.record("lookup_contact", "{}", succeeded = true, failure = null, note = "Ade: +2348012345678")
        w.record("open_app", "{}", succeeded = true, failure = null, note = "Opened WhatsApp")
        w.record("tap_text", """{"text":"Send"}""", succeeded = false, failure = FailureKind.INVALID_INPUT, note = "no such element")

        val digest = w.digest()!!
        assertTrue(digest.contains("send ade a message"))
        assertTrue("successes missing", digest.contains("lookup_contact"))
        assertTrue("findings missing", digest.contains("+2348012345678"))
        assertTrue("failures missing", digest.contains("tap_text"))
    }

    @Test
    fun `an exhausted call is named so it is not retried`() {
        val w = WorkingMemory()
        w.begin("open the app")
        repeat(2) {
            w.record("tap_text", """{"text":"Send"}""", succeeded = false, failure = FailureKind.TOOL_FAILURE, note = "nope")
        }
        assertTrue(w.isExhausted("tap_text", """{"text":"Send"}"""))
        assertTrue(w.digest()!!.contains("exhausted"))
        // A different call with the same tool is not exhausted.
        assertFalse(w.isExhausted("tap_text", """{"text":"Back"}"""))
    }

    @Test
    fun `a successful tool marks its planned step done`() {
        val w = WorkingMemory()
        w.begin("message ade")
        w.setPlan(listOf("lookup_contact for ade", "open_app whatsapp", "send it"))
        assertEquals(3, w.unresolvedSteps.size)

        w.record("open_app", "{}", succeeded = true, failure = null, note = "Opened WhatsApp")
        // Observed, not asserted: the step closed because a tool reported success.
        assertTrue(w.completedSteps.any { it.contains("open_app") })
        assertEquals(2, w.unresolvedSteps.size)
        assertTrue(w.digest()!!.contains("Still to do"))
    }

    @Test
    fun `beginning a new task clears the previous one`() {
        val w = WorkingMemory()
        w.begin("first task")
        w.record("open_app", "{}", succeeded = true, failure = null, note = "done")
        w.begin("second task")
        assertNull(w.digest())
        assertEquals(0, w.attemptCount)
        assertEquals(0, w.appSwitches)
    }

    @Test
    fun `app switches are counted so churn can be refused`() {
        val w = WorkingMemory()
        w.begin("do a thing")
        w.record("open_app", "{}", succeeded = true, failure = null, note = "a")
        w.record("close_app", "{}", succeeded = true, failure = null, note = "b")
        assertEquals(2, w.appSwitches)
    }
}

/**
 * The gate exists because a model misreading "should I text Ade?" as an
 * instruction costs a real message to a real person. Approval must therefore be
 * unambiguous, and anything else must count as refusal.
 */
class ConfirmationTest {

    @Test
    fun `plain affirmatives approve`() {
        listOf("yes", "Yes", "yeah", "yep", "sure", "ok", "okay", "go ahead", "do it", "send it", "y")
            .forEach { assertTrue("\"$it\" should approve", PendingConfirmation.isApproval(it)) }
    }

    @Test
    fun `refusals and anything ambiguous do not approve`() {
        listOf(
            "no", "nope", "don't", "cancel", "wait", "hold on", "not yet", "never mind",
            "", "what do you mean", "who is that", "change it to hello", "maybe later"
        ).forEach { assertFalse("\"$it\" must not approve", PendingConfirmation.isApproval(it)) }
    }

    @Test
    fun `a negation overrides an affirmative-looking phrase`() {
        // The dangerous case: "yes" appears, but the sentence says not to.
        assertFalse(PendingConfirmation.isApproval("yes but don't send it yet"))
        assertFalse(PendingConfirmation.isApproval("ok wait"))
    }

    @Test
    fun `the prompt states exactly what will happen`() {
        val call = ToolCall(id = "1", name = "send_sms", argumentsJson = "{}")
        val summary = PendingConfirmation.describe(
            call,
            mapOf("phone_number" to "+2348012345678", "message" to "running late")
        )
        // The user has to see the recipient and the content to make a real decision.
        assertTrue(summary.contains("+2348012345678"))
        assertTrue(summary.contains("running late"))
    }

    @Test
    fun `a delete says it cannot be undone`() {
        val call = ToolCall(id = "1", name = "delete_file", argumentsJson = "{}")
        val summary = PendingConfirmation.describe(call, mapOf("path" to "notes/old.txt"))
        assertTrue(summary.contains("notes/old.txt"))
        assertTrue(summary.lowercase().contains("undone"))
    }
}

/**
 * The system prompt must not be how Lain gets smarter — the architecture is.
 * These pin its size so a future change has to justify itself.
 */
class PromptBudgetTest {

    private val profile = UserProfile(name = "Ada", age = 30, gender = Gender.FEMALE, nickname = "necio")

    private fun build(free: Boolean, mode: DeliveryMode = DeliveryMode.TEXT): String {
        val caps = ModelCapabilityRegistry.forModel(
            if (free) "vendor/small-9b:free" else "anthropic/claude-sonnet-5",
            Provider.OPENROUTER
        )
        return PromptBuilder.build(
            profile = profile,
            memories = emptyList(),
            conversationSummary = null,
            capabilities = caps,
            mode = mode,
            accessibilityReady = true
        )
    }

    @Test
    fun `the prompt stays small, and smaller still for weak models`() {
        val strong = build(free = false)
        val weak = build(free = true)
        println("system prompt: strong ${strong.length} chars, weak ${weak.length} chars")

        assertTrue("strong-model prompt has grown to ${strong.length} chars", strong.length < 3500)
        assertTrue("weak-model prompt has grown to ${weak.length} chars", weak.length < 2700)
        assertTrue("the weak-model prompt should be the shorter one", weak.length < strong.length)
    }

    @Test
    fun `voice mode asks for short spoken answers`() {
        val spoken = build(free = false, mode = DeliveryMode.VOICE)
        assertTrue(spoken.contains("SPOKEN ALOUD"))
        assertFalse("text-mode length guidance leaked into voice", spoken.contains("MATCH THE QUESTION"))
    }

    @Test
    fun `a trimmed tool is named so it is not reported as missing`() {
        val weak = ModelCapabilityRegistry.forModel("vendor/small-9b:free", Provider.OPENROUTER)
        val omitted = ToolDefinitions.omittedByBudget(weak, accessibilityReady = true)
        assertTrue("a weak model should not be shown every tool", omitted.isNotEmpty())

        val prompt = PromptBuilder.build(
            profile = profile, memories = emptyList(), conversationSummary = null,
            capabilities = weak, mode = DeliveryMode.TEXT, accessibilityReady = true,
            omittedTools = omitted
        )
        assertTrue(prompt.contains("left out of this message"))
        assertTrue("the trimmed names aren't there", prompt.contains(omitted.first()))
        // The whole point: she must not answer "I can't" for something she can.
        assertTrue(prompt.contains("never that you can't"))

        // And a model shown everything is told nothing about omissions.
        val strong = ModelCapabilityRegistry.forModel("anthropic/claude-sonnet-5", Provider.OPENROUTER)
        val full = PromptBuilder.build(
            profile = profile, memories = emptyList(), conversationSummary = null,
            capabilities = strong, mode = DeliveryMode.TEXT, accessibilityReady = true,
            omittedTools = ToolDefinitions.omittedByBudget(strong, accessibilityReady = true)
        )
        assertFalse(full.contains("left out of this message"))
        println("weak prompt with omissions: ${prompt.length} chars")
        assertTrue("the weak prompt has grown to ${prompt.length} chars", prompt.length < 2900)
    }

    @Test
    fun `accessibility being off is a switch, not a missing ability`() {
        val caps = ModelCapabilityRegistry.forModel("anthropic/claude-sonnet-5", Provider.OPENROUTER)
        val prompt = PromptBuilder.build(
            profile = profile, memories = emptyList(), conversationSummary = null,
            capabilities = caps, mode = DeliveryMode.TEXT, accessibilityReady = false
        )
        assertTrue(prompt.contains("Accessibility Service is off"))
        assertTrue("no route to fixing it", prompt.contains("switch in Settings"))
    }

    @Test
    fun `the study brief costs nothing on an ordinary turn`() {
        val caps = ModelCapabilityRegistry.forModel("anthropic/claude-sonnet-5", Provider.OPENROUTER)
        val ordinary = PromptBuilder.build(
            profile = profile, memories = emptyList(), conversationSummary = null,
            capabilities = caps, mode = DeliveryMode.TEXT, accessibilityReady = true
        )
        val study = PromptBuilder.build(
            profile = profile, memories = emptyList(), conversationSummary = null,
            capabilities = caps, mode = DeliveryMode.TEXT, accessibilityReady = true,
            includeTools = false, includeStudy = true
        )

        // The point of a separate section is that the turns it does not apply to
        // never pay for it — every torch and alarm would otherwise carry an essay
        // about writing essays.
        assertFalse("study guidance leaked into the default prompt", ordinary.contains("CODE AND SCHOOLWORK"))
        assertTrue(study.contains("CODE AND SCHOOLWORK"))
        println("study prompt: ${study.length} chars")
        assertTrue("the study prompt has grown to ${study.length} chars", study.length < 4200)
        // No tools on this path, so their description is dead weight.
        assertFalse(study.contains("YOUR TOOLS"))
    }

    @Test
    fun `screen tools are declared unavailable when accessibility is off`() {
        val caps = ModelCapabilityRegistry.forModel("anthropic/claude-sonnet-5", Provider.OPENROUTER)
        val prompt = PromptBuilder.build(
            profile = profile,
            memories = emptyList(),
            conversationSummary = null,
            capabilities = caps,
            mode = DeliveryMode.TEXT,
            accessibilityReady = false
        )
        assertTrue(prompt.contains("Accessibility Service is off"))
    }
}
