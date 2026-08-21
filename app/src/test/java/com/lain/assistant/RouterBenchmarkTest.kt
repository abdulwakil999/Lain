package com.lain.assistant

import com.lain.assistant.agent.FastRouter
import com.lain.assistant.agent.LocalIntent
import com.lain.assistant.agent.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The router's whole justification is that it is faster than a network round trip
 * by orders of magnitude, and that it is right about what it claims. Both are
 * checked here, because either one failing removes the point of it.
 */
class RouterBenchmarkTest {

    private val commands = listOf(
        "what time is it", "what's my battery", "open youtube", "set a timer for 10 minutes",
        "open wifi settings", "call John", "volume up", "torch on", "go home", "what's on screen"
    )

    @Test
    fun `routing a command costs microseconds, not a round trip`() {
        // Warm the JIT so this measures steady-state cost rather than first-run.
        repeat(2_000) { commands.forEach { FastRouter.route(it) } }

        val iterations = 10_000
        val started = System.nanoTime()
        repeat(iterations) { commands.forEach { FastRouter.route(it) } }
        val perCallMicros = (System.nanoTime() - started) / (iterations.toDouble() * commands.size) / 1_000.0

        println("FastRouter: %.1fµs per command".format(perCallMicros))
        // A network round trip to a model is 300_000µs at the very best. Anything
        // under 100µs means routing is free relative to what it replaces; the real
        // measured figure is far below this, so the assertion is a regression guard
        // rather than a target.
        assertTrue("routing took ${perCallMicros}µs, expected <100µs", perCallMicros < 100.0)
    }

    @Test
    fun `commands Android can answer never reach a model`() {
        val expectations = mapOf(
            "what time is it" to LocalIntent.Clock::class,
            "what's the date" to LocalIntent.Clock::class,
            "what's my battery" to LocalIntent.Battery::class,
            "am I charging" to LocalIntent.Battery::class,
            "open youtube" to LocalIntent.OpenApp::class,
            "launch spotify" to LocalIntent.OpenApp::class,
            "set a timer for 10 minutes" to LocalIntent.Timer::class,
            "remind me in 2 hours to call the bank" to LocalIntent.Timer::class,
            "open wifi settings" to LocalIntent.SettingsPage::class,
            "call John" to LocalIntent.Call::class,
            "volume up" to LocalIntent.Volume::class,
            "set volume to 40" to LocalIntent.Volume::class,
            "turn on the torch" to LocalIntent.Torch::class,
            "go home" to LocalIntent.Navigate::class,
            "what's on screen" to LocalIntent.ReadScreen::class,
            "turn on wifi" to LocalIntent.ToggleRequest::class
        )
        expectations.forEach { (input, expected) ->
            val route = FastRouter.route(input)
            assertTrue("\"$input\" was not routed locally: $route", route is Route.Local)
            assertEquals(input, expected, (route as Route.Local).intent::class)
        }
    }

    @Test
    fun `politeness does not defeat the router`() {
        listOf(
            "hey lain, open youtube please",
            "can you open youtube",
            "could you please open youtube for me",
            "ok open youtube now"
        ).forEach {
            assertTrue("\"$it\" should still route locally", FastRouter.route(it) is Route.Local)
        }
    }

    @Test
    fun `questions about a command go to the model, not the command`() {
        // The dangerous failure mode: acting on something that was being discussed.
        listOf(
            "why won't youtube open",
            "should I open youtube or use the browser",
            "how come my battery drains so fast",
            "explain how the torch works",
            "what's the difference between wifi and mobile data",
            "is it better to call John or text him"
        ).forEach {
            assertTrue("\"$it\" must not be executed locally", FastRouter.route(it) !is Route.Local)
        }
    }

    @Test
    fun `compound instructions go to the agent loop`() {
        listOf(
            "open whatsapp and text ade",
            "call John and tell him I'm late",
            "open spotify then play something chill"
        ).forEach {
            assertTrue("\"$it\" needs the full loop", FastRouter.route(it) !is Route.Local)
        }
    }

    @Test
    fun `conversation still skips the tool surface`() {
        listOf("good morning", "how are you", "thanks", "tell me a joke").forEach {
            assertEquals(it, Route.Chat, FastRouter.route(it))
        }
    }
}
