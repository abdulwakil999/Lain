package com.lain.assistant.tools

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.lain.assistant.automation.AppLauncher
import com.lain.assistant.automation.AutomationResult
import com.lain.assistant.automation.CameraController
import com.lain.assistant.automation.DeviceController
import com.lain.assistant.automation.AccessibilityMonitor
import com.lain.assistant.automation.LainAccessibilityService
import com.lain.assistant.automation.MessageFlow
import com.lain.assistant.automation.NotesRepository
import com.lain.assistant.automation.PhoneController
import com.lain.assistant.automation.QuickToggles
import com.lain.assistant.automation.LocationReader
import com.lain.assistant.automation.QuranPlayer
import com.lain.assistant.automation.SearchFlow
import com.lain.assistant.automation.SimPreference
import com.lain.assistant.automation.SystemToggles
import com.lain.assistant.automation.RemindersRepository
import com.lain.assistant.automation.CancelOutcome
import com.lain.assistant.automation.Scheduler
import com.lain.assistant.automation.SchedulingOutcome
import com.lain.assistant.automation.VoiceInputController
import com.lain.assistant.agent.WhenParser
import com.lain.assistant.data.MemoryCategory
import com.lain.assistant.data.Repeat
import com.lain.assistant.data.ScheduledTask
import com.lain.assistant.data.TaskAction
import com.lain.assistant.data.MemoryStore
import com.lain.assistant.network.ToolCall
import com.lain.assistant.network.WebResearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream

/**
 * Executes a [ToolCall] and returns a [ToolResult] the agent can reason about.
 *
 * The important property here is that success is never assumed. Each branch
 * reports what actually happened — attempted, succeeded, or failed and why — so
 * the model cannot mistake "I sent the intent" for "the thing is done".
 */
class ToolDispatcher(context: Context) {

    companion object {
        /** Ceiling on any single textual result, so one dense screen can't blow the context window. */
        private const val MAX_RESULT_CHARS = 3500

        /**
         * How long to wait between attempts to find an element on screen.
         *
         * Three tries over about a second: enough to cover a screen transition and a
         * list finishing its first layout, short enough that a genuinely absent
         * element is reported while the user is still expecting an answer.
         */
        private val FIND_BACKOFF_MS = listOf(150L, 350L, 700L)
    }

    private val appContext = context.applicationContext
    private val phone = PhoneController(appContext)
    private val apps = AppLauncher(appContext)
    private val notes = NotesRepository(appContext)
    private val reminders = RemindersRepository(appContext)
    private val camera = CameraController(appContext)
    private val voice = VoiceInputController(appContext)
    private val device = DeviceController(appContext)
    private val scheduler = Scheduler(appContext)
    private val toggles = QuickToggles(appContext)
    private val sims = SimPreference(appContext)
    private val systemToggles = SystemToggles(appContext)
    private val searchFlow = SearchFlow(appContext)
    private val quran = QuranPlayer(appContext)
    private val location = LocationReader(appContext)
    private val messaging = MessageFlow(appContext)
    private val web = WebResearch()
    private val memory = MemoryStore(appContext)
    private val conversations = com.lain.assistant.data.ConversationStore(appContext)
    private val brightness = com.lain.assistant.automation.ScreenBrightness(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    /** Set by the engine so memories can record where they were learned. */
    var currentConversationId: String? = null

    /**
     * Runs off the main thread — walking an accessibility tree or fetching a page on
     * the UI thread shows up as jank or an ANR.
     */
    suspend fun execute(call: ToolCall): ToolResult = withContext(Dispatchers.Default) {
        val args = runCatching { json.parseToJsonElement(call.argumentsJson) as JsonObject }.getOrNull()
            ?: return@withContext ToolResult.fail(FailureKind.INVALID_INPUT, "Couldn't parse the arguments for ${call.name}")

        // A model that fails to recognise its own success calls the tool again with
        // the same arguments. For anything that leaves the phone, the second call is
        // a second message to a real person.
        RecentSideEffects.duplicate(call.name, call.argumentsJson)?.let { return@withContext it }

        val result = try {
            dispatch(call.name, args)
        } catch (t: Throwable) {
            ToolResult.fail(FailureKind.TOOL_FAILURE, "${call.name} threw an exception", t.message)
        }
        if (result.success) RecentSideEffects.record(call.name, call.argumentsJson)

        // Clip prose, never images or structured payloads.
        if (result.result.length <= MAX_RESULT_CHARS) result
        else result.copy(result = result.result.take(MAX_RESULT_CHARS) + "\n… (truncated)")
    }

    private suspend fun dispatch(name: String, args: JsonObject): ToolResult = when (name) {

        // ---------------------------------------------------------- apps
        "open_app" -> {
            val target = args.str("app_name")
            val launch = apps.openApp(target)
            if (launch is AutomationResult.Success) {
                val service = LainAccessibilityService.instance
                // Wait for the app to actually finish drawing rather than sleeping for
                // the worst case. Most launches settle in a few hundred milliseconds;
                // the old flat 1.6s was paid on every single one.
                service?.awaitSettle(maxWait = 3500L)
                val foreground = service?.foregroundApp()
                val screen = service?.readScreenCompact()
                // Confirm it actually came to the front rather than trusting the intent.
                if (foreground != null) {
                    ToolResult.ok(
                        "Opened ${foreground.second}. It is now in the foreground.",
                        data = screen?.let { mapOf("screen" to it) } ?: emptyMap()
                    )
                } else {
                    ToolResult.ok(
                        "Launched $target. Couldn't verify the foreground app (Accessibility Service off), so confirm before acting.",
                        data = screen?.let { mapOf("screen" to it) } ?: emptyMap()
                    )
                }
            } else {
                launch.asFailure(FailureKind.APP_UNAVAILABLE)
            }
        }

        "close_app" -> closeApp(args.str("app_name"))

        "open_url" -> phone.openUrl(args.str("url")).asResult(FailureKind.APP_UNAVAILABLE)

        "open_settings_page" -> device.openSettingsPage(args.str("page"))

        "current_app" -> withService("current_app") { service ->
            val fg = service.foregroundApp()
            if (fg == null) ToolResult.ok("Lain's own screen is in the foreground — no other app is open.")
            else ToolResult.ok("Foreground app: ${fg.second} (${fg.first})")
        }

        // ---------------------------------------------------- perception
        "read_screen" -> withService("read_screen") { ToolResult.ok(it.readScreenText()) }

        "look_at_screen" -> withService("look_at_screen") { service ->
            val shot = service.captureScreenshotBase64()
            if (shot == null) {
                ToolResult.fail(
                    FailureKind.CAPABILITY_UNAVAILABLE,
                    "Screenshot unavailable — this needs Android 11 or newer, or the app is blocking capture. Use read_screen instead."
                )
            } else {
                ToolResult.ok("Screen captured.", image = shot)
            }
        }

        // ------------------------------------------------------- control
        //
        // Every branch here used to sleep for a fixed period and then ship the FULL
        // screen dump back through the model. On a ten-step task that's ten dense
        // screens and seven seconds of pure sleeping. They now wait for the UI to
        // actually settle and return the compact, interactive-only view.
        "tap_text" -> withService("tap_text") { service ->
            val label = args.str("text")
            if (awaitElement(label, service) == null) {
                ToolResult.fail(
                    FailureKind.INVALID_INPUT,
                    "No element labelled \"$label\" on screen. Pick a label from the listing below.",
                    data = mapOf("screen" to service.readScreenCompact())
                )
            } else if (service.tapByText(label)) {
                service.awaitSettle()
                ToolResult.ok("Tapped \"$label\".", data = mapOf("screen" to service.readScreenCompact()))
            } else {
                ToolResult.fail(FailureKind.TOOL_FAILURE, "The tap on \"$label\" was rejected by the system.")
            }
        }

        "tap_screen" -> withService("tap_screen") { service ->
            val x = args.num("x"); val y = args.num("y")
            val tapped = service.tap(x, y)
            if (tapped) {
                service.awaitSettle()
                ToolResult.ok("Tapped (${x.toInt()}, ${y.toInt()}).", data = mapOf("screen" to service.readScreenCompact()))
            } else {
                ToolResult.fail(FailureKind.TOOL_FAILURE, "The tap gesture was rejected.")
            }
        }

        "type_text" -> withService("type_text") { service ->
            val text = args.str("text")
            if (service.typeText(text)) {
                // Typing redraws the field; a short settle is enough, and submitting
                // in the same call saves an entire model round trip.
                service.awaitSettle(maxWait = 1200L, quietPeriod = 150L)
                if (args.bool("submit")) {
                    val submitted = service.pressImeAction() ||
                        listOf("Send", "Search", "Go", "Done").any { service.tapByText(it) }
                    service.awaitSettle()
                    if (submitted) {
                        ToolResult.ok("Typed \"$text\" and submitted it.", data = mapOf("screen" to service.readScreenCompact()))
                    } else {
                        ToolResult.ok(
                            "Typed \"$text\", but no submit control responded — tap the send/search button yourself.",
                            data = mapOf("screen" to service.readScreenCompact())
                        )
                    }
                } else {
                    ToolResult.ok("Typed \"$text\".", data = mapOf("screen" to service.readScreenCompact()))
                }
            } else {
                ToolResult.fail(
                    FailureKind.TOOL_FAILURE,
                    "No editable field had focus, so nothing was typed. Tap the [INPUT] element first.",
                    data = mapOf("screen" to service.readScreenCompact())
                )
            }
        }

        "press_key" -> withService("press_key") { service ->
            val key = args.str("key").lowercase()
            val label = when (key) {
                "back" -> { service.goBack(); "Pressed back." }
                "home" -> { service.goHome(); "Went home." }
                "recents" -> { service.openRecents(); "Opened recents." }
                "notifications" -> { service.openNotifications(); "Opened notifications." }
                "enter" -> {
                    // The keyboard's own action key first — it needs no label and works
                    // on screens where the submit control is an unlabelled icon.
                    val submitted = service.pressImeAction() ||
                        listOf("Search", "Go", "Send", "Done", "Enter").any { service.tapByText(it) }
                    if (!submitted) {
                        return@withService ToolResult.fail(
                            FailureKind.INVALID_INPUT,
                            "Nothing on this screen accepted a submit.",
                            data = mapOf("screen" to service.readScreenCompact())
                        )
                    }
                    "Submitted."
                }
                else -> return@withService ToolResult.fail(
                    FailureKind.INVALID_INPUT, "Unknown key \"$key\". Use back, home, recents, notifications or enter."
                )
            }
            service.awaitSettle()
            ToolResult.ok(label, data = mapOf("screen" to service.readScreenCompact()))
        }

        "swipe_screen" -> withService("swipe_screen") { service ->
            service.swipe(args.num("x1"), args.num("y1"), args.num("x2"), args.num("y2"))
            service.awaitSettle()
            ToolResult.ok("Swiped.", data = mapOf("screen" to service.readScreenCompact()))
        }

        "wait" -> {
            val service = LainAccessibilityService.instance
            val cap = (args.num("seconds").coerceIn(0.5f, 5f) * 1000).toLong()
            if (service != null) {
                // Returns as soon as the screen holds still — the requested duration is
                // a ceiling, not a mandatory sleep.
                val settled = service.awaitSettle(maxWait = cap)
                ToolResult.ok(
                    if (settled) "Screen has settled." else "Screen is still changing after ${cap / 1000f}s.",
                    data = mapOf("screen" to service.readScreenCompact())
                )
            } else {
                delay(cap)
                ToolResult.ok("Waited ${cap / 1000f}s.")
            }
        }

        // ------------------------------------------------ communication
        // The whole "text someone" job in one call. See MessageFlow for why.
        "message_contact" -> messaging.send(
            recipient = args.str("contact").ifBlank { args.str("phone_number") },
            message = args.str("message"),
            channel = when (args.str("app").lowercase()) {
                "whatsapp" -> MessageFlow.Channel.WHATSAPP
                "sms", "text" -> MessageFlow.Channel.SMS
                else -> MessageFlow.Channel.AUTO
            }
        )

        "send_sms" -> phone.sendSms(args.str("phone_number"), args.str("message")).asResult(FailureKind.PERMISSION)
        "make_call" -> {
            val outcome = phone.placeCall(args.str("phone_number")).asResult(FailureKind.PERMISSION)
            // Attach the *real* Accessibility state. Left to infer it, a model that
            // hit a dialler it couldn't drive announced "the Accessibility Service is
            // off" while it was connected the whole time — a confident, wrong
            // explanation for a real problem, which is worse than no explanation.
            AccessibilityMonitor.reconcile(appContext)
            val screenControl = if (AccessibilityMonitor.isUsable) {
                "Accessibility is connected, so you can read_screen and tap the call button yourself."
            } else {
                "Accessibility is not connected (${AccessibilityMonitor.state.value}), so you cannot tap the " +
                    "screen — ask the user to tap it."
            }
            if (outcome.success) outcome
            else outcome.copy(error = listOfNotNull(outcome.error, screenControl).joinToString(" "))
        }
        "lookup_contact" -> phone.lookupContact(args.str("name")).asResult(FailureKind.PERMISSION)
        "send_whatsapp_message" ->
            phone.openWhatsAppChat(args.str("phone_number"), args.str("message")).asResult(FailureKind.APP_UNAVAILABLE)
        "open_contacts" -> device.openContacts()

        // ------------------------------------------------- web research
        "web_search" -> web.search(args.str("query"))
        "fetch_page" -> web.fetchPage(args.str("url"))

        // ------------------------------------------------------ device
        "device_status" -> device.deviceStatus()
        "set_volume" -> device.setVolume(args.num("percent").toInt(), args.str("stream"))
        "set_brightness" -> if (args.bool("auto")) brightness.setAutomatic()
        else brightness.set(args.num("percent").toInt())
        "clipboard" -> when (args.str("action").lowercase()) {
            "write" -> device.writeClipboard(args.str("text"))
            else -> device.readClipboard()
        }

        // ----------------------------------------------- notifications
        "read_notifications" -> {
            val items = com.lain.assistant.automation.LainNotificationListener.current(appContext)
            when {
                items == null -> ToolResult.fail(
                    FailureKind.PERMISSION,
                    "Notification access isn't granted. Android only allows it from Settings > Notifications > " +
                        "Device & app notifications > Lain — tell the user to switch it on there; it can't be done from here."
                )
                items.isEmpty() -> ToolResult.ok("The notification shade is empty.")
                else -> ToolResult.ok(
                    items.joinToString("\n") { n ->
                        val body = listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(" — ")
                        "${n.app}: $body"
                    }
                )
            }
        }

        // ------------------------------------------------------- files
        "list_files" -> device.listFiles(args.str("path"))
        "delete_file" -> device.deleteFile(args.str("path"))
        "make_folder" -> device.makeFolder(args.str("path"))
        "find_files" -> device.findFiles(args.str("query"))
        "read_file" -> device.readFile(args.str("path"))
        "write_file" -> device.writeFile(args.str("path"), args.str("content"), args.bool("append"))
        "rename_file" -> device.renameFile(args.str("from"), args.str("to"))

        // -------------------------------------------------- scheduling
        "set_reminder" -> reminders.schedule(
            text = args.str("text"),
            triggerAtMillis = System.currentTimeMillis() + (args.num("minutes_from_now") * 60_000).toLong()
        ).asResult(FailureKind.PERMISSION)

        "schedule_task" -> scheduleTask(args)
        "list_scheduled_tasks" -> listScheduled()
        "cancel_scheduled_task" -> cancelScheduled(args.str("which"))

        // ---------------------------------------------- device toggles
        "set_do_not_disturb" -> toggles.setDoNotDisturb(args.str("mode"))
        "set_ringer_mode" -> toggles.setRingerMode(args.str("mode"))
        "open_quick_toggle" -> toggles.openPanel(args.str("what"))

        // --------------------------------------------- one-call composites
        "search_in_app" -> searchFlow.search(args.str("app_name"), args.str("query"))

        "set_system_toggle" -> {
            val which = SystemToggles.Toggle.from(args.str("what"))
            if (which == null) {
                ToolResult.fail(
                    FailureKind.INVALID_INPUT,
                    "Don't know a toggle called \"${args.str("what")}\". Try wifi, bluetooth, mobile data, " +
                        "hotspot, location, aeroplane mode, torch or auto-rotate."
                )
            } else {
                systemToggles.set(which, on = args.bool("on"))
            }
        }

        "recite_quran" -> {
            val surah = args.str("surah")
            if (surah.isBlank()) {
                if (quran.stop()) ToolResult.ok("Stopped the recitation.")
                else ToolResult.fail(FailureKind.INVALID_INPUT, "Which surah?")
            } else {
                quran.recite(surah, args.str("reciter"))
            }
        }

        "where_am_i" -> {
            val described = location.describe()
            when {
                described != null -> ToolResult.ok(described)
                !location.hasPermission() -> ToolResult.fail(
                    FailureKind.PERMISSION,
                    "Location permission hasn't been granted. Ask the user to allow it — Lain only reads " +
                        "the last known area and never sends it anywhere."
                )
                else -> ToolResult.fail(FailureKind.TOOL_FAILURE, "Couldn't read a location.")
            }
        }

        "clear_recent_apps" -> withService("clear_recent_apps") { service ->
            if (service.clearRecents()) ToolResult.ok("Cleared the recents list.")
            else ToolResult.fail(
                FailureKind.TOOL_FAILURE,
                "Opened recents but couldn't find a clear-all control — this phone may not have one."
            )
        }

        "set_preferred_sim" -> setPreferredSim(args.str("which"))

        "write_note" -> notes.addNote(args.str("text")).let { ToolResult.ok("Saved note: \"${it.text}\"") }
        "list_notes" -> notes.notes.first()
            .joinToString("\n") { "- ${it.text}" }
            .ifBlank { "No notes yet." }
            .let { ToolResult.ok(it) }

        "take_photo" -> camera.capturePhoto(args.bool("use_front_camera")).fold(
            onSuccess = { ToolResult.ok("Photo captured.", image = it.toJpegBase64()) },
            onFailure = { ToolResult.fail(FailureKind.PERMISSION, "Camera capture failed", it.message) }
        )

        "listen_microphone" -> voice.listenOnce().fold(
            onSuccess = { ToolResult.ok("Heard: \"$it\"") },
            onFailure = { ToolResult.fail(FailureKind.TOOL_FAILURE, "Didn't catch anything", it.message) }
        )

        // ------------------------------------------------------ memory
        "remember" -> {
            val subject = args.str("key").ifBlank { args.str("subject") }
            val fact = args.str("value").ifBlank { args.str("fact") }
            if (subject.isBlank() || fact.isBlank()) {
                ToolResult.fail(FailureKind.INVALID_INPUT, "remember needs both a key and a value.")
            } else {
                val saved = memory.remember(
                    subject = subject,
                    fact = fact,
                    category = MemoryCategory.parse(args.str("category")),
                    importance = args.num("importance").toInt().takeIf { it in 1..5 } ?: 3,
                    sourceConversationId = currentConversationId
                )
                ToolResult.ok("Remembered — ${saved.subject}: ${saved.fact}")
            }
        }

        "edit_memory" -> {
            val updated = memory.edit(
                id = args.str("id"),
                fact = args.str("fact").takeIf { it.isNotBlank() },
                importance = args.num("importance").toInt().takeIf { it in 1..5 }
            )
            if (updated == null) {
                ToolResult.fail(
                    FailureKind.INVALID_INPUT,
                    "No memory with that id — call recall first to get the right one."
                )
            } else {
                ToolResult.ok("Updated — ${updated.subject}: ${updated.fact}")
            }
        }

        "forget" -> {
            val removed = memory.forget(args.str("key"))
            if (removed > 0) ToolResult.ok("Forgot $removed memory item(s) matching \"${args.str("key")}\".")
            else ToolResult.ok("Nothing stored matched \"${args.str("key")}\".")
        }

        "recall" -> recall(args.str("query"))

        else -> ToolResult.fail(FailureKind.INVALID_INPUT, "Unknown tool: $name")
    }

    /**
     * Looks for a labelled element, giving the screen a few chances to produce it.
     *
     * A tap that misses is almost never a tap at the wrong place — it is a tap a
     * fraction of a second too early, at a screen still drawing itself after the
     * previous action. The old code asked once, got nothing, and reported the element
     * as absent, which sent the model off to re-plan a task that was about to work.
     * On a free model that costs a round trip and often the whole task.
     *
     * Backs off between attempts rather than spinning: the delays are roughly how
     * long a transition, a list inflate and a slow network-backed screen take, in
     * that order. Retrying a *lookup* is always safe — it reads, it does not act —
     * which is why the retry lives here and not around the tap itself. A tap that was
     * dispatched and rejected is not retried, because "rejected" and "happened but
     * looked like it didn't" are indistinguishable from here, and repeating a real
     * tap is how something gets bought twice.
     */
    private suspend fun awaitElement(
        label: String,
        service: LainAccessibilityService
    ): Any? {
        FIND_BACKOFF_MS.forEachIndexed { index, wait ->
            service.findTapPointByText(label)?.let { return it }
            if (index < FIND_BACKOFF_MS.lastIndex) kotlinx.coroutines.delay(wait)
        }
        return service.findTapPointByText(label)
    }

    /**
     * Searches everything Lain holds, not only her filed facts.
     *
     * Three stores answer to one question because the user does not know which of
     * them a thing landed in — "what did I say about the flat" might be a saved
     * memory, a note, or a sentence from a conversation last week, and being told
     * "nothing in memory" when it is sitting in a note is the same failure as not
     * having it at all.
     *
     * Each section is labelled so the model can say where something came from. A
     * remembered fact and a thing the user said once are different kinds of
     * evidence, and flattening them into one list invites stating an old passing
     * remark as a standing fact.
     */
    private suspend fun recall(query: String): ToolResult {
        if (query.isBlank()) return ToolResult.fail(FailureKind.INVALID_INPUT, "Nothing to search for.")

        val facts = memory.retrieveRelevant(query, limit = 8)
        val savedNotes = notes.search(query, limit = 4)
        val said = conversations.searchMessages(query, limit = 4)

        if (facts.isEmpty() && savedNotes.isEmpty() && said.isEmpty()) {
            return ToolResult.ok("Nothing about that in memory, notes or earlier conversations.")
        }

        return ToolResult.ok(
            buildString {
                if (facts.isNotEmpty()) {
                    append("REMEMBERED\n")
                    // Ids are included so edit_memory has something to address.
                    facts.forEach { append("- [${it.category.lowercase()}] ${it.fact} (id: ${it.id})\n") }
                }
                if (savedNotes.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("NOTES\n")
                    savedNotes.forEach { append("- ${it.text.take(200)}\n") }
                }
                if (said.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("SAID EARLIER (a past remark, not a standing fact)\n")
                    said.forEach { append("- ${it.role}: ${it.content.take(200)}\n") }
                }
            }.trim()
        )
    }

    /**
     * Remembers which SIM to use, so a dual-SIM phone stops asking every time.
     *
     * Honest about the split: texts genuinely go out on the chosen SIM with no
     * prompt, while for calls this is a hint the system dialler honours and some
     * third-party diallers ignore. Saying "it's set" for both would be half a lie.
     */
    private suspend fun setPreferredSim(which: String): ToolResult {
        val available = sims.available()
        return when {
            available.isEmpty() -> ToolResult.fail(
                FailureKind.CAPABILITY_UNAVAILABLE,
                "Lain can't read the SIM list — that needs the phone permission, which hasn't been granted."
            )
            available.size == 1 -> ToolResult.ok(
                "There's only one SIM in this phone (${available.first().describe()}), so there's nothing to choose."
            )
            which.isBlank() -> ToolResult.fail(
                FailureKind.INVALID_INPUT,
                "Which one? " + available.joinToString(", ") { it.describe() }
            )
            else -> {
                val chosen = sims.match(which) ?: return ToolResult.fail(
                    FailureKind.INVALID_INPUT,
                    "\"$which\" doesn't match a SIM in this phone. Available: " +
                        available.joinToString(", ") { it.describe() }
                )
                sims.remember(chosen.subscriptionId)
                ToolResult.ok(
                    "Texts will go out on ${chosen.describe()} from now on. For calls Lain passes that as a " +
                        "hint — the system dialler follows it, but a third-party dialler may still ask."
                )
            }
        }
    }

    /**
     * Closes an app, by whichever route Android actually permits.
     *
     * The two cases are genuinely different and were being conflated. A foreground app
     * can only be dismissed by driving Recents and swiping its card — Android removed
     * force-quit from ordinary apps years ago. A *background* app can be reaped with
     * killBackgroundProcesses, which needs no Accessibility Service at all and works
     * even when it is off.
     */
    private suspend fun closeApp(appName: String): ToolResult {
        val name = appName.trim()
        val service = LainAccessibilityService.instance

        // Is it actually what's on screen? If so, Recents is the only route.
        val foreground = service?.foregroundApp()
        val target = name.takeIf { it.isNotBlank() }
        val isForeground = target == null || foreground?.second?.contains(target, ignoreCase = true) == true ||
            apps.resolvePackage(target) == foreground?.first

        if (isForeground) {
            if (service == null) {
                return ToolResult.fail(
                    FailureKind.PERMISSION,
                    "Closing what's on screen needs the Accessibility Service — Android only lets the app be " +
                        "dismissed by swiping its card in Recents, which is a gesture. It isn't connected."
                )
            }
            service.closeCurrentApp()
            AccessibilityMonitor.reconcile(appContext)
            val now = LainAccessibilityService.instance?.foregroundApp()
            return if (now?.first != foreground?.first) {
                ToolResult.ok("Closed ${foreground?.second ?: target ?: "it"}.")
            } else {
                ToolResult.fail(
                    FailureKind.TOOL_FAILURE,
                    "Swiped in Recents but ${foreground?.second ?: "the app"} is still in the foreground."
                )
            }
        }

        if (target == null) return ToolResult.fail(FailureKind.INVALID_INPUT, "Which app?")
        return apps.killBackgroundProcess(target).asResult(FailureKind.TOOL_FAILURE)
    }

    // ------------------------------------------------------------ scheduling

    /**
     * Creates a scheduled task from either an explicit time or a plain phrase.
     *
     * The resolved time is always read back in the reply. A misparse ("2:30" landing
     * in the afternoon when the user meant the small hours) is then visible straight
     * away, rather than at 2:30.
     */
    private suspend fun scheduleTask(args: JsonObject): ToolResult {
        val phrase = args.str("when")
        val label = args.str("label")
        val actionName = args.str("action").ifBlank { "remind" }.uppercase()
        val action = runCatching { TaskAction.valueOf(actionName) }.getOrNull()
            ?: return ToolResult.fail(
                FailureKind.INVALID_INPUT,
                "action must be one of remind, alarm, call, sms, open_app."
            )

        val parsed = WhenParser.parse(phrase)
            ?: return ToolResult.fail(
                FailureKind.INVALID_INPUT,
                "Couldn't read a time out of \"$phrase\". Give a clock time (\"7:30 am\"), a delay " +
                    "(\"in 20 minutes\"), or add a repeat (\"every weekday at 7\")."
            )

        val repeat = args.str("repeat").takeIf { it.isNotBlank() }
            ?.let { runCatching { Repeat.valueOf(it.uppercase()) }.getOrNull() }
            ?: parsed.repeat

        val target = args.str("target")
        val needsTarget = action in setOf(
            TaskAction.CALL, TaskAction.SMS, TaskAction.WHATSAPP, TaskAction.OPEN_APP
        )
        if (needsTarget && target.isBlank()) {
            return ToolResult.fail(
                FailureKind.INVALID_INPUT,
                "A ${actionName.lowercase()} task needs a target — who to reach, or which app."
            )
        }
        if (action in setOf(TaskAction.SMS, TaskAction.WHATSAPP) && args.str("message").isBlank()) {
            return ToolResult.fail(FailureKind.INVALID_INPUT, "A scheduled message needs the text to send.")
        }

        // Resolved now, not at fire time.
        //
        // A task that names a contact who doesn't exist, or an app that isn't
        // installed, used to be accepted cheerfully and then fail silently at seven
        // in the morning — the worst possible moment to discover it, and with nobody
        // watching to notice. Anything checkable is checked while the user is still
        // here to correct it.
        when (action) {
            TaskAction.CALL, TaskAction.SMS, TaskAction.WHATSAPP -> {
                when (val who = messaging.resolveRecipient(target)) {
                    is MessageFlow.Recipient.Ambiguous -> return ToolResult.fail(
                        FailureKind.INVALID_INPUT,
                        "\"$target\" matches more than one contact:\n" +
                            who.options.joinToString("\n") { "- ${it.name} (${it.number})" } +
                            "\nAsk which one before scheduling this."
                    )
                    MessageFlow.Recipient.None -> return ToolResult.fail(
                        FailureKind.INVALID_INPUT,
                        "No contact matches \"$target\", and it isn't a number either. " +
                            "Scheduling it would just fail later, so nothing has been set."
                    )
                    // Missing contacts permission is not a reason to refuse: the user
                    // may grant it before the task fires, and a raw number needs no
                    // lookup at all.
                    MessageFlow.Recipient.NoPermission -> Unit
                    is MessageFlow.Recipient.Number -> Unit
                }
            }

            TaskAction.OPEN_APP -> {
                if (apps.resolvePackage(target) == null) {
                    return ToolResult.fail(
                        FailureKind.INVALID_INPUT,
                        "No installed app matches \"$target\", so that task would do nothing. Nothing has been set."
                    )
                }
            }

            else -> Unit
        }

        val task = ScheduledTask(
            label = label.ifBlank { parsed.remainder }.ifBlank { "Alarm" },
            action = action,
            target = target,
            payload = args.str("message"),
            triggerAtMillis = parsed.triggerAtMillis,
            repeat = repeat
        )

        return when (val outcome = scheduler.add(task)) {
            is SchedulingOutcome.Failed -> ToolResult.fail(FailureKind.TOOL_FAILURE, outcome.reason)
            is SchedulingOutcome.Scheduled -> {
                val note = when {
                    // Android will batch an inexact alarm with others to save power,
                    // so it can land minutes late. For an alarm that matters.
                    !outcome.exact ->
                        " Android hasn't given Lain permission for exact alarms, so this may arrive a few " +
                            "minutes late — allow \"Alarms & reminders\" for Lain in Settings to fix that."
                    outcome.task.action == TaskAction.CALL ->
                        " It'll ring and show a Call button — Lain won't dial on her own while you're away."
                    outcome.task.action == TaskAction.WHATSAPP ->
                        " WhatsApp has no send API, so Lain opens the chat with it typed and taps Send " +
                            "herself if Accessibility is connected. She'll say which happened."
                    else -> ""
                }
                ToolResult.ok("Set: ${outcome.task.describe()}.$note")
            }
        }
    }

    private suspend fun listScheduled(): ToolResult {
        val all = scheduler.all()
        if (all.isEmpty()) return ToolResult.ok("Nothing scheduled.")
        return ToolResult.ok(all.joinToString("\n") { "- ${it.describe()}" })
    }

    private suspend fun cancelScheduled(which: String): ToolResult =
        when (val outcome = scheduler.cancelMatching(which)) {
            is CancelOutcome.Cancelled -> ToolResult.ok("Cancelled: ${outcome.task.describe()}.")
            is CancelOutcome.Ambiguous -> ToolResult.fail(
                FailureKind.INVALID_INPUT,
                // Deleting the wrong alarm is not recoverable, so it asks instead.
                "\"$which\" matches more than one:\n" +
                    outcome.candidates.joinToString("\n") { "- ${it.describe()}" } +
                    "\nAsk the user which one."
            )
            CancelOutcome.NoMatch -> ToolResult.fail(
                FailureKind.INVALID_INPUT,
                "Nothing scheduled matches \"$which\". Call list_scheduled_tasks to see what's set."
            )
        }

    /**
     * Single gate for anything needing the Accessibility Service, so the agent gets
     * a precise reason (off vs. enabled-but-not-bound) instead of a generic refusal.
     *
     * Also the single point where screen commands are recorded. Every command that
     * reaches the service produces a RECEIVED and then exactly one of EXECUTED,
     * VERIFIED, REJECTED or EXCEPTION, so a log read after a failure shows whether
     * the command arrived, whether it ran, and whether the outcome was confirmed.
     * The distinction matters: "Lain said it tapped and nothing happened" and "Lain
     * never got the tap" are different bugs and had previously looked identical.
     */
    private suspend fun withService(
        command: String,
        block: suspend (LainAccessibilityService) -> ToolResult
    ): ToolResult {
        AccessibilityMonitor.record(AccessibilityMonitor.Event.COMMAND_RECEIVED, command)
        val service = LainAccessibilityService.instance
        if (service == null) {
            // Re-read Settings before answering: the state may have gone stale while
            // the app was backgrounded, and the advice differs per state.
            AccessibilityMonitor.reconcile(appContext)
            AccessibilityMonitor.record(
                AccessibilityMonitor.Event.COMMAND_REJECTED,
                "$command: no bound service"
            )
            return ToolResult.fail(
                FailureKind.PERMISSION,
                LainAccessibilityService.unavailableReason(appContext)
            )
        }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val result = try {
            block(service)
        } catch (t: Throwable) {
            AccessibilityMonitor.recordException(command, t)
            throw t
        }
        val took = android.os.SystemClock.elapsedRealtime() - startedAt
        // A screen payload means the post-state was actually read back off the device,
        // which is the only verification available to us. Without it we know the call
        // returned, not that the screen changed — so it stays EXECUTED, not VERIFIED.
        val event = when {
            !result.success -> AccessibilityMonitor.Event.COMMAND_REJECTED
            result.data.containsKey("screen") || result.image != null ->
                AccessibilityMonitor.Event.COMMAND_VERIFIED
            else -> AccessibilityMonitor.Event.COMMAND_EXECUTED
        }
        AccessibilityMonitor.record(event, "$command in ${took}ms")
        return result
    }

    private fun AutomationResult.asResult(permissionKind: FailureKind): ToolResult = when (this) {
        is AutomationResult.Success -> ToolResult.ok(message)
        is AutomationResult.Failure -> ToolResult.fail(FailureKind.TOOL_FAILURE, reason)
        is AutomationResult.MissingPermission ->
            ToolResult.fail(permissionKind, "Needs the $permission permission, which hasn't been granted.")
    }

    private fun AutomationResult.asFailure(kind: FailureKind): ToolResult = when (this) {
        is AutomationResult.Success -> ToolResult.ok(message)
        is AutomationResult.Failure -> ToolResult.fail(kind, reason)
        is AutomationResult.MissingPermission ->
            ToolResult.fail(FailureKind.PERMISSION, "Needs the $permission permission.")
    }

    private fun Bitmap.toJpegBase64(): String = ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.JPEG, 75, out)
        recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""
    private fun JsonObject.num(key: String): Float = this[key]?.jsonPrimitive?.floatOrNull ?: 0f
    private fun JsonObject.bool(key: String): Boolean =
        this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
}
