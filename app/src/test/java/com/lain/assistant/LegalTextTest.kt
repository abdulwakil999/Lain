package com.lain.assistant

import com.lain.assistant.legal.LegalText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The documents have to describe the app that actually shipped.
 *
 * A privacy policy is the one piece of text in the project a user cannot check
 * against the code, so these pin the claims that have already gone stale once — and
 * would go stale again silently, because nothing else fails when a policy is wrong.
 */
class LegalTextTest {

    @Test
    fun `the policy does not claim location is never requested`() {
        // It was true, then location was added for "where am I" and the sentence
        // stayed. That is the exact failure this file exists to catch.
        assertFalse(
            "the policy still says location is never requested",
            LegalText.PRIVACY.contains("Location is never requested")
        )
        assertTrue(
            "location is requested and the policy doesn't say what for",
            LegalText.PRIVACY.contains("coarse")
        )
    }

    @Test
    fun `the policy names the screen by the name on the screen`() {
        assertTrue(LegalText.PRIVACY.contains("Memoria"))
    }

    @Test
    fun `both documents cover the capabilities that were added`() {
        assertTrue("code and schoolwork aren't in the privacy policy", LegalText.PRIVACY.contains("schoolwork"))
        assertTrue("schoolwork isn't in the terms", LegalText.TERMS.contains("SCHOOLWORK"))
        // She calls the user a fool. That belongs in what they agreed to.
        assertTrue("the personality isn't disclosed", LegalText.TERMS.contains("necio"))
    }

    @Test
    fun `a changed document is a changed version, with a summary`() {
        // The re-consent screen keys off this number; leaving it at 1 after editing
        // the text means nobody who already accepted is ever shown the change.
        assertTrue("VERSION was not bumped with the text", LegalText.VERSION >= 2)
        assertTrue(LegalText.WHATS_CHANGED.isNotBlank())
        assertTrue(LegalText.WHATS_CHANGED.contains("Location"))
    }

    @Test
    fun `the terms sections are numbered in order`() {
        val numbers = Regex("(?m)^\\s{0,12}(\\d+)\\. [A-Z]").findAll(LegalText.TERMS)
            .map { it.groupValues[1].toInt() }
            .toList()
        assertTrue("no numbered sections found", numbers.size >= 10)
        assertTrue("terms sections are misnumbered: $numbers", numbers == (1..numbers.size).toList())
    }
}
