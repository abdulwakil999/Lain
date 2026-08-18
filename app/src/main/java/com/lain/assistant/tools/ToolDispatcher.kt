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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

    suspend fun execute(call: ToolCall): String {
        val args = runCatching { json.parseToJsonElement(call.argumentsJson) as JsonObject }.getOrNull()
            ?: return "Failed: couldn't parse arguments"

        val result = try {
            when (call.name) {
                // ------------------------------------------------------ apps
                "open_app" -> apps.openApp(args.str("app_name")).toToolOutput()
                "close_app" -> closeApp(args.str("app_name"))
                "open_url" -> phone.openUrl(args.str("url")).toToolOutput()

                // -------------------------------------------------- perception
                "read_screen" -> withService { it.readScreenText() }
                "look_at_screen" -> withService { service ->
                    service.captureScreenshotBase64()
                        ?.let { IMAGE_RESULT_PREFIX + it }
                        ?: "Failed: couldn't capture a screenshot (needs Android 11+)"
                }

                // ----------------------------------------------------- control
                "tap_text" -> withService { service ->
                    val label = args.str("text")
                    val point = service.findTapPointByText(label)
                        ?: return@withService "Couldn't find \"$label\" on screen. Call read_screen to see what's actually there, then try a label from that list."
                    service.tap(point.first.toFloat(), point.second.toFloat())
                    delay(600)
                    "Tapped \"$label\". The screen has probably changed — call read_screen to see the new state and continue."
                }
                "tap_screen" -> withService { service ->
                    service.tap(args.num("x"), args.num("y"))
                    delay(600)
                    "Tapped (${args.num("x").toInt()}, ${args.num("y").toInt()}). The screen has probably changed — call read_screen to see the new state and continue."
                }
                "type_text" -> withService { service ->
                    val text = args.str("text")
                    if (service.typeText(text)) {
                        "Typed \"$text\". Call read_screen to confirm it landed in the right field and to find the send/next button."
                    } else {
                        "Couldn't find a text field to type into. Call read_screen, tap the [INPUT] element you want first, then type again."
                    }
                }
                "press_key" -> withService { service ->
                    when (args.str("key").lowercase()) {
                        "back" -> { service.goBack(); "Pressed back." }
                        "home" -> { service.goHome(); "Went to the home screen." }
                        "recents" -> { service.openRecents(); "Opened recents." }
                        "notifications" -> { service.openNotifications(); "Opened the notification shade." }
                        "enter" -> {
                            // No global ENTER action exists; the reliable equivalent is the IME's
                            // own action button, which is on-screen and tappable.
                            service.findTapPointByText("Send")?.let { service.tap(it.first.toFloat(), it.second.toFloat()) }
                            "Tried to confirm via the on-screen Send/Go button. Call read_screen to check it worked."
                        }
                        else -> "Unknown key. Use one of: back, home, recents, notifications, enter."
                    }
                }
                "swipe_screen" -> withService { service ->
                    service.swipe(args.num("x1"), args.num("y1"), args.num("x2"), args.num("y2"))
                    delay(500)
                    "Swiped. Call read_screen to see the new state."
                }
                "wait" -> {
                    val seconds = args.num("seconds").coerceIn(0.5f, 5f)
                    delay((seconds * 1000).toLong())
                    "Waited ${seconds}s. Call read_screen to see the current state."
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
        return if (result.startsWith(IMAGE_RESULT_PREFIX) || result.length <= MAX_RESULT_CHARS) {
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
