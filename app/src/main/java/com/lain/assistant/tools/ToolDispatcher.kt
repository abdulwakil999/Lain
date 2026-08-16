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
import com.lain.assistant.network.ToolCall
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
         * A tool result that starts with this carries a base64 JPEG after
         * the prefix. ChatViewModel watches for it and, instead of just
         * feeding the raw string back as the tool result, attaches the
         * image to a follow-up turn so vision-capable models actually see
         * it rather than reading a wall of base64 as text.
         */
        const val IMAGE_RESULT_PREFIX = "IMAGE_BASE64:"
    }

    private val appContext = context.applicationContext
    private val phone = PhoneController(appContext)
    private val apps = AppLauncher(appContext)
    private val notes = NotesRepository(appContext)
    private val reminders = RemindersRepository(appContext)
    private val camera = CameraController(appContext)
    private val voice = VoiceInputController(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun execute(call: ToolCall): String {
        val args = runCatching { json.parseToJsonElement(call.argumentsJson) as JsonObject }.getOrNull()
            ?: return "Failed: couldn't parse arguments"

        return try {
            when (call.name) {
                "open_app" -> apps.openApp(args.str("app_name")).toToolOutput()
                "close_app" -> closeApp(args.str("app_name"))
                "set_reminder" -> reminders.schedule(
                    text = args.str("text"),
                    triggerAtMillis = System.currentTimeMillis() + (args.num("minutes_from_now") * 60_000).toLong()
                ).toToolOutput()
                "write_note" -> notes.addNote(args.str("text")).let { "Saved note: \"${it.text}\"" }
                "list_notes" -> notes.notes.first().joinToString("\n") { "- ${it.text}" }.ifBlank { "No notes yet" }
                "send_sms" -> phone.sendSms(args.str("phone_number"), args.str("message")).toToolOutput()
                "make_call" -> phone.placeCall(args.str("phone_number")).toToolOutput()
                "send_whatsapp_message" -> phone.openWhatsAppChat(args.str("phone_number"), args.str("message")).toToolOutput()
                "read_screen" -> withAccessibilityService { it.readScreenText() }
                "look_at_screen" -> withAccessibilityService { service ->
                    val base64 = service.captureScreenshotBase64()
                        ?: return@withAccessibilityService "Failed: couldn't capture a screenshot (needs Android 11+)"
                    IMAGE_RESULT_PREFIX + base64
                }
                "tap_screen" -> withAccessibilityService { it.tap(args.num("x"), args.num("y")); "Tapped (${args.num("x")}, ${args.num("y")})" }
                "swipe_screen" -> withAccessibilityService {
                    it.swipe(args.num("x1"), args.num("y1"), args.num("x2"), args.num("y2"))
                    "Swiped"
                }
                "take_photo" -> camera.capturePhoto(args.bool("use_front_camera")).fold(
                    onSuccess = { bitmap -> IMAGE_RESULT_PREFIX + bitmap.toJpegBase64() },
                    onFailure = { "Failed: ${it.message}" }
                )
                "listen_microphone" -> voice.listenOnce().fold(
                    onSuccess = { "Heard: \"$it\"" },
                    onFailure = { "Failed: ${it.message}" }
                )
                else -> "Unknown tool: ${call.name}"
            }
        } catch (t: Throwable) {
            "Failed: ${t.message}"
        }
    }

    private suspend fun closeApp(appName: String): String {
        val service = LainAccessibilityService.instance
            ?: return "Needs the Accessibility Service enabled — ask the user to turn on Lain's Accessibility Service in Settings."
        service.closeCurrentApp()
        return "Closed $appName"
    }

    private suspend fun withAccessibilityService(block: suspend (LainAccessibilityService) -> String): String {
        val service = LainAccessibilityService.instance
            ?: return "Needs the Accessibility Service enabled — ask the user to turn on Lain's Accessibility Service in Settings."
        return block(service)
    }

    private fun Bitmap.toJpegBase64(): String = ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.JPEG, 85, out)
        recycle()
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""
    private fun JsonObject.num(key: String): Float = this[key]?.jsonPrimitive?.floatOrNull ?: 0f
    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
}
