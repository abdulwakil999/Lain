package com.lain.assistant.automation

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import com.lain.assistant.agent.LocalIntent
import com.lain.assistant.agent.TransportAction
import com.lain.assistant.agent.WhenParser
import com.lain.assistant.data.ScheduledTask
import com.lain.assistant.data.TaskAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Executes the intents [com.lain.assistant.agent.FastRouter] resolved locally,
 * and phrases the answer.
 *
 * Two things are load-bearing here. The first is that none of this touches the
 * network: every branch is a platform call, so these commands work in a lift with
 * no signal. The second is the wording — the replies have to sound like Lain
 * rather than like a status readout, because the user cannot tell (and shouldn't
 * care) which path answered them. If the local path sounded robotic, the speed
 * would read as a downgrade.
 *
 * Anything that fails here returns null rather than an error, and the caller
 * falls back to the model. A local shortcut that breaks should cost latency, not
 * capability.
 */
class LocalActions(private val context: Context) {

    private val apps = AppLauncher(context)
    private val phone = PhoneController(context)
    private val device = DeviceController(context)
    private val reminders = RemindersRepository(context)
    private val media = MediaController(context)
    private val scheduler = Scheduler(context)
    private val toggles = QuickToggles(context)

    /** @return the spoken/displayed reply, or null if this couldn't be handled locally after all. */
    suspend fun execute(intent: LocalIntent): String? = withContext(Dispatchers.Default) {
        runCatching {
            when (intent) {
                is LocalIntent.Clock -> clock(intent.wantsDate)
                is LocalIntent.Battery -> battery()
                is LocalIntent.OpenApp -> openApp(intent.appName)
                is LocalIntent.Navigate -> navigate(intent.key)
                is LocalIntent.Timer -> timer(intent.minutes, intent.label)
                is LocalIntent.SettingsPage -> settingsPage(intent.page)
                is LocalIntent.Call -> call(intent.contact)
                is LocalIntent.Volume -> volume(intent.percent, intent.direction)
                is LocalIntent.Torch -> torch(intent.on)
                is LocalIntent.ReadScreen -> readScreen()
                is LocalIntent.ToggleRequest -> toggleRequest(intent.page, intent.what)
                is LocalIntent.Transport -> transport(intent.action)
                is LocalIntent.PlayMusic -> playMusic(intent.query, intent.app)
                is LocalIntent.Schedule -> schedule(intent.phrase, intent.alarm)
                is LocalIntent.ListSchedule -> listSchedule()
                is LocalIntent.CancelSchedule -> cancelSchedule(intent.which)
                is LocalIntent.Dnd -> toggles.setDoNotDisturb(intent.mode).result
                is LocalIntent.Ringer -> toggles.setRingerMode(intent.mode).result
                // Arithmetic was already done by the router; this just phrases it.
                is LocalIntent.Calculate -> "${intent.result.expression} = ${intent.result.pretty()}"
            }
        }.getOrNull()
    }

    // --------------------------------------------------------------- clock

    private fun clock(wantsDate: Boolean): String {
        val now = Date()
        return if (wantsDate) {
            SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(now)
        } else {
            "It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(now).lowercase(Locale.getDefault())
        }
    }

    // ------------------------------------------------------------- battery

    private fun battery(): String? {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return null
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level < 0) return null
        val charging = bm.isCharging
        return when {
            charging -> "$level%, charging."
            level <= 15 -> "$level% — worth plugging in."
            else -> "$level%."
        }
    }

    // ---------------------------------------------------------------- apps

    private fun openApp(name: String): String? = when (val result = apps.openApp(name)) {
        is AutomationResult.Success -> {
            // Prefer the app's real display name over what was said, so "open insta"
            // confirms "Instagram" and the user knows it resolved to the right thing.
            val resolved = apps.resolveLabel(name) ?: name
            "Opening $resolved."
        }
        // Not found is a real answer, and a faster one than the model would give.
        is AutomationResult.Failure -> "No app called \"$name\" is installed."
        is AutomationResult.MissingPermission -> null
    }

    private suspend fun navigate(key: String): String? {
        val service = LainAccessibilityService.instance ?: return null
        when (key) {
            "home" -> service.goHome()
            "back" -> service.goBack()
            "recents" -> service.openRecents()
            "notifications" -> service.openNotifications()
            else -> return null
        }
        return when (key) {
            "home" -> "Home."
            "back" -> "Back."
            "recents" -> "Recents."
            else -> "Notifications."
        }
    }

    private suspend fun readScreen(): String? {
        val service = LainAccessibilityService.instance ?: return null
        val text = service.readScreenText()
        return text.takeIf { it.isNotBlank() }
    }

    // -------------------------------------------------------------- timers

    private suspend fun timer(minutes: Int, label: String): String? {
        val what = label.ifBlank { "Timer" }
        val at = System.currentTimeMillis() + minutes * 60_000L
        return when (reminders.schedule(what, at)) {
            is AutomationResult.Success -> {
                val when_ = if (minutes >= 60 && minutes % 60 == 0) {
                    val h = minutes / 60
                    "$h hour${if (h > 1) "s" else ""}"
                } else {
                    "$minutes minute${if (minutes > 1) "s" else ""}"
                }
                if (label.isBlank()) "Timer set for $when_." else "I'll remind you to $label in $when_."
            }
            // Exact-alarm permission is a real gate; the model can't fix it either, but
            // it phrases the ask better than a raw failure would.
            else -> null
        }
    }

    // ---------------------------------------------------------- scheduling

    /**
     * Sets an alarm or reminder from the raw phrase, with no model involved.
     *
     * The resolved time is always spoken back. That is the safeguard: if "half two"
     * was read as 14:30 when the user meant 02:30, they hear it now rather than
     * discovering it tomorrow.
     */
    private suspend fun schedule(phrase: String, alarm: Boolean): String? {
        val parsed = WhenParser.parse(phrase) ?: return null

        // "call mama every day at 7" is a scheduled *call*, not a note to self.
        //
        // Everything used to become ALARM or REMIND, so the headline case turned into
        // a reminder captioned "call mama" — a notification the user then had to act
        // on themselves, which is precisely the work they asked to be rid of. When a
        // person is named, it becomes a task that rings with a Call button.
        val outward = outwardTarget(parsed.remainder)
        val action = when {
            outward != null -> TaskAction.CALL
            alarm -> TaskAction.ALARM
            else -> TaskAction.REMIND
        }

        // Resolved now rather than at fire time, so an unknown name is corrected while
        // the user is still here.
        if (outward != null && phone.resolveContact(outward) !is PhoneController.ContactMatch.One) {
            return null
        }

        val label = parsed.remainder.ifBlank { if (alarm) "Alarm" else "Reminder" }
        val task = ScheduledTask(
            label = label,
            action = action,
            target = outward.orEmpty(),
            triggerAtMillis = parsed.triggerAtMillis,
            repeat = parsed.repeat
        )
        return when (val outcome = scheduler.add(task)) {
            is SchedulingOutcome.Scheduled -> {
                val when_ = SimpleDateFormat("h:mm a", Locale.getDefault())
                    .format(Date(outcome.task.triggerAtMillis))
                    .lowercase(Locale.getDefault())
                val repeat = if (outcome.task.repeat == com.lain.assistant.data.Repeat.ONCE) ""
                else ", ${outcome.task.repeat.label}"
                val drift = if (outcome.exact) "" else
                    " Android hasn't granted Lain exact alarms, so it could be a few minutes out — " +
                        "allow \"Alarms & reminders\" for Lain in Settings."
                when (outcome.task.action) {
                    TaskAction.CALL ->
                        "Set. I'll ring you at $when_$repeat to call ${outcome.task.target} — one tap and " +
                            "it dials.$drift"
                    TaskAction.ALARM -> "Alarm set for $when_$repeat.$drift"
                    else -> "I'll remind you at $when_$repeat — $label.$drift"
                }
            }
            // A failure here falls back to the model, which can ask the user what
            // they meant rather than leaving them with nothing.
            is SchedulingOutcome.Failed -> null
        }
    }

    /**
     * The person a scheduled phrase is aimed at, if any.
     *
     * Only fires on an explicit verb of contact followed by a name, so "remind me to
     * buy milk" stays a reminder. Anything vaguer is left alone — a wrong guess here
     * schedules a phone call nobody asked for.
     */
    private fun outwardTarget(remainder: String): String? {
        val m = Regex("^(?:call|ring|phone|dial)\\s+(.{2,40})$").find(remainder.trim().lowercase())
            ?: return null
        val who = m.groupValues[1].trim().trim('.', ',')
        // "call it a day", "call back" — speech, not telephony.
        if (who.startsWith("it") || who.startsWith("back") || who.startsWith("me")) return null
        return who.takeIf { it.isNotBlank() }
    }

    private suspend fun listSchedule(): String {
        val all = scheduler.all()
        return if (all.isEmpty()) "Nothing scheduled."
        else "You've got:\n" + all.joinToString("\n") { "- ${it.describe()}" }
    }

    private suspend fun cancelSchedule(which: String): String? =
        when (val outcome = scheduler.cancelMatching(which)) {
            is CancelOutcome.Cancelled -> "Cancelled ${outcome.task.describe()}."
            // More than one match, or none: the model asks rather than guessing which
            // alarm to delete, because that is not undoable.
            is CancelOutcome.Ambiguous -> null
            CancelOutcome.NoMatch -> null
        }

    // ------------------------------------------------------------ settings

    private fun settingsPage(page: String): String? {
        val result = device.openSettingsPage(page)
        return if (result.success) "Opening $page settings." else null
    }

    /**
     * Wi-Fi, Bluetooth and aeroplane mode cannot be toggled by a normal app —
     * Android 10 removed that for everyone who isn't a system app, deliberately.
     * Saying so immediately and landing the user one tap away beats two model calls
     * that arrive at the same wall more slowly.
     */
    private fun toggleRequest(page: String, what: String): String? {
        val result = device.openSettingsPage(page)
        return if (result.success) {
            "Android doesn't let apps flip $what directly any more — I've opened the settings page for you."
        } else {
            null
        }
    }

    // --------------------------------------------------------------- music

    /**
     * Transport controls report what the device actually did.
     *
     * A media key is fire-and-forget — the OS routes it to whoever owns the session,
     * and if nothing does, nothing happens. Checking `isMusicActive` afterwards is
     * what stops Lain saying "paused" into silence when no player was running.
     */
    private suspend fun transport(action: TransportAction): String? {
        val wasPlaying = media.isPlaying()

        val dispatched = when (action) {
            TransportAction.PLAY -> media.play()
            TransportAction.PAUSE -> media.pause()
            TransportAction.TOGGLE -> media.playPause()
            TransportAction.NEXT -> media.next()
            TransportAction.PREVIOUS -> media.previous()
            TransportAction.STOP -> media.stop()
        }
        if (!dispatched) return null

        // Media sessions react asynchronously; give the player a moment before asking
        // whether anything changed.
        kotlinx.coroutines.delay(350)
        val nowPlaying = media.isPlaying()

        return when (action) {
            TransportAction.PAUSE, TransportAction.STOP ->
                if (nowPlaying) "Sent the pause, but something's still playing." else "Paused."

            TransportAction.PLAY, TransportAction.TOGGLE -> when {
                nowPlaying -> "Playing."
                // Nothing was playing and nothing started: there is no session to
                // resume, which is a different problem from a failed key press.
                !wasPlaying -> "Nothing's queued up to resume — say what you want and I'll start it."
                else -> "Paused."
            }

            TransportAction.NEXT -> if (nowPlaying) "Skipped." else "Sent the skip, but nothing's playing."
            TransportAction.PREVIOUS -> if (nowPlaying) "Went back." else "Sent it, but nothing's playing."
        }
    }

    /**
     * Starts playback via the platform's play-from-search intent, which is the hook
     * Spotify and the other players expose precisely for this.
     */
    private suspend fun playMusic(query: String, app: String?): String? {
        val target = media.packageFor(app)

        // No search terms: resume whatever was last playing, or open the named player.
        if (query.isBlank()) {
            if (media.play()) {
                kotlinx.coroutines.delay(350)
                if (media.isPlaying()) return "Playing."
            }
            if (target != null && media.openPlayer(target)) {
                return "Opened ${app?.replaceFirstChar { it.uppercase() } ?: "your music app"} — tell me what to play."
            }
            if (media.playFromSearch("", target)) return "Starting something."
            return null
        }

        if (!media.playFromSearch(query, target)) return null

        // The intent was accepted; whether audio starts is up to the player and how
        // well it matched. Report what was asked for rather than claiming a result
        // that hasn't been verified.
        kotlinx.coroutines.delay(900)
        val where = app?.replaceFirstChar { it.uppercase() }
        return if (media.isPlaying()) {
            if (where != null) "Playing $query on $where." else "Playing $query."
        } else {
            if (where != null) "Asked $where for $query." else "Asked your music app for $query."
        }
    }

    // --------------------------------------------------------------- phone

    private suspend fun call(contact: String): String? =
        when (val match = phone.resolveContact(contact)) {
            is PhoneController.ContactMatch.One -> {
                when (val placed = phone.placeCall(match.contact.number)) {
                    // Names who was actually matched, so "call moyo" reaching MoyOma
                    // is visible before the phone starts ringing rather than after.
                    is AutomationResult.Success -> "Calling ${match.contact.name}."
                    is AutomationResult.Failure -> placed.reason
                    is AutomationResult.MissingPermission -> null
                }
            }

            // Two different people, similarly named. This is the one place where being
            // fast must not mean being wrong — a call cannot be taken back.
            is PhoneController.ContactMatch.Several ->
                "There's more than one match for \"$contact\": " +
                    match.contacts.joinToString("; ") { it.name } + ". Which one?"

            PhoneController.ContactMatch.None -> null
            PhoneController.ContactMatch.NoPermission -> null
        }

    // -------------------------------------------------------------- volume

    private fun volume(percent: Int?, direction: Int): String? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return null

        if (percent != null) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, percent * max / 100, 0)
            return when (percent) {
                0 -> "Muted."
                100 -> "Volume maxed."
                else -> "Volume at $percent%."
            }
        }

        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = (max / 7).coerceAtLeast(1)
        val next = (current + direction * step).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        return "Volume ${if (direction > 0) "up" else "down"} — ${next * 100 / max}%."
    }

    // --------------------------------------------------------------- torch

    private fun torch(on: Boolean): String? {
        val cm = context.getSystemService(CameraManager::class.java) ?: return null
        // The back camera is the one with a flash; find it rather than assuming "0".
        val id = cm.cameraIdList.firstOrNull { camera ->
            cm.getCameraCharacteristics(camera)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return null
        cm.setTorchMode(id, on)
        return if (on) "Torch on." else "Torch off."
    }
}
