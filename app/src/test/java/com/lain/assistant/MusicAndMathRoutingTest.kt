package com.lain.assistant

import com.lain.assistant.agent.Calculator
import com.lain.assistant.agent.FastRouter
import com.lain.assistant.agent.LocalIntent
import com.lain.assistant.agent.Route
import com.lain.assistant.agent.TransportAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Playback is the command people repeat most, so it has to be instant and never wrong. */
class MusicRoutingTest {

    private fun local(input: String): LocalIntent {
        val route = FastRouter.route(input)
        assertTrue("\"$input\" was not routed locally: $route", route is Route.Local)
        return (route as Route.Local).intent
    }

    @Test
    fun `transport controls resolve to media keys`() {
        val expectations = mapOf(
            "pause" to TransportAction.PAUSE,
            "pause the music" to TransportAction.PAUSE,
            "resume" to TransportAction.PLAY,
            "next" to TransportAction.NEXT,
            "skip this" to TransportAction.NEXT,
            "next track" to TransportAction.NEXT,
            "previous song" to TransportAction.PREVIOUS,
            "stop playing" to TransportAction.STOP
        )
        expectations.forEach { (input, action) ->
            assertEquals(input, action, (local(input) as LocalIntent.Transport).action)
        }
    }

    @Test
    fun `play with a title becomes a search on the named player`() {
        val spotify = local("play burna boy on spotify") as LocalIntent.PlayMusic
        assertEquals("burna boy", spotify.query)
        assertEquals("spotify", spotify.app)

        val anyPlayer = local("play last last") as LocalIntent.PlayMusic
        assertEquals("last last", anyPlayer.query)
        assertNull(anyPlayer.app)

        val album = local("play the album rave and roses on spotify") as LocalIntent.PlayMusic
        assertEquals("rave and roses", album.query)
        assertEquals("spotify", album.app)
    }

    @Test
    fun `a title containing "on" is not mistaken for a player name`() {
        // The subtle failure: splitting on the last "on" would leave "live" as the
        // query and "broadway" as the player.
        val track = local("play live on broadway") as LocalIntent.PlayMusic
        assertEquals("live on broadway", track.query)
        assertNull(track.app)
    }

    @Test
    fun `vague requests start playback without a search`() {
        listOf("play some music", "put some music on", "play something").forEach {
            val intent = local(it) as LocalIntent.PlayMusic
            assertEquals(it, "", intent.query)
        }
        val onSpotify = local("play some music on spotify") as LocalIntent.PlayMusic
        assertEquals("", onSpotify.query)
        assertEquals("spotify", onSpotify.app)
    }

    @Test
    fun `play spotify means start it playing, not open the app`() {
        val intent = local("play spotify") as LocalIntent.PlayMusic
        assertEquals("spotify", intent.app)
        assertEquals("", intent.query)
        // Whereas "open spotify" is still a plain app launch.
        assertTrue(local("open spotify") is LocalIntent.OpenApp)
    }

    @Test
    fun `compound and conversational music requests go to the model`() {
        listOf(
            "play something and then text ade",
            "why won't spotify play anything",
            "should I play this on spotify or youtube",
            "what do you think of burna boy"
        ).forEach {
            assertTrue("\"$it\" must not be handled locally", FastRouter.route(it) !is Route.Local)
        }
    }
}

/**
 * Small models produce confident wrong arithmetic. These cases are checked against
 * exact expected values because "approximately right" is the failure being removed.
 */
class CalculatorTest {

    private fun value(input: String): String =
        Calculator.evaluate(input)?.pretty() ?: error("\"$input\" did not evaluate")

    @Test
    fun `arithmetic respects precedence and associativity`() {
        assertEquals("14", value("2 + 3 * 4"))
        assertEquals("20", value("(2 + 3) * 4"))
        assertEquals("512", value("2^3^2"))
        assertEquals("-5", value("5 - 10"))
        assertEquals("2.5", value("5 / 2"))
        assertEquals("1234321", value("1111 * 1111"))
    }

    @Test
    fun `floating point dust is rounded away`() {
        // 0.1 + 0.2 is 0.30000000000000004 in binary floating point. Showing that
        // would make correct arithmetic look broken.
        assertEquals("0.3", value("0.1 + 0.2"))
    }

    @Test
    fun `percentages`() {
        assertEquals("36", value("what's 15% of 240"))
        assertEquals("36", value("15 percent of 240"))
        assertEquals("170", value("15% off 200"))
        assertEquals("230", value("15% on 200"))
    }

    @Test
    fun `unit conversion covers the common families`() {
        assertEquals("3.1068559612", value("convert 5km to miles"))
        assertEquals("100", value("1 m in cm"))
        assertEquals("2.2046226218", value("1 kg in pounds"))
        assertEquals("90", value("1.5 hours in minutes"))
        assertEquals("1024", value("1 gb in mb"))
    }

    @Test
    fun `temperature is converted with offsets, not ratios`() {
        assertEquals("100", value("212 f to c"))
        assertEquals("32", value("0 c to f"))
        assertEquals("273.15", value("0 c to k"))
    }

    @Test
    fun `mismatched units decline rather than guess`() {
        assertNull(Calculator.evaluate("5 km to kilograms"))
        assertNull(Calculator.evaluate("3 bananas to miles"))
    }

    @Test
    fun `prose and bare numbers are left to the model`() {
        listOf(
            "42",
            "call me at 5",
            "what's the meaning of life",
            "open app 2",
            "play 24k magic"
        ).forEach {
            assertNull("\"$it\" should not be treated as a calculation", Calculator.evaluate(it))
        }
    }

    @Test
    fun `division by zero declines instead of returning infinity`() {
        assertNull(Calculator.evaluate("5 / 0"))
    }

    @Test
    fun `a calculation routes locally, never to the model`() {
        listOf("what's 15% of 240", "2 + 2 * 3", "convert 5km to miles").forEach {
            assertTrue("\"$it\" should be answered locally", FastRouter.route(it) is Route.Local)
        }
    }
}
