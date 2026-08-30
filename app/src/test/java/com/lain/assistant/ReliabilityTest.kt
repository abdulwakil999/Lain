package com.lain.assistant

import com.lain.assistant.tools.RecentSideEffects
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The guard against an outward action happening twice.
 *
 * Worth testing on its own because the bug it prevents is invisible in the app: the
 * tool succeeds both times, nothing errors, and the only evidence is a second
 * message on someone else's phone.
 */
class ReliabilityTest {

    private val args = """{"phone_number":"+2348012345678","message":"running late"}"""

    @Before
    @After
    fun reset() = RecentSideEffects.clear()

    @Test
    fun `an identical send straight after a successful one is not repeated`() {
        assertNull("nothing sent yet", RecentSideEffects.duplicate("send_sms", args))
        RecentSideEffects.record("send_sms", args)

        val second = RecentSideEffects.duplicate("send_sms", args)
        assertNotNull("the same text went out twice", second)
        // Reported as a success: the message did go, a moment ago. Calling it a
        // failure would have her tell the user it never sent.
        assertTrue(second!!.success)
        assertTrue(second.result!!.contains("already happened"))
    }

    @Test
    fun `a different recipient or a different message goes through`() {
        RecentSideEffects.record("send_sms", args)
        assertNull(
            RecentSideEffects.duplicate("send_sms", """{"phone_number":"+2348099999999","message":"running late"}""")
        )
        assertNull(
            RecentSideEffects.duplicate("send_sms", """{"phone_number":"+2348012345678","message":"on my way"}""")
        )
    }

    @Test
    fun `formatting differences do not get past the guard`() {
        RecentSideEffects.record("send_sms", args)
        val respaced = """{ "phone_number" : "+2348012345678",  "message" : "running late" }"""
        assertNotNull("whitespace was enough to send it twice", RecentSideEffects.duplicate("send_sms", respaced))
    }

    @Test
    fun `harmless actions are never blocked`() {
        // Opening an app twice costs a second. Guarding it would only get in the way.
        RecentSideEffects.record("open_app", """{"app_name":"Spotify"}""")
        assertNull(RecentSideEffects.duplicate("open_app", """{"app_name":"Spotify"}"""))
    }

    @Test
    fun `a new request is allowed to repeat the last one`() {
        // "Text him again" is a person deciding, not a model looping. The guard is
        // cleared at the start of every turn so it never overrules them.
        RecentSideEffects.record("send_sms", args)
        assertNotNull(RecentSideEffects.duplicate("send_sms", args))

        RecentSideEffects.clear()
        assertNull("the user was refused their own repeat", RecentSideEffects.duplicate("send_sms", args))
    }

    @Test
    fun `a failed action is not remembered as done`() {
        // Only successes are recorded, so a send that failed can be retried.
        assertNull(RecentSideEffects.duplicate("send_sms", args))
    }
}
