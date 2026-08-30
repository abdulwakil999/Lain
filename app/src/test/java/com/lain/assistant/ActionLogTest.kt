package com.lain.assistant

import com.lain.assistant.data.CurrentGoal
import com.lain.assistant.legal.LegalText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record of what Lain did.
 *
 * Most of this lives behind Room and Compose, so what is pinned here is the part
 * that can silently go wrong without anything failing: which turn an action is
 * attributed to, and whether the documents still describe what is stored.
 */
class ActionLogTest {

    @Test
    fun `an action is attributed to the request that caused it`() {
        CurrentGoal.set("text ade that I'm running late")
        assertEquals("text ade that I'm running late", CurrentGoal.get())

        // A new turn re-points it, so an action never carries the previous request's
        // reason — which would be a log that reads plausibly and is wrong.
        CurrentGoal.set("turn the torch on")
        assertEquals("turn the torch on", CurrentGoal.get())
    }

    @Test
    fun `the goal is trimmed but never truncated into nonsense here`() {
        CurrentGoal.set("   open whatsapp   ")
        assertEquals("open whatsapp", CurrentGoal.get())
    }

    @Test
    fun `the policy lists the log, since it is new data kept on the phone`() {
        assertTrue("the log isn't in the policy", LegalText.PRIVACY.contains("A log of actions"))
        // And says what it deliberately does not hold.
        assertTrue(LegalText.PRIVACY.contains("no message contents"))
        // The user has to be able to get rid of it, and be told where it lives.
        assertTrue("the policy points at the wrong screen", LegalText.PRIVACY.contains("Historial"))
        assertTrue("no way to clear it is described", LegalText.PRIVACY.contains("clears all at once"))
        // And the change summary has to name the screen too, since that is the page
        // someone re-consenting actually reads.
        assertTrue(LegalText.WHATS_CHANGED.contains("Historial"))
    }

    @Test
    fun `saving facts unasked is disclosed, not silent`() {
        assertTrue(LegalText.WHATS_CHANGED.contains("without being asked"))
        assertTrue(LegalText.PRIVACY.contains("saved on her own"))
    }

    @Test
    fun `a changed policy is a changed version`() {
        assertTrue("VERSION was not bumped with the text", LegalText.VERSION >= 3)
    }
}
