package com.lain.assistant

import com.lain.assistant.data.ApiKeys
import com.lain.assistant.data.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What arrives in the key field, and what has to come out of it.
 *
 * Every case here is a real way a correct key gets rejected. None of them is a
 * wrong key, which is the point: the failure they all produce is "invalid API key",
 * and the field masks the evidence behind dots.
 */
class ApiKeyTest {

    private val REAL = "sk-or-v1-0a1b2c3d4e5f60718293a4b5c6d7e8f9"

    @Test
    fun `a clean key is passed through untouched`() {
        assertEquals(REAL, ApiKeys.clean(REAL))
        assertNull(ApiKeys.problem(REAL))
        assertTrue(ApiKeys.isUsable(REAL))
    }

    @Test
    fun `whitespace from a copy is removed`() {
        assertEquals(REAL, ApiKeys.clean("  $REAL  "))
        assertEquals(REAL, ApiKeys.clean("$REAL\n"))
        assertEquals(REAL, ApiKeys.clean("\n$REAL\r\n"))
        assertEquals(REAL, ApiKeys.clean("$REAL\t"))
    }

    @Test
    fun `the invisible characters trim cannot see are removed`() {
        // The one that motivated all of this. A non-breaking space and a zero-width
        // space are not whitespace as far as String.trim is concerned, so both
        // survived into the header — where OkHttp refuses to send them at all.
        assertEquals(REAL, ApiKeys.clean("\u00A0$REAL\u00A0"))
        assertEquals(REAL, ApiKeys.clean("$REAL\u200B"))
        assertEquals(REAL, ApiKeys.clean("\uFEFF$REAL"))
        assertEquals(REAL, ApiKeys.clean("$REAL\u200D"))
    }

    @Test
    fun `a copied header prefix is dropped`() {
        // Pasting the curl example rather than the value. Left in, it becomes
        // "Bearer Bearer sk-…", which is a 401 that looks like a bad key.
        assertEquals(REAL, ApiKeys.clean("Bearer $REAL"))
        assertEquals(REAL, ApiKeys.clean("bearer $REAL"))
        assertEquals(REAL, ApiKeys.clean("Authorization: Bearer $REAL"))
        assertEquals(REAL, ApiKeys.clean("x-api-key: $REAL"))
        assertEquals(REAL, ApiKeys.clean("API key = $REAL"))
    }

    @Test
    fun `quotes from json or a config file are dropped`() {
        assertEquals(REAL, ApiKeys.clean("\"$REAL\""))
        assertEquals(REAL, ApiKeys.clean("'$REAL'"))
        assertEquals(REAL, ApiKeys.clean("`$REAL`"))
        assertEquals(REAL, ApiKeys.clean("<$REAL>"))
    }

    @Test
    fun `everything that is left is safe to put in a header`() {
        // The actual contract. OkHttp rejects any value outside this range, and a
        // rejected value used to surface as a crash rather than a sentence.
        listOf(
            "\u00A0$REAL", "Bearer $REAL\n", "\"$REAL\u200B\"", "  $REAL  "
        ).forEach { raw ->
            ApiKeys.clean(raw).forEach { c ->
                assertTrue("$c is not header-safe", c.code in 0x21..0x7E)
            }
        }
    }

    @Test
    fun `nothing usable is reported as nothing usable`() {
        assertEquals("", ApiKeys.clean("   "))
        assertEquals("", ApiKeys.clean("\"\""))
        assertFalse(ApiKeys.isUsable("   "))
        assertFalse(ApiKeys.isUsable(null))
        assertFalse(ApiKeys.isUsable(""))
    }

    @Test
    fun `each repair says what it repaired`() {
        // Silently fixing and silently failing look the same from outside, so every
        // repair has to be reportable.
        assertNotNull(ApiKeys.problem("\u00A0$REAL"))
        assertNotNull(ApiKeys.problem("Bearer $REAL"))
        assertNotNull(ApiKeys.problem("\"$REAL\""))
        assertNotNull(ApiKeys.problem("$REAL extra"))
        // Nothing to report about a key that needed nothing.
        assertNull(ApiKeys.problem(REAL))
        assertNull(ApiKeys.problem(""))
    }

    @Test
    fun `the invisible character gets its own message`() {
        // Because it is the one a person cannot find by looking, it must be named
        // rather than lumped in with "stray space".
        val note = ApiKeys.problem("\u00A0$REAL").orEmpty()
        assertTrue(note, note.contains("invisible"))
    }

    // ------------------------------------------------------- the wrong provider

    @Test
    fun `a key from another provider is named`() {
        val anthropic = "sk-ant-api03-abcdef"
        val note = ApiKeys.mismatch(Provider.OPENROUTER, anthropic).orEmpty()
        assertTrue(note, note.contains("Anthropic"))
        assertTrue(note, note.contains("OpenRouter"))
    }

    @Test
    fun `a key that matches its provider is not complained about`() {
        assertNull(ApiKeys.mismatch(Provider.OPENROUTER, REAL))
        assertNull(ApiKeys.mismatch(Provider.ANTHROPIC, "sk-ant-api03-abcdef"))
        assertNull(ApiKeys.mismatch(Provider.GROK, "xai-abcdef"))
        assertNull(ApiKeys.mismatch(Provider.GEMINI, "AIzaSyAbcdef"))
    }

    @Test
    fun `an unfamiliar key shape is left alone`() {
        // Providers change their formats. Recognising a key as somebody else's is
        // safe; refusing one for not looking familiar would break the app the day a
        // provider changes its prefix.
        assertNull(ApiKeys.mismatch(Provider.OPENROUTER, "abc123-not-a-known-shape"))
        assertNull(ApiKeys.mismatch(Provider.ANTHROPIC, "abc123-not-a-known-shape"))
        assertNull(ApiKeys.mismatch(Provider.OPENROUTER, ""))
    }

    @Test
    fun `the prefix check reads the key after cleaning it`() {
        // Otherwise "Bearer sk-ant-…" fails to be recognised as an Anthropic key,
        // and the one message that would have explained the failure never appears.
        assertNotNull(ApiKeys.mismatch(Provider.OPENROUTER, " Bearer sk-ant-api03-abcdef "))
    }
}
