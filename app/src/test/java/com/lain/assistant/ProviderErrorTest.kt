package com.lain.assistant

import com.lain.assistant.data.Provider
import com.lain.assistant.network.ProviderError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a failed request is allowed to claim.
 *
 * The rule these hold down: only a 401 may send somebody to look at their API key.
 * Verified against the live API — OpenRouter answers a key it has never seen with
 * 401 "User not found." — so a 403 reported as a bad key is the app inventing a
 * cause, which is the one thing this project's replies are not allowed to do.
 */
class ProviderErrorTest {

    @Test
    fun `only a 401 is about the key`() {
        assertTrue(ProviderError.isAboutTheKey(401))
        assertFalse(ProviderError.isAboutTheKey(403))
        assertFalse(ProviderError.isAboutTheKey(402))
        assertFalse(ProviderError.isAboutTheKey(404))
        assertFalse(ProviderError.isAboutTheKey(429))
        assertFalse(ProviderError.isAboutTheKey(500))
    }

    @Test
    fun `a 401 says the key was not recognised`() {
        val said = ProviderError.describe(
            401, """{"error":{"message":"User not found.","code":401}}""", Provider.OPENROUTER
        )
        assertTrue(said, said.contains("401"))
        assertTrue(said, said.contains("User not found."))
        assertTrue(said, said.contains("key", ignoreCase = true))
    }

    @Test
    fun `a 403 does not blame the key`() {
        val said = ProviderError.describe(
            403, """{"error":{"message":"Account restricted","code":403}}""", Provider.OPENROUTER
        )
        assertTrue(said, said.contains("403"))
        assertTrue(said, said.contains("Account restricted"))
        // The whole point: it must say this is not the key, not send them to Settings
        // to re-paste one that works.
        assertTrue(said, said.contains("not the key"))
    }

    @Test
    fun `a model the provider will not serve this app is named as such`() {
        // OpenRouter's actual words, from a real report. The catalogue lists this
        // model as free and says nothing about the restriction — the refusal is the
        // only place it is ever stated.
        val body = """{"error":{"message":"thinkingmachines/inkling:free is only available on agentic harnesses. Try plugging it into a coding agent or productivity app listed on https://openrouter.ai/apps","code":403}}"""
        val said = ProviderError.describe(403, body, Provider.OPENROUTER)
        assertTrue(said, said.contains("restricted to"))
        // It must clear the user's key and account, because neither is involved.
        assertTrue(said, said.contains("Nothing is wrong with your key"))
        assertTrue(said, said.contains("pick another model", ignoreCase = true))

        // And it has to be recognisable as a model problem, so the app switches off
        // the model instead of failing the same way on every message.
        assertTrue(ProviderError.refusesThisModel(403, body))
    }

    @Test
    fun `an ordinary 403 is not mistaken for a restricted model`() {
        val body = """{"error":{"message":"Account restricted","code":403}}"""
        assertFalse(ProviderError.refusesThisModel(403, body))
        // Nor is any other status, whatever it says.
        assertFalse(
            ProviderError.refusesThisModel(
                429, """{"error":{"message":"only available on agentic harnesses"}}"""
            )
        )
    }

    @Test
    fun `a page instead of an answer is reported as a blocked request`() {
        val blocked = ProviderError.describe(
            403,
            "<!DOCTYPE html><html><head><title>Access denied | Cloudflare</title></head></html>",
            Provider.OPENROUTER
        )
        assertTrue(blocked, blocked.contains("never reached"))
        assertTrue(blocked, blocked.contains("VPN"))
        // And it must not leave them thinking the key is at fault.
        assertTrue(blocked, blocked.contains("key is not involved"))
    }

    @Test
    fun `the free-model data policy gets its own answer`() {
        // The one that hits everybody who picks a free model and has never opened
        // OpenRouter's privacy page — and which looks exactly like a broken app.
        val said = ProviderError.describe(
            404,
            """{"error":{"message":"No endpoints found matching your data policy.","code":404}}""",
            Provider.OPENROUTER
        )
        assertTrue(said, said.contains("Privacy"))
    }

    @Test
    fun `a plain 404 is about the model`() {
        val said = ProviderError.describe(
            404, """{"error":{"message":"No such model","code":404}}""", Provider.OPENROUTER
        )
        assertTrue(said, said.contains("model"))
        assertFalse(said, said.contains("Privacy"))
    }

    // ------------------------------------------------------- reading the body

    @Test
    fun `the provider's own words are found in every shape they arrive in`() {
        assertEquals(
            "User not found.",
            ProviderError.messageIn("""{"error":{"message":"User not found.","code":401}}""")
        )
        assertEquals("plain", ProviderError.messageIn("""{"error":"plain"}"""))
        assertEquals("detail here", ProviderError.messageIn("""{"detail":"detail here"}"""))
        assertEquals("top level", ProviderError.messageIn("""{"message":"top level"}"""))
    }

    @Test
    fun `a body that is not an api error is not invented into one`() {
        assertNull(ProviderError.messageIn("<html><body>nope</body></html>"))
        assertNull(ProviderError.messageIn(""))
        assertNull(ProviderError.messageIn("not json at all"))
        assertNull(ProviderError.messageIn("""{"unrelated":1}"""))
    }
}
