package com.lain.assistant

import com.lain.assistant.agent.FastRouter
import com.lain.assistant.agent.PromptBuilder
import com.lain.assistant.agent.Route
import com.lain.assistant.agent.SkillTeacher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skills — taught on the device, kept forever, and able to build on each other.
 *
 * The parsing tests carry most of the weight. A skill is invoked by name weeks after
 * it is taught, so a misparse is discovered at the worst possible moment and looks
 * exactly like the app being broken.
 */
class SkillsTest {

    @Test
    fun `the ways people actually teach are understood`() {
        listOf(
            "when I say wind down, put the phone on do not disturb and dim the screen",
            "learn a skill called wind down: put the phone on do not disturb",
            "teach you how to wind down: put the phone on do not disturb",
            "from now on, wind down means: put the phone on do not disturb"
        ).forEach { lesson ->
            val taught = SkillTeacher.parse(lesson)
            assertTrue("\"$lesson\" wasn't understood as teaching", taught != null)
            assertEquals("wind down", taught!!.name)
            assertTrue(taught.steps.contains("do not disturb"))
        }
    }

    @Test
    fun `being taught beats being instructed`() {
        // "When I say X, do Y" contains an instruction. Read as one, she does the
        // thing instead of learning it, and the lesson is silently lost.
        val route = FastRouter.route("when I say wind down, turn on do not disturb")
        assertTrue("the lesson was executed instead of learned", route is Route.LearnSkill)
        assertEquals("wind down", (route as Route.LearnSkill).name)
    }

    @Test
    fun `an ordinary request is not mistaken for a lesson`() {
        listOf(
            "turn on do not disturb",
            "what did you say",
            "open whatsapp",
            "remind me to call mum at six",
            "text ade that I'm running late"
        ).forEach { assertNull("\"$it\" was read as teaching", SkillTeacher.parse(it)) }
    }

    @Test
    fun `a lesson with no separator is refused rather than half-stored`() {
        // Without a colon or comma there is no line between the name and the steps,
        // and a skill filed under half a sentence never matches anything again.
        assertNull(SkillTeacher.parse("when I say wind down turn everything off"))
        // And a "name" that is really a sentence is a misparse, not a handle.
        assertNull(
            SkillTeacher.parse(
                "when I say this whole long thing about the evening routine and the lights, do it"
            )
        )
    }

    @Test
    fun `forgetting a skill is recognised, and is not the same as forgetting a fact`() {
        assertEquals("wind down", SkillTeacher.parseForget("forget the wind down skill"))
        assertEquals("wind down", SkillTeacher.parseForget("unlearn wind down"))
        // Phrasing alone can't separate these two; the store decides. What matters
        // here is that the parser offers a name rather than asserting the intent.
        assertEquals("my birthday", SkillTeacher.parseForget("forget my birthday"))
    }

    @Test
    fun `a skill brief keeps the confirmation gate`() {
        val brief = PromptBuilder.skillRule("wind down", "put the phone on do not disturb")
        assertTrue(brief.contains("wind down"))
        assertTrue(brief.contains("put the phone on do not disturb"))
        // A stored procedure is a recipe, not a licence. The twentieth run of a skill
        // that texts someone is not more authorised than the first.
        assertTrue("the skill brief waives confirmation", brief.contains("confirmed first"))
        assertTrue(brief.contains("say which one and stop"))
    }

    @Test
    fun `the skill brief costs nothing on turns without a skill`() {
        val ordinary = PromptBuilder.build(
            profile = null, memories = emptyList(), conversationSummary = null,
            capabilities = com.lain.assistant.data.ModelCapabilityRegistry.forModel(
                "anthropic/claude-sonnet-5", com.lain.assistant.data.Provider.OPENROUTER
            ),
            mode = com.lain.assistant.agent.DeliveryMode.TEXT,
            accessibilityReady = true
        )
        assertFalse(ordinary.contains("A SKILL THEY TAUGHT YOU"))
    }
}
