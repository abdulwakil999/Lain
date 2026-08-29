package com.lain.assistant

import com.lain.assistant.agent.CodeBlocks
import com.lain.assistant.agent.FastRouter
import com.lain.assistant.agent.LocalIntent
import com.lain.assistant.agent.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Code and schoolwork: that they take the long, tool-free path, that the code
 * survives the round trip intact, and that it never gets read aloud.
 */
class CodeAndStudyTest {

    // ------------------------------------------------------------- routing

    @Test
    fun `code and homework asks take the study path`() {
        listOf(
            "write me a python script that renames files by date",
            "why is my kotlin coroutine leaking",
            "explain recursion with an example in javascript",
            "help me with my chemistry assignment",
            "prove that the square root of 2 is irrational",
            "what's the derivative of x squared times sin x",
            "debug this stack trace for me",
            "write a sql query to find duplicate rows",
            "structure an essay on the causes of the first world war"
        ).forEach { ask ->
            assertEquals("\"$ask\" was not routed to study", Route.Study, FastRouter.route(ask))
        }
    }

    @Test
    fun `a device command that mentions a language is still a device command`() {
        // "Open Python" is an app launch. Losing that to the study path would mean a
        // network round trip for something the launcher answers instantly.
        val route = FastRouter.route("open python")
        assertTrue("expected a local intent, got $route", route is Route.Local)
        assertTrue((route as Route.Local).intent is LocalIntent.OpenApp)
    }

    @Test
    fun `ordinary talk and phone commands never become study work`() {
        listOf(
            "what's the time",
            "turn the torch on",
            "call mum",
            "how are you",
            "set an alarm for 7am",
            "play some music"
        ).forEach { ask ->
            assertTrue("\"$ask\" was misrouted to study", FastRouter.route(ask) !is Route.Study)
        }
    }

    // ------------------------------------------------------ block parsing

    @Test
    fun `a fenced block keeps its language and its exact text`() {
        val reply = "Here you go:\n\n```python\ndef add(a, b):\n    return a + b\n```\n\nThat's it."
        val blocks = CodeBlocks.split(reply)

        assertEquals(3, blocks.size)
        assertFalse(blocks[0].isCode)
        assertTrue(blocks[1].isCode)
        assertEquals("python", blocks[1].language)
        // Indentation is the code; losing it is losing the answer.
        assertEquals("def add(a, b):\n    return a + b", blocks[1].text)
        assertFalse(blocks[2].isCode)
    }

    @Test
    fun `a reply with no code is left completely alone`() {
        val reply = "Two sentences of prose. Nothing else."
        assertEquals(listOf(reply), CodeBlocks.split(reply).map { it.text })
        assertFalse(CodeBlocks.containsCode(reply))
        assertEquals(reply, CodeBlocks.forSpeech(reply))
    }

    @Test
    fun `an unclosed fence still yields the code it did send`() {
        // The model ran out of room mid-snippet. Showing what arrived beats showing
        // three backticks and a wall of unstyled text.
        val reply = "Start:\n```kotlin\nfun main() {\n    println(\"hi\")"
        val blocks = CodeBlocks.split(reply)
        assertTrue(blocks.last().isCode)
        assertTrue(blocks.last().text.contains("println"))
    }

    @Test
    fun `several blocks in one reply are each their own block`() {
        val reply = "First:\n```js\na()\n```\nThen:\n```js\nb()\n```"
        val code = CodeBlocks.split(reply).filter { it.isCode }
        assertEquals(2, code.size)
        assertEquals("a()", code[0].text)
        assertEquals("b()", code[1].text)
    }

    @Test
    fun `a fence with no language is not given one`() {
        val blocks = CodeBlocks.split("```\nplain\n```")
        assertEquals(null, blocks.first().language)
    }

    // -------------------------------------------------------------- speech

    @Test
    fun `a worked solution is never filed away as working`() {
        // "Analysis:" is on the list of preamble headers, and without a guard the
        // deliberation split would hide the code and show the footnote as the answer.
        val reply = "Analysis:\nA loop is enough here.\n\n```python\nfor i in range(3):\n    print(i)\n```\n\nPrints 0 to 2."
        val split = com.lain.assistant.agent.Deliberation.split(reply)

        assertEquals(reply, split.answer)
        assertEquals(null, split.working)
        assertFalse(
            "code counted as thinking out loud",
            com.lain.assistant.agent.Deliberation.isThinkingOutLoud(reply, actedThisTurn = false)
        )
    }

    @Test
    fun `code is described aloud, never read aloud`() {
        val reply = "Here:\n```python\ndef add(a, b):\n    return a + b\n```\nCall it with two numbers."
        val spoken = CodeBlocks.forSpeech(reply)

        assertFalse("braces and syntax reached the synthesiser", spoken.contains("def add"))
        assertFalse(spoken.contains("```"))
        assertTrue("the prose was dropped too", spoken.contains("Call it with two numbers"))
        assertTrue("the listener isn't told the code exists", spoken.contains("python code"))
    }
}
