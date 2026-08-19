package com.lain.assistant.tools

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.lain.assistant.automation.AppLauncher
import com.lain.assistant.automation.CameraController
import com.lain.assistant.automation.LainAccessibilityService
import com.lain.assistant.automation.NotesRepository
import com.lain.assistant.automation.PhoneController
import com.lain.assistant.automation.RemindersRepository
import com.lain.assistant.automation.VoiceInputController
import com.lain.assistant.data.MemoryRepository
import com.lain.assistant.network.ToolCall
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

/** Executes a [ToolCall] the model asked for and turns the outcome into text the model can read back. */
class ToolDispatcher(context: Context) {

    companion object {
        /**
         * A tool result that starts with this carries a base64 JPEG after the
         * prefix. ChatViewModel watches for it and attaches the image to a
         * follow-up turn so vision-capable models actually see it, rather than
         * reading a wall of base64 as text.
         */
        const val IMAGE_RESULT_PREFIX = "IMAGE_BASE64:"

        /** Ceiling on any single textual tool result, so one dense screen can't blow the context window. */
        private const val MAX_RESULT_CHARS = 3500
    }

    private val appContext = context.applicationContext
    private val phone = PhoneController(appContext)
    private val apps = AppLauncher(appContext)
    private val notes = NotesRepository(appContext)
    private val reminders = RemindersRepository(appContext)
    private val camera = CameraController(appContext)
    private val voice = VoiceInputController(appContext)
    private val memory = MemoryRepository(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Runs off the main thread. Walking an app's accessibility tree is not free,
     * and doing it on the UI thread (which the engine's scope lives on) shows up
     * as jank or, on a dense screen, an ANR.
     */
    suspend fun execute(call: ToolCall): String = withContext(Dispatchers.Default) {
        val args = runCatching { json.parseToJsonElement(call.argumentsJson) as JsonObject }.getOrNull()
            ?: return@withContext "Failed: couldn't parse arguments"

        val result = try {
            when (call.name) {
                // ------------------------------------------------------ apps
                "open_app" -> {
                    val opened = apps.openApp(args.str("app_name")).toToolOutput()
                    // Apps need a beat to draw; wait here and hand back the loaded screen so
                    // the model doesn't burn two more rounds on wait + read_screen.
                    delay(1600)
                    val screen = LainAccessibilityService.instance?.readScreenText()
                    if (screen != null) "$opened\n\nScreen now:\n$screen" else opened
                }
                "close_app" -> closeApp(args.str("app_name"))
                "open_url" -> {
                    val opened = phone.openUrl(args.str("url")).toToolOutput()
                    delay(2200)
                    val screen = LainAccessibilityService.instance?.readScreenText()
                    if (screen != null) "$opened\n\nScreen now:\n$screen" else opened
                }

                // -------------------------------------------------- perception
                "read_screen" -> withService { it.readScreenText() }
                "look_at_screen" -> withService { service ->
                    service.captureScreenshotBase64()
                        ?.let { IMAGE_RESULT_PREFIX + it }
                        ?: "Failed: couldn't capture a screenshot (needs Android 11+)"
                }

                // ----------------------------------------------------- control
                // Every action below returns the resulting screen inline. Making the model
                // spend a whole extra round on read_screen after each tap was the main reason
                // it ran out of steps halfway through a task — and it doubled the tokens and
                // request count for no benefit, since it always needs the new screen anyway.
                "tap_text" -> withService { service ->
                    val label = args.str("text")
                    val point = service.findTapPointByText(label)
                        ?: return@withService "Couldn't find \"$label\" on screen.\n\nHere's what IS on screen — pick a label from this list:\n" +
                            service.readScreenText()
                    service.tap(point.first.toFloat(), point.second.toFloat())
                    delay(700)
                    "Tapped \"$label\".\n\nScreen now:\n" + service.readScreenText()
                }
                "tap_screen" -> withService { service ->
                    service.tap(args.num("x"), args.num("y"))
                    delay(700)
                    "Tapped (${args.num("x").toInt()}, ${args.num("y").toInt()}).\n\nScreen now:\n" + service.readScreenText()
                }
                "type_text" -> withService { service ->
                    val text = args.str("text")
                    if (service.typeText(text)) {
                        delay(400)
                        "Typed \"$text\".\n\nScreen now:\n" + service.readScreenText()
                    } else {
                        "Couldn't find a text field to type into. Tap the [INPUT] element you want first, then type again.\n\nScreen now:\n" +
                            service.readScreenText()
                    }
                }
                "press_key" -> withService { service ->
                    val label = when (args.str("key").lowercase()) {
                        "back" -> { service.goBack(); "Pressed back." }
                        "home" -> { service.goHome(); "Went to the home screen." }
                        "recents" -> { service.openRecents(); "Opened recents." }
                        "notifications" -> { service.openNotifications(); "Opened the notification shade." }
                        "enter" -> {
                            // There's no global ENTER action; submitting means pressing the app's
                            // own confirm control, so try the usual labels before giving up.
                            val hit = listOf("Search", "Go", "Send", "Done", "Enter")
                                .firstNotNullOfOrNull { service.findTapPointByText(it) }
                            if (hit != null) {
                                service.tap(hit.first.toFloat(), hit.second.toFloat())
                                "Submitted."
                            } else {
                                "No submit button found — tap the app's own Search/Send control instead."
                            }
                        }
                        else -> return@withService "Unknown key. Use one of: back, home, recents, notifications, enter."
                    }
                    delay(700)
                    "$label\n\nScreen now:\n" + service.readScreenText()
                }
                "swipe_screen" -> withService { service ->
                    service.swipe(args.num("x1"), args.num("y1"), args.num("x2"), args.num("y2"))
                    delay(600)
                    "Swiped.\n\nScreen now:\n" + service.readScreenText()
                }
                "wait" -> {
                    val seconds = args.num("seconds").coerceIn(0.5f, 5f)
                    delay((seconds * 1000).toLong())
                    val screen = LainAccessibilityService.instance?.readScreenText()
                    if (screen != null) "Waited ${seconds}s.\n\nScreen now:\n$screen" else "Waited ${seconds}s."
                }

                // ----------------------------------------------- communication
                "send_sms" -> phone.sendSms(args.str("phone_number"), args.str("message")).toToolOutput()
                "make_call" -> phone.placeCall(args.str("phone_number")).toToolOutput()
                "lookup_contact" -> phone.lookupContact(args.str("name")).toToolOutput()
                "send_whatsapp_message" -> phone.openWhatsAppChat(args.str("phone_number"), args.str("message")).toToolOutput()

                // --------------------------------------------------- utility
                "set_reminder" -> reminders.schedule(
                    text = args.str("text"),
                    triggerAtMillis = System.currentTimeMillis() + (args.num("minutes_from_now") * 60_000).toLong()
                ).toToolOutput()
                "write_note" -> notes.addNote(args.str("text")).let { "Saved note: \"${it.text}\"" }
                "list_notes" -> notes.notes.first().joinToString("\n") { "- ${it.text}" }.ifBlank { "No notes yet" }
                "take_photo" -> camera.capturePhoto(args.bool("use_front_camera")).fold(
                    onSuccess = { bitmap -> IMAGE_RESULT_PREFIX + bitmap.toJpegBase64() },
                    onFailure = { "Failed: ${it.message}" }
                )
                "listen_microphone" -> voice.listenOnce().fold(
                    onSuccess = { "Heard: \"$it\"" },
                    onFailure = { "Failed: ${it.message}" }
                )

                // ---------------------------------------------------- memory
                "remember" -> memory.remember(args.str("key"), args.str("value"))
                    .let { "Remembered — ${it.key}: ${it.value}" }
                "forget" -> if (memory.forget(args.str("key"))) {
                    "Forgot \"${args.str("key")}\"."
                } else {
                    "Nothing remembered under \"${args.str("key")}\"."
                }

                else -> "Unknown tool: ${call.name}"
            }
        } catch (t: Throwable) {
            "Failed: ${t.message}"
        }

        // Images are passed through whole; only prose gets clipped.
        if (result.startsWith(IMAGE_RESULT_PREFIX) || result.length <= MAX_RESULT_CHARS) {
            result
        } else {
            result.take(MAX_RESULT_CHARS) + "\n… (truncated)"
        }
    }

    private suspend fun closeApp(appName: String): String {
        val service = LainAccessibilityService.instance
            ?: return LainAccessibilityService.unavailableReason(appContext)
        service.closeCurrentApp()
        return "Closed $appName"
    }

    /**
     * Single gate for everything needing the Accessibility Service. When it isn't
     * usable the message distinguishes "switched off" from "on but not connected",
     * instead of always claiming it's off.
     */
    private suspend fun withService(block: suspend (LainAccessibilityService) -> String): String {
        val service = LainAccessibilityService.instance
            ?: return LainAccessibilityService.unavailableReason(appContext)
        return block(service)
    }

    private fun Bitmap.toJpegBase64(): String = ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.JPEG, 75, out)
        recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""
    private fun JsonObject.num(key: String): Float = this[key]?.jsonPrimitive?.floatOrNull ?: 0f
    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
}
