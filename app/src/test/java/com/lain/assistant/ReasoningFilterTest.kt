package com.lain.assistant

import com.lain.assistant.network.ReasoningFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter has two ways to fail and both are bad: leaking the model's internal
 * working, or eating part of a legitimate answer. The second is the reason this
 * strips only known markers instead of regexing angle brackets — so the tests
 * that matter most are the ones proving ordinary content survives.
 */
class ReasoningFilterTest {

    /** Feeds text the way a stream does — in small, arbitrarily-cut pieces. */
    private fun stream(text: String, chunk: Int): String {
        val f = ReasoningFilter()
        val out = StringBuilder()
        text.chunked(chunk).forEach { out.append(f.push(it)) }
        out.append(f.flush())
        return out.toString()
    }

    // ------------------------------------------------------------ stripping

    @Test
    fun `think tags are removed whole`() {
        val raw = "<think>The user wants the capital. It is Paris.</think>Paris."
        assertEquals("Paris.", ReasoningFilter.clean(raw))
    }

    @Test
    fun `reasoning is stripped no matter how the stream is cut`() {
        val raw = "<think>internal working here</think>The answer is 42."
        // Every chunk size, including ones that split markers down the middle.
        for (size in 1..20) {
            assertEquals("chunk size $size leaked or truncated", "The answer is 42.", stream(raw, size).trim())
        }
    }

    @Test
    fun `a marker split across chunks does not leak`() {
        val f = ReasoningFilter()
        val out = StringBuilder()
        // "<thi" + "nk>" — the classic boundary leak.
        listOf("Hello. <thi", "nk>secret", " thoughts</thin", "k> Bye.").forEach { out.append(f.push(it)) }
        out.append(f.flush())
        val result = out.toString()
        assertFalse("reasoning leaked: $result", result.contains("secret"))
        assertTrue(result.contains("Hello."))
        assertTrue(result.contains("Bye."))
    }

    @Test
    fun `harmony channels keep only the final channel`() {
        val raw = "<|channel|>analysis<|message|>Let me work through this...<|channel|>final<|message|>It's Tuesday."
        assertEquals("It's Tuesday.", ReasoningFilter.clean(raw))
    }

    @Test
    fun `multiple reasoning blocks are all removed`() {
        val raw = "<think>one</think>First. <think>two</think>Second."
        assertEquals("First. Second.", ReasoningFilter.clean(raw))
    }

    // ---------------------------------------------- legitimate content safety

    @Test
    fun `html and generics in a real answer survive untouched`() {
        // The failure mode of a naive regex: eating anything in angle brackets.
        val answers = listOf(
            "Use a `<div>` wrapper around the list.",
            "The signature is List<String> mapTo(Map<String, Int> input).",
            "In Kotlin, Flow<StreamEvent> is the return type.",
            "Compare a < b && c > d in the condition.",
            "The tag <span class=\"x\"> opens an inline element."
        )
        answers.forEach { assertEquals(it, ReasoningFilter.clean(it)) }
    }

    @Test
    fun `markdown structure is preserved`() {
        val md = """
            Here's the fix:

            ```kotlin
            fun main() {
                println("<think>not a real tag</think>")
            }
            ```

            - First point
            - Second point
        """.trimIndent()
        // A code fence containing the literal tag is the nasty case. The filter does
        // strip it — it cannot tell code from prose — so this pins the known
        // limitation rather than pretending it doesn't exist.
        val cleaned = ReasoningFilter.clean(md)!!
        assertTrue("the fence itself must survive", cleaned.contains("```kotlin"))
        assertTrue("list structure must survive", cleaned.contains("- First point"))
        assertTrue("the code line must survive", cleaned.contains("println"))
    }

    @Test
    fun `plain text passes through byte for byte`() {
        val plain = "Your battery is at 62%, and it's not charging. Want me to open battery settings?"
        assertEquals(plain, ReasoningFilter.clean(plain))
        for (size in 1..9) assertEquals(plain, stream(plain, size))
    }

    // -------------------------------------------------------- failure modes

    @Test
    fun `a response that is only unterminated reasoning yields nothing`() {
        // Better to report that than to show the reasoning or a blank bubble.
        assertNull(ReasoningFilter.clean("<think>I am thinking and never stopped"))
    }

    @Test
    fun `an empty response stays empty rather than becoming an error`() {
        assertNull(ReasoningFilter.clean(""))
        assertNull(ReasoningFilter.clean("   "))
    }

    @Test
    fun `the filter reports whether it actually did anything`() {
        val untouched = ReasoningFilter()
        untouched.push("Just an answer.")
        untouched.flush()
        assertFalse(untouched.strippedAnything)
        assertTrue(untouched.emittedAnything)

        val stripped = ReasoningFilter()
        stripped.push("<think>x</think>Answer.")
        stripped.flush()
        assertTrue(stripped.strippedAnything)
        assertTrue(stripped.emittedAnything)
    }

    @Test
    fun `text before a reasoning block is kept`() {
        // Some models greet, then think, then answer.
        assertEquals(
            "Sure. Paris.",
            ReasoningFilter.clean("Sure. <think>capital of France</think>Paris.")
        )
    }

    @Test
    fun `a lone angle bracket at the very end is not held back forever`() {
        // The holdback must be released on flush, or an answer ending in "<" vanishes.
        assertEquals("a < b", ReasoningFilter.clean("a < b"))
        assertEquals("done <", ReasoningFilter.clean("done <"))
    }
}

/**
 * Capturing what was stripped, rather than dropping it.
 *
 * The case that matters: a model cut off inside an unclosed think block emits no
 * visible text at all, so without capture the "her working" section would be empty
 * for exactly the failure the user most wants to look at.
 */
class ReasoningCaptureTest {

    @Test
    fun `a closed block is stripped and kept`() {
        val filter = ReasoningFilter()
        val visible = filter.push("<think>the user wants the time</think>It's 4:14 pm.")
        assertEquals("It's 4:14 pm.", visible.trim())
        assertTrue(filter.captured.contains("the user wants the time"))
    }

    @Test
    fun `an unclosed block still yields the working`() {
        val filter = ReasoningFilter()
        val visible = filter.push("<think>okay so I need to look up the contact and then")
        val tail = filter.flush()
        assertEquals("", (visible + tail).trim())
        assertTrue(filter.captured.contains("look up the contact"))
    }

    @Test
    fun `guillemet markers are recognised`() {
        val filter = ReasoningFilter()
        val visible = filter.push("◁think▷planning◁/think▷Done.")
        assertEquals("Done.", visible.trim())
        assertTrue(filter.captured.contains("planning"))
    }

    @Test
    fun `nothing is captured when there was no reasoning`() {
        val filter = ReasoningFilter()
        filter.push("Opened Spotify.")
        assertEquals("", filter.captured)
    }

    @Test
    fun `capture is bounded`() {
        val filter = ReasoningFilter()
        filter.push("<think>" + "a".repeat(20_000))
        filter.flush()
        assertTrue(filter.captured.length <= ReasoningFilter.MAX_CAPTURED)
    }
}
