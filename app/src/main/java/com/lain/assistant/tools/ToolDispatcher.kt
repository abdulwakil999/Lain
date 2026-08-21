package com.lain.assistant.tools

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.lain.assistant.automation.AppLauncher
import com.lain.assistant.automation.AutomationResult
import com.lain.assistant.automation.CameraController
import com.lain.assistant.automation.DeviceController
import com.lain.assistant.automation.LainAccessibilityService
import com.lain.assistant.automation.MessageFlow
import com.lain.assistant.automation.NotesRepository
import com.lain.assistant.automation.PhoneController
import com.lain.assistant.automation.RemindersRepository
import com.lain.assistant.automation.VoiceInputController
import com.lain.assistant.data.MemoryCategory
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
    }

    private val appContext = context.applicationContext
    private val phone = PhoneController(appContext)
    private val apps = AppLauncher(appContext)
    private val notes = NotesRepository(appContext)
    private val reminders = RemindersRepository(appContext)
    private val camera = CameraController(appContext)
    private val voice = VoiceInputController(appContext)
    private val device = DeviceController(appContext)
    private val messaging = MessageFlow(appContext)
    private val web = WebResearch()
    private val memory = MemoryStore(appContext)
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

        val result = try {
            dispatch(call.name, args)
        } catch (t: Throwable) {
            ToolResult.fail(FailureKind.TOOL_FAILURE, "${call.name} threw an exception", t.message)
        }

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

        "close_app" -> withService { service ->
            service.closeCurrentApp()
            ToolResult.ok("Closed ${args.str("app_name")}.")
        }

        "open_url" -> phone.openUrl(args.str("url")).asResult(FailureKind.APP_UNAVAILABLE)

        "open_settings_page" -> device.openSettingsPage(args.str("page"))

        "current_app" -> withService { service ->
            val fg = service.foregroundApp()
            if (fg == null) ToolResult.ok("Lain's own screen is in the foreground — no other app is open.")
            else ToolResult.ok("Foreground app: ${fg.second} (${fg.first})")
        }

        // ---------------------------------------------------- perception
        "read_screen" -> withService { ToolResult.ok(it.readScreenText()) }

        "look_at_screen" -> withService { service ->
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
        "tap_text" -> withService { service ->
            val label = args.str("text")
            if (service.findTapPointByText(label) == null) {
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

        "tap_screen" -> withService { service ->
            val x = args.num("x"); val y = args.num("y")
            val tapped = service.tap(x, y)
            if (tapped) {
                service.awaitSettle()
                ToolResult.ok("Tapped (${x.toInt()}, ${y.toInt()}).", data = mapOf("screen" to service.readScreenCompact()))
            } else {
                ToolResult.fail(FailureKind.TOOL_FAILURE, "The tap gesture was rejected.")
            }
        }

        "type_text" -> withService { service ->
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

        "press_key" -> withService { service ->
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

        "swipe_screen" -> withService { service ->
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
        "make_call" -> phone.placeCall(args.str("phone_number")).asResult(FailureKind.PERMISSION)
        "lookup_contact" -> phone.lookupContact(args.str("name")).asResult(FailureKind.PERMISSION)
        "send_whatsapp_message" ->
            phone.openWhatsAppChat(args.str("phone_number"), args.str("message")).asResult(FailureKind.APP_UNAVAILABLE)
        "open_contacts" -> device.openContacts()

        // ------------------------------------------------- web research
        "web_search" -> web.search(args.str("query"))
        "fetch_page" -> web.fetchPage(args.str("url"))

        // ------------------------------------------------------ device
        "device_status" -> device.deviceStatus()
        "set_volume" -> device.setMediaVolume(args.num("percent").toInt())
        "clipboard" -> when (args.str("action").lowercase()) {
            "write" -> device.writeClipboard(args.str("text"))
            else -> device.readClipboard()
        }

        // ------------------------------------------------------- files
        "list_files" -> device.listFiles(args.str("path"))
        "read_file" -> device.readFile(args.str("path"))
        "write_file" -> device.writeFile(args.str("path"), args.str("content"), args.bool("append"))
        "rename_file" -> device.renameFile(args.str("from"), args.str("to"))

        // ----------------------------------------------------- utility
        "set_reminder" -> reminders.schedule(
            text = args.str("text"),
            triggerAtMillis = System.currentTimeMillis() + (args.num("minutes_from_now") * 60_000).toLong()
        ).asResult(FailureKind.PERMISSION)

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

        "forget" -> {
            val removed = memory.forget(args.str("key"))
            if (removed > 0) ToolResult.ok("Forgot $removed memory item(s) matching \"${args.str("key")}\".")
            else ToolResult.ok("Nothing stored matched \"${args.str("key")}\".")
        }

        "recall" -> {
            val hits = memory.retrieveRelevant(args.str("query"), limit = 8)
            if (hits.isEmpty()) ToolResult.ok("Nothing relevant in memory.")
            else ToolResult.ok(hits.joinToString("\n") { "- [${it.category.lowercase()}] ${it.fact}" })
        }

        else -> ToolResult.fail(FailureKind.INVALID_INPUT, "Unknown tool: $name")
    }

    /**
     * Single gate for anything needing the Accessibility Service, so the agent gets
     * a precise reason (off vs. enabled-but-not-bound) instead of a generic refusal.
     */
    private suspend fun withService(block: suspend (LainAccessibilityService) -> ToolResult): ToolResult {
        val service = LainAccessibilityService.instance
            ?: return ToolResult.fail(
                FailureKind.PERMISSION,
                LainAccessibilityService.unavailableReason(appContext)
            )
        return block(service)
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
