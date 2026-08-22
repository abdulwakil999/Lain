package com.lain.assistant

import com.lain.assistant.automation.AccessibilityMonitor
import com.lain.assistant.automation.AccessibilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The state machine is the thing the whole accessibility audit rests on: if it
 * reports CONNECTED while nothing is bound, Lain tells the user an action happened
 * when it did not. These tests pin the transitions and, just as importantly, the
 * ones that must NOT happen.
 */
class AccessibilityMonitorTest {

    @Before
    fun setUp() = AccessibilityMonitor.resetForTest()

    @Test
    fun `starts unknown rather than claiming either answer`() {
        assertEquals(AccessibilityState.UNKNOWN, AccessibilityMonitor.state.value)
        assertFalse(AccessibilityMonitor.isUsable)
    }

    @Test
    fun `connect then unbind walks connected to disconnected`() {
        AccessibilityMonitor.onCreated()
        AccessibilityMonitor.onConnected()
        assertEquals(AccessibilityState.CONNECTED, AccessibilityMonitor.state.value)
        assertTrue(AccessibilityMonitor.isUsable)

        AccessibilityMonitor.onUnbound()
        assertEquals(AccessibilityState.DISCONNECTED, AccessibilityMonitor.state.value)
        assertFalse(AccessibilityMonitor.isUsable)
    }

    @Test
    fun `interrupt is not a disconnect`() {
        AccessibilityMonitor.onConnected()
        AccessibilityMonitor.onInterrupted()
        // onInterrupt means "stop announcing", not "binding lost". Downgrading here
        // would make Lain refuse actions she can still perform.
        assertEquals(AccessibilityState.CONNECTED, AccessibilityMonitor.state.value)
        assertTrue(AccessibilityMonitor.isUsable)
        assertTrue(AccessibilityMonitor.dump().contains("SERVICE_INTERRUPTED"))
    }

    @Test
    fun `a reconnect within one process is recorded as a restart`() {
        AccessibilityMonitor.onConnected()
        AccessibilityMonitor.onDestroyed()
        AccessibilityMonitor.onConnected()

        assertEquals(2, AccessibilityMonitor.connectCount)
        assertTrue(AccessibilityMonitor.dump().contains("SERVICE_RESTARTED"))
        assertEquals(AccessibilityState.CONNECTED, AccessibilityMonitor.state.value)
    }

    @Test
    fun `every state carries advice that matches it`() {
        assertTrue(AccessibilityMonitor.advice().contains("hasn't been able to check"))

        AccessibilityMonitor.onConnected()
        assertTrue(AccessibilityMonitor.advice().contains("connected"))

        AccessibilityMonitor.onDestroyed()
        // Not "turn it on" — that's the DISCONNECTED wording and it is what the user
        // kept being told while the switch was already on.
        assertTrue(AccessibilityMonitor.advice().contains("Accessibility"))
    }

    @Test
    fun `the log is bounded and keeps the newest entries`() {
        repeat(500) { AccessibilityMonitor.record(AccessibilityMonitor.Event.COMMAND_RECEIVED, "tap #$it") }
        val dump = AccessibilityMonitor.dump()
        assertTrue(dump.contains("tap #499"))
        assertFalse(dump.contains("tap #0 "))
        // 200-entry ring plus the four header/blank lines.
        assertTrue(dump.lines().size < 220)
    }

    @Test
    fun `state changes are themselves logged so a drop can be traced`() {
        AccessibilityMonitor.onConnected()
        AccessibilityMonitor.onUnbound()
        val dump = AccessibilityMonitor.dump()
        assertTrue(dump.contains("UNKNOWN -> CONNECTED"))
        assertTrue(dump.contains("CONNECTED -> DISCONNECTED"))
    }

    @Test
    fun `a repeated transition does not spam the log`() {
        AccessibilityMonitor.onConnected()
        val before = AccessibilityMonitor.dump().lines().count { it.contains("STATE_CHANGED") }
        AccessibilityMonitor.onConnected()
        val after = AccessibilityMonitor.dump().lines().count { it.contains("STATE_CHANGED") }
        assertEquals(before, after)
    }
}
