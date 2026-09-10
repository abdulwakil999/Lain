package com.lain.assistant

import com.lain.assistant.agent.FastRouter
import com.lain.assistant.agent.LocalIntent
import com.lain.assistant.agent.Route
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A topic is not a command.
 *
 * The bug this exists to prevent, in the words it actually happened in: "open
 * WhatsApp and tell abdulwakil what's the latest version of Lewa Coder" contains the
 * words "lewa coder", so the local topic matcher claimed the sentence and answered
 * with a description of Lewa Coder. No message was sent, nothing said one hadn't
 * been, and the reply looked like an answer — which is the worst version of this,
 * because there is nothing to notice.
 *
 * The rule: when a sentence tells her to *do* something, whatever it mentions is the
 * content of that instruction, not a question. Those go to the agent loop, which has
 * the tools to carry them out.
 */
class InstructionBeatsTopicTest {

    private fun isLocalAnswer(message: String): Boolean {
        val route = FastRouter.route(message)
        return route is Route.Local && route.intent is LocalIntent.Identity
    }

    @Test
    fun `the message that started this is not answered from a constant`() {
        assertFalse(
            isLocalAnswer(
                "open WhatsApp and tell abdulwakil what's the latest version of lewa coder " +
                    "and put your signature that it's from Lain"
            )
        )
    }

    @Test
    fun `no topic survives being the subject of an instruction`() {
        // Every constant-backed topic, wrapped in an instruction. All of them used to
        // be hijacked the same way.
        listOf(
            "text ade about lewa coder",
            "message mum what lewa therapist does",
            "whatsapp abdulwakil about lewa·dev",
            "tell ade who professor poopy butthole is",
            "send a message to ade about the anime lain is named after",
            "email sam what your capabilities are",
            "remind abdulwakil about lewa coder tomorrow",
            "call ade and tell him about lewa coder"
        ).forEach {
            assertFalse("\"$it\" must reach the tools, not a canned answer", isLocalAnswer(it))
            assertTrue("\"$it\" should read as an instruction", FastRouter.carriesAnInstruction(it))
        }
    }

    @Test
    fun `questions about herself still answer instantly`() {
        // The other half. These are the whole point of answering locally — they must
        // not be dragged into the agent loop by an over-eager guard.
        listOf(
            "what is lewa coder",
            "what can lewa coder do",
            "who is professor poopy butthole",
            "what is lewa therapist",
            "tell me about yourself",
            "what can you do",
            "who made you"
        ).forEach {
            assertTrue("\"$it\" should still be answered locally", isLocalAnswer(it))
        }
    }

    @Test
    fun `talking to her is not an instruction to the phone`() {
        // "tell me", "ask me", "remind me" are her being spoken to, not somebody else
        // being messaged. Getting this wrong would send every ordinary question to the
        // tool loop.
        listOf(
            "tell me about lewa coder",
            "tell me what you can do",
            "ask me something",
            "remind me what lewa coder is"
        ).forEach {
            assertFalse("\"$it\" is not a device instruction", FastRouter.carriesAnInstruction(it))
        }
    }

    @Test
    fun `the instruction check recognises the ordinary ways of asking`() {
        listOf(
            "text ade hello",
            "whatsapp mum",
            "call ade",
            "open whatsapp",
            "turn on bluetooth",
            "set an alarm for 7",
            "play something by fela",
            "send a photo to ade",
            "take a screenshot"
        ).forEach {
            assertTrue("\"$it\" should read as an instruction", FastRouter.carriesAnInstruction(it))
        }
    }

    @Test
    fun `ordinary sentences are not read as instructions`() {
        listOf(
            "what's the weather",
            "how are you",
            "explain recursion",
            "what time is it",
            "i told him already"
        ).forEach {
            assertFalse("\"$it\" is not an instruction", FastRouter.carriesAnInstruction(it))
        }
    }
}
