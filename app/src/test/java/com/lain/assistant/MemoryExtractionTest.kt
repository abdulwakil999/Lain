package com.lain.assistant

import com.lain.assistant.data.MemoryCategory
import com.lain.assistant.data.MemoryExtractor
import com.lain.assistant.data.TextIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory that sticks without being told to, and finds things by meaning.
 *
 * The extractor writes to a store the user can see and did not ask for, so the
 * false-positive tests below matter more than the hits: a wrong fact is worse than
 * a missing one, because it comes back later stated as truth.
 */
class MemoryExtractionTest {

    private fun subjects(message: String) = MemoryExtractor.extract(message).map { it.subject }
    private fun facts(message: String) = MemoryExtractor.extract(message).map { it.fact }

    // ------------------------------------------------------------- it catches

    @Test
    fun `a named relative is remembered, in either word order`() {
        val forward = MemoryExtractor.extract("my mum's name is Amina")
        assertEquals(1, forward.size)
        assertEquals("mum", forward.first().subject)
        assertEquals(MemoryCategory.PERSON, forward.first().category)
        assertTrue(forward.first().fact.contains("Amina"))

        val backward = MemoryExtractor.extract("Amina is my mum")
        assertEquals(1, backward.size)
        assertEquals("mum", backward.first().subject)
        assertTrue(backward.first().fact.contains("Amina"))
    }

    @Test
    fun `the facts people actually state about themselves are picked up`() {
        assertTrue(facts("my name is Abdul").any { it.contains("Abdul") })
        assertTrue(facts("call me Wakil").any { it.contains("Wakil") })
        assertTrue(facts("I'm 24 years old").any { it.contains("24") })
        assertTrue(facts("my birthday is 12 March").any { it.contains("12 March") })
        assertTrue(facts("I live in Lagos").any { it.contains("Lagos") })
        assertTrue(facts("I work at Zenith Bank").any { it.contains("Zenith Bank") })
        assertTrue(facts("I study mechanical engineering").any { it.contains("mechanical engineering") })
        assertTrue(facts("I'm allergic to peanuts").any { it.contains("peanuts") })
        assertTrue(facts("my exam is on Tuesday").any { it.contains("Tuesday") })
        assertTrue(facts("I'm working on a chemistry assignment").any { it.contains("chemistry") })
        assertTrue(facts("my favourite surah is Al-Kahf").any { it.contains("Al-Kahf") })
        assertTrue(facts("I hate loud notifications").any { it.contains("loud notifications") })
    }

    @Test
    fun `importance reflects how load-bearing the fact is`() {
        val name = MemoryExtractor.extract("my name is Abdul").first()
        val habit = MemoryExtractor.extract("I always leave the house at seven").first()
        assertTrue("who they are should outrank a habit", name.importance > habit.importance)
    }

    // ------------------------------------------------------- it does not catch

    @Test
    fun `a question is someone asking, not someone telling`() {
        // The difference between storing a fact and storing a guess.
        assertTrue(subjects("is my mum's name Amina?").isEmpty())
        assertTrue(subjects("what's my name?").isEmpty())
        assertTrue(subjects("do I live in Lagos?").isEmpty())
    }

    @Test
    fun `an instruction that looks like a statement is not stored`() {
        // "call me an ambulance" is the case that would turn a request for help into
        // an identity claim. The article is what rules it out.
        assertTrue(subjects("call me an ambulance").isEmpty())
        assertTrue(subjects("call me a taxi").isEmpty())
        assertTrue(subjects("call me back later when you have worked it out").isEmpty())
    }

    @Test
    fun `ordinary talk leaves nothing behind`() {
        listOf(
            "open whatsapp",
            "what's the time",
            "turn the torch on",
            "play something by Burna Boy",
            "thanks",
            "that was quick",
            "set an alarm for 7am",
            "write me a python script that renames files"
        ).forEach { assertTrue("\"$it\" wrote to memory", subjects(it).isEmpty()) }
    }

    @Test
    fun `one message never writes two facts to the same subject`() {
        // Overlapping rules on one sentence would otherwise store the loose match and
        // the tight one as separate contradicting facts.
        val found = MemoryExtractor.extract("my mum's name is Amina and I live in Lagos")
        assertEquals(found.map { it.subject }.size, found.map { it.subject }.toSet().size)
    }

    // ----------------------------------------------------------- finding again

    @Test
    fun `a fact is findable by a word the user did not use to store it`() {
        // The failure this replaces: "mum" and "mother" share no letters worth
        // matching, so the stored fact was invisible to the question that wanted it.
        val stored = TextIndex.concepts("Their mother is called Amina")
        assertTrue("mum doesn't reach mother", TextIndex.concepts("what's my mum's name").any { it in stored })
        assertTrue("mom doesn't reach mother", TextIndex.concepts("my mom").any { it in stored })
    }

    @Test
    fun `the same idea in different words lands on the same concept`() {
        listOf(
            "my flat" to "the apartment",
            "my job" to "at work",
            "my uni course" to "at university",
            "the exam" to "that test",
            "my mobile" to "the phone",
            "text her" to "send a message"
        ).forEach { (a, b) ->
            val overlap = TextIndex.concepts(a).intersect(TextIndex.concepts(b))
            assertTrue("\"$a\" and \"$b\" share nothing", overlap.isNotEmpty())
        }
    }

    @Test
    fun `unrelated subjects are still kept apart`() {
        // A lexicon that merges too much returns the wrong fact confidently, which is
        // worse than returning none.
        listOf(
            "my mother" to "my father",
            "the exam" to "the meeting",
            "my car" to "my laptop",
            "I love it" to "I hate it"
        ).forEach { (a, b) ->
            val overlap = TextIndex.concepts(a).intersect(TextIndex.concepts(b))
            assertTrue("\"$a\" and \"$b\" were merged", overlap.isEmpty())
        }
    }

    @Test
    fun `near-misses are caught without a lexicon entry`() {
        assertTrue(TextIndex.similarity("whatsapp", "whats app") > 0.6)
        assertTrue(TextIndex.similarity("Amina", "Aminas") > 0.6)
        // And things that genuinely differ still score low.
        assertTrue(TextIndex.similarity("battery", "birthday") < 0.4)
        // The documented limit, pinned so it is not mistaken for a capability:
        // a letter changed inside a short word is not caught by trigrams.
        assertTrue(TextIndex.similarity("Amina", "Ameena") < 0.45)
    }
}
