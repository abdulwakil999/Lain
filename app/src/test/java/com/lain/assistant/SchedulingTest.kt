package com.lain.assistant

import com.lain.assistant.agent.FastRouter
import com.lain.assistant.agent.LocalIntent
import com.lain.assistant.agent.Route
import com.lain.assistant.agent.WhenParser
import com.lain.assistant.data.Repeat
import com.lain.assistant.data.ScheduledTask
import com.lain.assistant.data.TaskAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Clock arithmetic is the part of scheduling worth testing hardest: a language
 * model that misreads "2:30" costs the user a missed morning, and the mistake is
 * invisible until it's too late to matter.
 */
class SchedulingTest {

    /** A fixed reference point so "tomorrow" means something stable: Wed 10:00. */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private val wednesdayMorning = at(2026, Calendar.AUGUST, 19, 10, 0)

    private fun fields(millis: Long): Triple<Int, Int, Int> {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return Triple(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    // -------------------------------------------------------------- parsing

    @Test
    fun `an explicit am time later today stays today`() {
        val p = WhenParser.parse("set an alarm for 11:30 am", at(2026, Calendar.AUGUST, 19, 9, 0))
        assertNotNull(p)
        assertEquals(Triple(19, 11, 30), fields(p!!.triggerAtMillis))
    }

    @Test
    fun `a time already gone today rolls to tomorrow`() {
        val p = WhenParser.parse("wake me at 7 am", wednesdayMorning)
        assertNotNull(p)
        // 07:00 has passed at 10:00, so the user means tomorrow morning.
        assertEquals(Triple(20, 7, 0), fields(p!!.triggerAtMillis))
    }

    @Test
    fun `a bare afternoon-ish hour is not read as the middle of the night`() {
        // "meet at 3" means 15:00 to every human being who has ever said it.
        val p = WhenParser.parse("remind me at 3 to leave", wednesdayMorning)
        assertNotNull(p)
        assertEquals(15, fields(p!!.triggerAtMillis).second)
    }

    @Test
    fun `pm is honoured over the default`() {
        val p = WhenParser.parse("set an alarm for 9 pm", wednesdayMorning)
        assertEquals(21, fields(p!!.triggerAtMillis).second)
    }

    @Test
    fun `midnight is zero, not twelve`() {
        val p = WhenParser.parse("wake me at midnight", wednesdayMorning)
        assertEquals(0, fields(p!!.triggerAtMillis).second)
    }

    @Test
    fun `relative delays are handled without a clock time`() {
        val p = WhenParser.parse("remind me in 20 minutes to check the oven", wednesdayMorning)
        assertNotNull(p)
        assertEquals(wednesdayMorning + 20 * 60_000L, p!!.triggerAtMillis)
        assertEquals(Repeat.ONCE, p.repeat)
        assertTrue(p.remainder.contains("check the oven"))
    }

    @Test
    fun `repeat rules are recognised`() {
        assertEquals(Repeat.DAILY, WhenParser.parse("call mama every day at 8 pm", wednesdayMorning)!!.repeat)
        assertEquals(Repeat.WEEKDAYS, WhenParser.parse("wake me at 6 am every weekday", wednesdayMorning)!!.repeat)
        assertEquals(Repeat.WEEKENDS, WhenParser.parse("alarm for 9 am at weekends", wednesdayMorning)!!.repeat)
        assertEquals(Repeat.WEEKLY, WhenParser.parse("remind me every friday at 5 pm", wednesdayMorning)!!.repeat)
    }

    @Test
    fun `a named day wins over today`() {
        val p = WhenParser.parse("remind me on friday at 5 pm to file it", wednesdayMorning)
        assertNotNull(p)
        val c = Calendar.getInstance().apply { timeInMillis = p!!.triggerAtMillis }
        assertEquals(Calendar.FRIDAY, c.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `a phrase with no time at all is refused rather than guessed`() {
        // "later" is not a time. Guessing one produces an alarm the user never set.
        assertNull(WhenParser.parse("remind me about this later"))
        assertNull(WhenParser.parse("do the thing"))
    }

    @Test
    fun `a bare number that is not a time is not mistaken for one`() {
        assertNull(WhenParser.parse("remind me about room 7"))
    }

    @Test
    fun `the label survives the time words being stripped`() {
        val p = WhenParser.parse("remind me at 6 pm to take the tablets", wednesdayMorning)
        assertTrue(p!!.remainder.contains("take the tablets"))
        assertTrue(!p.remainder.contains("6"))
    }

    // ------------------------------------------------------------ recurrence

    @Test
    fun `a daily task moves to the same time tomorrow`() {
        val task = ScheduledTask(
            label = "call mama",
            action = TaskAction.CALL,
            target = "mama",
            triggerAtMillis = at(2026, Calendar.AUGUST, 19, 20, 0),
            repeat = Repeat.DAILY
        )
        val next = task.nextTrigger(after = at(2026, Calendar.AUGUST, 19, 20, 1))
        assertNotNull(next)
        assertEquals(Triple(20, 20, 0), fields(next!!))
    }

    @Test
    fun `a weekday task skips the weekend`() {
        // Friday evening: the next weekday occurrence is Monday, not Saturday.
        val friday = at(2026, Calendar.AUGUST, 21, 7, 0)
        val task = ScheduledTask(
            label = "standup", action = TaskAction.ALARM,
            triggerAtMillis = friday, repeat = Repeat.WEEKDAYS
        )
        val next = task.nextTrigger(after = friday + 60_000L)
        val c = Calendar.getInstance().apply { timeInMillis = next!! }
        assertEquals(Calendar.MONDAY, c.get(Calendar.DAY_OF_WEEK))
        assertEquals(7, c.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `a one-shot in the past is retired rather than re-armed`() {
        val task = ScheduledTask(
            label = "once", action = TaskAction.REMIND,
            triggerAtMillis = wednesdayMorning, repeat = Repeat.ONCE
        )
        assertNull(task.nextTrigger(after = wednesdayMorning + 1))
    }

    @Test
    fun `a weekly task lands on the same weekday a week later`() {
        val task = ScheduledTask(
            label = "bins", action = TaskAction.REMIND,
            triggerAtMillis = wednesdayMorning, repeat = Repeat.WEEKLY
        )
        val next = task.nextTrigger(after = wednesdayMorning + 1)!!
        assertEquals(Triple(26, 10, 0), fields(next))
    }

    @Test
    fun `describe reads like something a person would say`() {
        val task = ScheduledTask(
            label = "call mama", action = TaskAction.CALL, target = "mama",
            triggerAtMillis = at(2026, Calendar.AUGUST, 19, 19, 0), repeat = Repeat.DAILY
        )
        val text = task.describe()
        assertTrue(text.contains("19:00"))
        assertTrue(text.contains("every day"))
        assertTrue(text.contains("call mama"))
    }

    // ---------------------------------------------------------------- router

    @Test
    fun `setting an alarm never reaches the model`() {
        val route = FastRouter.route("set an alarm for 6:30 am")
        assertTrue(route is Route.Local)
        val intent = (route as Route.Local).intent
        assertTrue(intent is LocalIntent.Schedule)
        assertTrue((intent as LocalIntent.Schedule).alarm)
    }

    @Test
    fun `a recurring call is routed locally too`() {
        val route = FastRouter.route("remind me to call mama every day at 7 pm")
        assertTrue(route is Route.Local)
        assertTrue((route as Route.Local).intent is LocalIntent.Schedule)
    }

    @Test
    fun `an unparseable scheduling phrase goes to the model, not to a guessed alarm`() {
        val route = FastRouter.route("remind me about the thing at some point")
        assertTrue(route !is Route.Local)
    }

    @Test
    fun `asking what is scheduled is answered locally`() {
        assertTrue(FastRouter.route("what alarms do i have") is Route.Local)
        assertTrue(
            (FastRouter.route("list my reminders") as Route.Local).intent is LocalIntent.ListSchedule
        )
    }

    @Test
    fun `cancelling names what to cancel`() {
        val route = FastRouter.route("cancel my 7am alarm")
        val intent = (route as Route.Local).intent
        assertTrue(intent is LocalIntent.CancelSchedule)
        assertTrue((intent as LocalIntent.CancelSchedule).which.contains("7am"))
    }

    @Test
    fun `do not disturb is routed locally in both directions`() {
        assertEquals("priority", ((FastRouter.route("turn on do not disturb") as Route.Local).intent as LocalIntent.Dnd).mode)
        assertEquals("off", ((FastRouter.route("turn off do not disturb") as Route.Local).intent as LocalIntent.Dnd).mode)
    }

    @Test
    fun `silencing the phone means the ringer, not the media volume`() {
        val intent = (FastRouter.route("put my phone on silent") as Route.Local).intent
        assertTrue(intent is LocalIntent.Ringer)
        assertEquals("silent", (intent as LocalIntent.Ringer).mode)
    }

    @Test
    fun `muting the volume still means volume`() {
        // The ringer matcher must not steal a media-volume command.
        val intent = (FastRouter.route("mute the volume") as Route.Local).intent
        assertTrue(intent is LocalIntent.Volume)
    }

    @Test
    fun `a countdown stays a timer rather than becoming a scheduled task`() {
        // "in two hours" is phrased better by the timer path, and the existing
        // behaviour is worth keeping rather than absorbing into the scheduler.
        assertTrue((FastRouter.route("set a timer for 10 minutes") as Route.Local).intent is LocalIntent.Timer)
        assertTrue((FastRouter.route("remind me in 2 hours to call the bank") as Route.Local).intent is LocalIntent.Timer)
    }
}
