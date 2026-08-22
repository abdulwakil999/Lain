package com.lain.assistant

import androidx.compose.ui.unit.dp
import com.lain.assistant.ui.common.LainWindow
import com.lain.assistant.ui.common.WidthClass
import com.lain.assistant.ui.common.HeightClass
import com.lain.assistant.ui.onboarding.OnboardingLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The onboarding panel has to leave Continue on screen on every device.
 *
 * This is checked arithmetically rather than visually because the failure is
 * silent and total: with a long step, an uncapped panel grows past the screen and
 * the button leaves the bottom, which strands the user on that step with no way
 * forward. It shipped that way once.
 */
class OnboardingLayoutTest {

    /** width x height in dp, and the navigation-bar inset. */
    private data class Device(val name: String, val w: Int, val h: Int, val navBar: Int)

    private val devices = listOf(
        Device("compact phone, gesture nav", 360, 640, 24),
        Device("compact phone, 3-button nav", 360, 640, 48),
        Device("tall phone (the reported one)", 417, 679, 48),
        Device("very tall phone", 412, 915, 24),
        Device("small/old phone", 320, 534, 48),
        Device("phone landscape", 731, 360, 24),
        Device("tablet portrait", 800, 1280, 24),
        Device("tablet landscape", 1280, 800, 24),
        Device("split screen, very short", 360, 380, 48)
    )

    /** Mirrors the composable: art size, then the panel's top offset from it. */
    private fun panelTopOffset(d: Device): Float {
        val window = LainWindow(
            width = when {
                d.w < 600 -> WidthClass.COMPACT
                d.w < 840 -> WidthClass.MEDIUM
                else -> WidthClass.EXPANDED
            },
            height = if (d.h < 480) HeightClass.SHORT else HeightClass.REGULAR,
            widthDp = d.w.dp,
            heightDp = d.h.dp
        )
        return window.artSize.value * 0.62f
    }

    @Test
    fun `the panel never exceeds the screen, so Continue stays visible`() {
        devices.forEach { d ->
            val m = OnboardingLayout.measure(d.h.dp, panelTopOffset(d).dp, d.navBar.dp)
            val panelHeight = m.scrollHeight.value + OnboardingLayout.PANEL_CHROME_HEIGHT.value + d.navBar
            val available = d.h - m.topOffset.value

            assertTrue(
                "${d.name}: panel is ${panelHeight}dp but only ${available}dp is available — " +
                    "Continue would be off screen",
                panelHeight <= available + 0.5f
            )
        }
    }

    @Test
    fun `on a short screen the panel slides up rather than overflowing`() {
        // Landscape and split-screen can't fit the panel below the portrait. Covering
        // some of the picture is the correct trade against losing the button.
        val short = devices.first { it.name == "phone landscape" }
        val m = OnboardingLayout.measure(short.h.dp, panelTopOffset(short).dp, short.navBar.dp)
        assertTrue(
            "the panel should have moved up to make room",
            m.topOffset.value < panelTopOffset(short)
        )
        assertTrue("but never off the top", m.topOffset.value >= OnboardingLayout.MIN_TOP_OFFSET.value)
    }

    @Test
    fun `the list still gets usable room on ordinary phones`() {
        // A cap that leaves two chips visible would technically pass the first test
        // while being horrible to use.
        devices.filter { it.h >= 600 }.forEach { d ->
            val m = OnboardingLayout.measure(d.h.dp, panelTopOffset(d).dp, d.navBar.dp)
            assertTrue("${d.name}: only ${m.scrollHeight.value}dp for the list", m.scrollHeight.value >= 180f)
        }
    }

    @Test
    fun `an ordinary phone keeps the panel under the portrait`() {
        // The slide-up is a fallback, not the normal case — it shouldn't fire on a
        // regular phone and start covering the artwork for no reason.
        val phone = devices.first { it.name == "tall phone (the reported one)" }
        val m = OnboardingLayout.measure(phone.h.dp, panelTopOffset(phone).dp, phone.navBar.dp)
        assertEquals(panelTopOffset(phone), m.topOffset.value, 0.5f)
    }

    @Test
    fun `the navigation bar is charged for`() {
        // The bug caught in review: identical screens differing only by nav bar must
        // not produce the same amount of room.
        val withGesture = OnboardingLayout.measure(679.dp, 193.dp, 24.dp)
        val withButtons = OnboardingLayout.measure(679.dp, 193.dp, 48.dp)
        assertTrue("the nav-bar inset is being ignored", withButtons.scrollHeight.value < withGesture.scrollHeight.value)
    }
}
