package com.lain.assistant

import com.lain.assistant.agent.FastRouter
import com.lain.assistant.agent.LocalIntent
import com.lain.assistant.agent.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controls Lain asked for, answered on the device rather than through a model.
 */
class DeviceControlsTest {

    private fun local(message: String): LocalIntent? =
        (FastRouter.route(message) as? Route.Local)?.intent

    // ------------------------------------------------------------- volume

    @Test
    fun `a named volume is not the media volume`() {
        // Getting this wrong leaves a phone ringing at full volume in a meeting,
        // having reported success.
        val ringer = local("turn the ringer down") as? LocalIntent.Volume
        assertEquals("ring", ringer?.stream)

        assertEquals("notification", (local("set notification volume to 20") as? LocalIntent.Volume)?.stream)
        assertEquals("alarm", (local("alarm volume up") as? LocalIntent.Volume)?.stream)
        assertEquals("ring", (local("set ringtone volume to 60") as? LocalIntent.Volume)?.stream)
    }

    @Test
    fun `an unqualified volume request still means media`() {
        listOf("volume up", "turn the volume down", "set volume to 40", "mute the sound").forEach {
            val volume = local(it) as? LocalIntent.Volume
            assertEquals("\"$it\" changed the wrong stream", "media", volume?.stream)
        }
    }

    // --------------------------------------------------------- brightness

    @Test
    fun `brightness is handled without a model`() {
        assertEquals(
            LocalIntent.Brightness(percent = 70, direction = 0),
            local("set brightness to 70")
        )
        assertEquals(
            LocalIntent.Brightness(percent = 100, direction = 0),
            local("brightness max")
        )
        assertEquals(
            LocalIntent.Brightness(percent = null, direction = 0, auto = true),
            local("put brightness back on auto")
        )

        val dimmer = local("dim the screen") as? LocalIntent.Brightness
        assertTrue("dimming should go down", (dimmer?.direction ?: 0) < 0)

        val brighter = local("brighten the screen") as? LocalIntent.Brightness
        assertTrue("brightening should go up", (brighter?.direction ?: 0) > 0)
    }

    @Test
    fun `asking for the brightness settings page is not a brightness change`() {
        assertTrue(local("open brightness settings") !is LocalIntent.Brightness)
    }

    @Test
    fun `dim on its own is not a screen command`() {
        // "dim" appears in plenty of sentences that are not about the display, so it
        // only counts when the screen is named.
        assertTrue(local("the future looks dim") !is LocalIntent.Brightness)
    }
}
