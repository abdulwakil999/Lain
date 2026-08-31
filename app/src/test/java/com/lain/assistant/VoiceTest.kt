package com.lain.assistant

import com.lain.assistant.agent.FastRouter
import com.lain.assistant.agent.IdentityQuestion
import com.lain.assistant.agent.LocalIntent
import com.lain.assistant.agent.Replies
import com.lain.assistant.agent.Route
import com.lain.assistant.tts.FishAudioTtsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Her name's real origin, and the set of lines that has to survive going offline.
 */
class VoiceTest {

    // ---------------------------------------------------------------- name

    @Test
    fun `the anime question is answered on the device`() {
        listOf(
            "are you named after serial experiments lain",
            "are you from the anime",
            "is your name from the anime",
            "were you named after the manga",
            "is that name from serial experiments lain"
        ).forEach { ask ->
            val route = FastRouter.route(ask)
            assertTrue("\"$ask\" went to a model, which will say yes", route is Route.Local)
            assertEquals(
                LocalIntent.Identity(IdentityQuestion.ANIME),
                (route as Route.Local).intent
            )
        }
    }

    @Test
    fun `every anime answer gives the real origin, not just a denial`() {
        assertTrue(Replies.animeName.size >= 6)
        Replies.animeName.forEach {
            assertTrue(
                "\"${it.text}\" denies the anime without saying where the name is from",
                it.text.contains("Leave-it-to-Artificial-intelligence-Necio")
            )
        }
    }

    @Test
    fun `asking where the name comes from stays local too`() {
        listOf("where does your name come from", "why are you called lain", "why lain")
            .forEach { ask ->
                val route = FastRouter.route(ask)
                assertTrue("\"$ask\" left the device", route is Route.Local)
            }
    }

    @Test
    fun `the acronym is still the origin everywhere it is stated`() {
        // The anime may be referenced. It may never be the source.
        val referencing = Replies.lainName.filter { it.text.contains("anime", ignoreCase = true) }
        assertTrue("she can't reference it at all", referencing.isNotEmpty())
        referencing.forEach {
            assertTrue(
                "\"${it.text}\" leaves the anime as the origin",
                it.text.contains("acronym", ignoreCase = true)
            )
        }
    }

    // --------------------------------------------------------------- voice

    @Test
    fun `the offline pack covers what she says without a model`() {
        val lines = Replies.fixedLines.map { it.text }

        // If a bank is missing from the pack it never gets downloaded, and that line
        // alone drops to the device voice with nothing to indicate why.
        listOf(
            Replies.greetings, Replies.thanks, Replies.howAreYou, Replies.goodbyes,
            Replies.affirmations, Replies.lainName, Replies.appName, Replies.necio,
            Replies.animeName, Replies.developer, Replies.developerChallenge,
            Replies.developerAccepted, Replies.developerRejected, Replies.capabilities,
            Replies.sassPrefixes
        ).forEach { bank ->
            bank.forEach { line ->
                assertTrue("\"${line.text}\" is not in the downloadable set", line.text in lines)
            }
        }
        assertTrue("suspiciously few lines to cache", lines.size > 60)
    }

    @Test
    fun `the default voice is Fish Audio's own, not one picked from memory`() {
        // A reference_id recalled rather than looked up would eventually stop
        // resolving, and a voice that fails silently is the worst kind. Blank means
        // the model's own default, which always exists.
        assertEquals("default", FishAudioTtsEngine.DEFAULT_VOICE_KEY)
    }

    @Test
    fun `the endpoint and default model are the documented ones`() {
        assertEquals("https://api.fish.audio/v1/tts", FishAudioTtsEngine.ENDPOINT)
        assertEquals("s2.1-pro", FishAudioTtsEngine.DEFAULT_MODEL)
    }
}
