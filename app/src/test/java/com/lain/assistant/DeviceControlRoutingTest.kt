package com.lain.assistant

import com.lain.assistant.agent.FastRouter
import com.lain.assistant.agent.LocalIntent
import com.lain.assistant.agent.Route
import com.lain.assistant.automation.SystemToggles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These commands must never reach a model. Each one that does costs a round trip
 * the user waits through, and the compound ones used to cost five.
 */
class DeviceControlRoutingTest {

    private fun local(input: String): LocalIntent? =
        (FastRouter.route(input) as? Route.Local)?.intent

    // -------------------------------------------------------------- searching

    @Test
    fun `open an app and search in it is one local action`() {
        // The exact phrasing that stalled: five round trips, then a question back.
        val intent = local("open chrome and search animeheaven")
        assertTrue("got $intent", intent is LocalIntent.SearchIn)
        intent as LocalIntent.SearchIn
        assertEquals("chrome", intent.app)
        assertEquals("animeheaven", intent.query)
    }

    @Test
    fun `search phrasings all resolve`() {
        assertEquals("youtube", (local("search youtube for lofi") as LocalIntent.SearchIn).app)
        assertEquals("lofi", (local("search youtube for lofi") as LocalIntent.SearchIn).query)

        val onForm = local("search cat videos on youtube") as LocalIntent.SearchIn
        assertEquals("youtube", onForm.app)
        assertEquals("cat videos", onForm.query)
    }

    @Test
    fun `a plain app launch is still a launch, not a search`() {
        assertTrue(local("open chrome") is LocalIntent.OpenApp)
        assertTrue(local("open whatsapp") is LocalIntent.OpenApp)
    }

    // --------------------------------------------------------------- toggles

    @Test
    fun `toggles route locally in both directions`() {
        assertEquals(true, (local("turn on wifi") as LocalIntent.SystemToggle).on)
        assertEquals(false, (local("turn off bluetooth") as LocalIntent.SystemToggle).on)
        assertEquals(true, (local("enable hotspot") as LocalIntent.SystemToggle).on)
        assertEquals(false, (local("switch off location") as LocalIntent.SystemToggle).on)
    }

    @Test
    fun `a toggle with no direction is not flipped`() {
        // "wifi" on its own is a question about Wi-Fi. Flipping it because the word
        // appeared would be the wrong kind of helpful.
        val intent = local("wifi")
        assertTrue("got $intent", intent !is LocalIntent.SystemToggle)
    }

    @Test
    fun `every named toggle maps to something real`() {
        listOf(
            "wifi" to SystemToggles.Toggle.WIFI,
            "wi-fi" to SystemToggles.Toggle.WIFI,
            "bluetooth" to SystemToggles.Toggle.BLUETOOTH,
            "mobile data" to SystemToggles.Toggle.MOBILE_DATA,
            "hotspot" to SystemToggles.Toggle.HOTSPOT,
            "location" to SystemToggles.Toggle.LOCATION,
            "gps" to SystemToggles.Toggle.LOCATION,
            "flight mode" to SystemToggles.Toggle.AIRPLANE
        ).forEach { (spoken, expected) ->
            assertEquals(spoken, expected, SystemToggles.Toggle.from(spoken))
        }
    }

    @Test
    fun `an unknown toggle name resolves to nothing rather than a wrong switch`() {
        assertNull(SystemToggles.Toggle.from("nfc payments"))
        assertNull(SystemToggles.Toggle.from(""))
    }

    // --------------------------------------------------------------- closing

    @Test
    fun `closing an app is local`() {
        assertEquals("whatsapp", (local("close whatsapp") as LocalIntent.CloseApp).appName)
        assertEquals("", (local("close this app") as LocalIntent.CloseApp).appName)
    }

    @Test
    fun `stopping music is transport, not an app close`() {
        // "stop music" must not be read as closing an app called "music".
        val intent = local("stop music")
        assertTrue("got $intent", intent is LocalIntent.Transport)
    }

    @Test
    fun `clearing background apps is local`() {
        assertTrue(local("close background apps") is LocalIntent.ClearRecents)
        assertTrue(local("clear recent apps") is LocalIntent.ClearRecents)
    }
}
