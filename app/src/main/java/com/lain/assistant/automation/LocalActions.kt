package com.lain.assistant.automation

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import com.lain.assistant.agent.IdentityQuestion
import com.lain.assistant.agent.DeveloperGate
import com.lain.assistant.agent.Replies
import com.lain.assistant.agent.SpokenSegments
import com.lain.assistant.agent.SmallTalkKind
import kotlinx.coroutines.flow.first
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
import kotlin.random.Random

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

    private val prefs = com.lain.assistant.data.UserPreferencesRepository(context)
    private val apps = AppLauncher(context)
    private val phone = PhoneController(context)
    private val device = DeviceController(context)
    private val reminders = RemindersRepository(context)
    private val media = MediaController(context)
    private val scheduler = Scheduler(context)
    private val toggles = QuickToggles(context)
    private val systemToggles = SystemToggles(context)
    private val quran = QuranPlayer(context)
    private val location = LocationReader(context)
    private val searchFlow = SearchFlow(context)

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
                is LocalIntent.Volume -> volume(intent.percent, intent.direction, intent.stream)
                is LocalIntent.Brightness -> brightness(intent.percent, intent.direction, intent.auto)
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
                is LocalIntent.SystemToggle -> systemToggle(intent.what, intent.on)
                is LocalIntent.SearchIn -> searchFlow.search(intent.app, intent.query).result
                is LocalIntent.CloseApp -> closeApp(intent.appName)
                is LocalIntent.ClearRecents -> clearRecents()
                is LocalIntent.SmallTalk -> smallTalk(intent.kind)
                is LocalIntent.Identity -> identity(intent.question)
                is LocalIntent.DeveloperClaim -> developerClaim()
                is LocalIntent.DeveloperAnswer -> developerAnswer(intent.text)
                is LocalIntent.Recite -> recite(intent)
                is LocalIntent.WhereAmI -> whereAmI()
                is LocalIntent.LockScreen -> lockScreen()
                is LocalIntent.Power -> powerMenu(intent.restart)
                is LocalIntent.CloseSelf -> "Closing."
                // Arithmetic was already done by the router; this just phrases it.
                is LocalIntent.Calculate -> "${intent.result.expression} = ${intent.result.pretty()}"
            }
        }.getOrNull()
    }

    // --------------------------------------------------------------- clock

    private fun clock(wantsDate: Boolean): String {
        val now = Date()
        return sass(
            if (wantsDate) {
                SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(now)
            } else {
                "It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
                    .lowercase(Locale.getDefault())
            }
        )
    }

    // ------------------------------------------------------------- battery

    private fun battery(): String? {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return null
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level < 0) return null
        val charging = bm.isCharging
        return sass(
            when {
                charging -> "$level%, charging."
                level <= 15 -> "$level% — worth plugging in."
                else -> "$level%."
            }
        )
    }

    // ---------------------------------------------------------------- apps

    private fun openApp(name: String): String? = when (val result = apps.openApp(name)) {
        is AutomationResult.Success -> {
            // Prefer the app's real display name over what was said, so "open insta"
            // confirms "Instagram" and the user knows it resolved to the right thing.
            val resolved = apps.resolveLabel(name) ?: name
            sass("Opening $resolved.")
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

    // ------------------------------------------------------------ recitation

    private suspend fun recite(intent: LocalIntent.Recite): String? {
        if (intent.stop) {
            return if (quran.stop()) "Stopped." else "Nothing was playing."
        }
        if (intent.surah.isBlank()) return null
        val result = quran.recite(intent.surah, intent.reciter)
        return result.error ?: result.result
    }

    // -------------------------------------------------------------- location

    /**
     * Null when the permission isn't granted, so the turn falls through to the model
     * — which can explain what to grant. Answering "I don't have permission" from
     * here would be terser but leaves the user without the next step.
     */
    private suspend fun whereAmI(): String? = location.describe()

    // ------------------------------------------------------------ small talk

    /**
     * A handful of fixed exchanges, answered on the device.
     *
     * Varied deliberately. A greeting that returns the same six characters every
     * time is what makes an assistant feel like a vending machine, and this is the
     * one place a canned answer is honest — there is nothing to work out, only
     * something to say back.
     */
    private suspend fun smallTalk(kind: SmallTalkKind): String {
        val name = prefs.userProfile.first()?.nickname?.takeIf { it.isNotBlank() }
        val options = when (kind) {
            SmallTalkKind.GREETING -> name?.let { Replies.greetingsWithName(it) } ?: Replies.greetings
            SmallTalkKind.THANKS -> Replies.thanks
            SmallTalkKind.HOW_ARE_YOU -> Replies.howAreYou
            SmallTalkKind.GOODBYE -> name?.let { Replies.goodbyesWithName(it) } ?: Replies.goodbyes
            SmallTalkKind.AFFIRMATION -> Replies.affirmations
        }
        return say(options)
    }

    /**
     * Picks a line, records it, and hands the segmentation to the speaker.
     *
     * The segmentation is the part that matters for the Spanish: a mixed line has to
     * be spoken as two languages, and only the line itself knows where the split is.
     */
    private fun say(options: List<Replies.Spoken>): String {
        val chosen = Replies.pick(options, lastSpokenLine)
        lastSpokenLine = chosen.text
        SpokenSegments.remember(chosen)
        return chosen.text
    }

    /**
     * Occasionally puts an insult in front of an answer she has already produced.
     *
     * Only wrapped around results that are known to have worked, and only for the
     * requests a person could have done themselves in two taps — a torch, a volume
     * step, opening an app. Never around a failure or a refusal: being told you are
     * a fool by something that then didn't do the thing is just rude, and the brief
     * was sassy, not rude.
     *
     * Fires on roughly [Replies.SASS_CHANCE] percent of eligible answers, and never
     * replaces the answer, only precedes it.
     */
    private fun sass(reply: String): String {
        if (Random.nextInt(100) >= Replies.SASS_CHANCE) return reply
        val prefix = Replies.pick(Replies.sassPrefixes, lastSassLine)
        lastSassLine = prefix.text
        val combined = Replies.prefixed(prefix, reply)
        SpokenSegments.remember(combined)
        return combined.text
    }

    /** The last sass line used, so two in a row are never the same jab. */
    private var lastSassLine: String? = null

    /** The last local line used, so the next pick avoids repeating it. */
    private var lastSpokenLine: String? = null

    // -------------------------------------------------------------- identity

    /**
     * Answers what Lain already knows.
     *
     * The profile is a row in the app's own database and her name is a constant.
     * These went through a language model, which meant a network round trip to be
     * told something already on the device — and on a free model, a numbered plan
     * about how to say a two-word name.
     */
    private suspend fun identity(question: IdentityQuestion): String? {
        val profile = prefs.userProfile.first()
        return when (question) {
            IdentityQuestion.USER_NAME -> {
                val nickname = profile?.nickname?.takeIf { it.isNotBlank() }
                val real = profile?.name?.takeIf { it.isNotBlank() }
                val display = when {
                    nickname != null && real != null && !nickname.equals(real, ignoreCase = true) ->
                        "$real — you go by $nickname"
                    else -> nickname ?: real
                }
                if (display == null) say(Replies.unknownUserName) else say(Replies.userName(display))
            }

            IdentityQuestion.USER_AGE ->
                profile?.age?.takeIf { it > 0 }?.let { say(Replies.userAge(it)) }

            IdentityQuestion.LAIN_NAME -> say(Replies.lainName)
            IdentityQuestion.APP_NAME -> say(Replies.appName)
            IdentityQuestion.NECIO -> say(Replies.necio)
            IdentityQuestion.DEVELOPER -> say(Replies.developer)
            IdentityQuestion.CAPABILITIES -> say(Replies.capabilities)
        }
    }

    // ------------------------------------------------------------- developer

    /**
     * Answers a claim to be the developer with a question, never with belief.
     *
     * The claim itself is six words anyone can type, so it earns nothing on its own.
     * Arming the gate here means the very next message is read as the answer — see
     * [DeveloperGate] — which is why the router checks it before anything else.
     */
    private fun developerClaim(): String {
        DeveloperGate.arm()
        return say(Replies.developerChallenge)
    }

    /**
     * The reply to the challenge, and the only place the answer is checked.
     *
     * Right: remembered, so he is not asked again on the next launch. Wrong: said
     * plainly, in Spanish, and not remembered at all — a failed guess is not worth
     * storing, and someone who mistypes deserves to be able to simply say it again.
     */
    private suspend fun developerAnswer(text: String): String {
        val correct = DeveloperGate.answer(text)
        if (correct) prefs.setDeveloperKnown(true)
        return say(if (correct) Replies.developerAccepted else Replies.developerRejected)
    }

    // ----------------------------------------------------------- power state

    private fun lockScreen(): String? {
        val service = LainAccessibilityService.instance ?: return null
        return if (service.lockScreen()) sass("Locked.")
        else "Locking the screen needs Android 9 or newer — this phone won't let an app do it."
    }

    /**
     * Puts the system power menu up rather than restarting anything.
     *
     * No API exists for an app to restart or power off a phone, and that is correct:
     * it would end whatever the user was doing, mid-sentence, with no way back. So
     * this raises the real menu and the press stays theirs. Said plainly, because
     * "restarting now" followed by nothing happening would be worse.
     */
    private fun powerMenu(restart: Boolean): String? {
        val service = LainAccessibilityService.instance ?: return null
        val word = if (restart) "Restart" else "Power off"
        return if (service.showPowerMenu()) {
            "Power menu's up — tap $word. Android doesn't let any app do that one on its own, " +
                "and I'd rather not be the reason your phone goes down mid-sentence anyway."
        } else {
            "Couldn't raise the power menu. Hold the power button."
        }
    }

    // ------------------------------------------------------- system toggles

    /**
     * Switches a radio and says what actually happened.
     *
     * Returns null — falling through to the model — only when the phrase names
     * nothing recognisable. A refusal or a failed tap is reported here, because the
     * model cannot do any better and a round trip to hear the same answer is a round
     * trip wasted.
     */
    private suspend fun systemToggle(what: String, on: Boolean): String? {
        val toggle = SystemToggles.Toggle.from(what) ?: return null
        val outcome = systemToggles.set(toggle, on)
        // A refusal explains a wall the user didn't build; it doesn't get mocked.
        return outcome.error ?: outcome.result?.let { sass(it) }
    }

    private suspend fun closeApp(appName: String): String? {
        val service = LainAccessibilityService.instance
        val name = appName.trim()

        // Named app that isn't on screen: stop its background work, no service needed.
        if (name.isNotBlank()) {
            val foreground = service?.foregroundApp()
            val isForeground = foreground?.second?.contains(name, ignoreCase = true) == true ||
                apps.resolvePackage(name) == foreground?.first
            if (!isForeground) {
                return when (val stopped = apps.killBackgroundProcess(name)) {
                    is AutomationResult.Success -> "Stopped $name in the background."
                    is AutomationResult.Failure -> stopped.reason
                    is AutomationResult.MissingPermission -> null
                }
            }
        }

        // On screen: Recents is the only route Android permits.
        if (service == null) return null
        val before = service.foregroundApp()
        service.closeCurrentApp()
        val after = LainAccessibilityService.instance?.foregroundApp()
        return if (after?.first != before?.first) "Closed ${before?.second ?: name}."
        else "Swiped in Recents, but ${before?.second ?: name} is still up."
    }

    private suspend fun clearRecents(): String? {
        val service = LainAccessibilityService.instance ?: return null
        return if (service.clearRecents()) "Cleared the recents list."
        else "Opened recents but couldn't find a clear-all control on this phone."
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
        return if (result.success) sass("Opening $page settings.") else null
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

    private fun volume(percent: Int?, direction: Int, stream: String): String? {
        val am = context.getSystemService(AudioManager::class.java) ?: return null
        val (type, label) = when (stream) {
            "ring" -> AudioManager.STREAM_RING to "Ringer"
            "notification" -> AudioManager.STREAM_NOTIFICATION to "Notification"
            "alarm" -> AudioManager.STREAM_ALARM to "Alarm"
            "call" -> AudioManager.STREAM_VOICE_CALL to "Call"
            else -> AudioManager.STREAM_MUSIC to "Volume"
        }
        val max = am.getStreamMaxVolume(type)
        if (max <= 0) return null

        val target = if (percent != null) {
            percent * max / 100
        } else {
            val step = (max / 7).coerceAtLeast(1)
            (am.getStreamVolume(type) + direction * step).coerceIn(0, max)
        }
        am.setStreamVolume(type, target, 0)

        // Read it back. Do Not Disturb refuses ringer and notification changes
        // without erroring, and "ringer at 60%" over a phone that is still silent is
        // the exact shape of lie this app is built not to tell.
        val actual = am.getStreamVolume(type)
        if (actual != target) {
            return "$label wouldn't move — still ${actual * 100 / max}%. " +
                "Do Not Disturb holds it there."
        }

        val reached = actual * 100 / max
        return sass(
            when {
                reached == 0 -> if (label == "Volume") "Muted." else "$label off."
                reached == 100 -> "$label maxed."
                percent != null -> "$label at $reached%."
                else -> "$label ${if (direction > 0) "up" else "down"} — $reached%."
            }
        )
    }

    // ---------------------------------------------------------- brightness

    /**
     * Screen brightness, answered on the device.
     *
     * Returns null only when the value can't be read to step from — anything else,
     * including the missing permission, is reported here rather than handed to the
     * model, which cannot do better and would take a round trip to say the same
     * thing. [ScreenBrightness] opens the grant screen itself when it needs to.
     */
    private fun brightness(percent: Int?, direction: Int, auto: Boolean): String? {
        val screen = ScreenBrightness(context)
        if (auto) return screen.setAutomatic().let { it.error ?: it.result }

        val target = percent ?: run {
            val current = screen.current() ?: return null
            (current + direction * 20).coerceIn(0, 100)
        }
        val outcome = screen.set(target)
        return outcome.error ?: outcome.result?.let { sass(it) }
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
        return sass(if (on) "Torch on." else "Torch off.")
    }
}
