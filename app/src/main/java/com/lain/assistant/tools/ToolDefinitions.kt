package com.lain.assistant.tools

import com.lain.assistant.network.ToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Every action Lain can take on the device, described once and handed to whichever LLM is selected. */
object ToolDefinitions {

    val all: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "open_app",
            description = "Open an app already installed on the phone by its display name.",
            parameters = schema {
                property("app_name", "string", "Display name of the app, e.g. \"Chrome\" or \"WhatsApp\"")
                required("app_name")
            }
        ),
        ToolDefinition(
            name = "close_app",
            description = "Close/dismiss the app currently in the foreground, or stop a backgrounded app's process.",
            parameters = schema {
                property("app_name", "string", "Display name of the app to close")
                required("app_name")
            }
        ),
        ToolDefinition(
            name = "set_reminder",
            description = "Schedule a reminder notification.",
            parameters = schema {
                property("text", "string", "What to remind the user about")
                property("minutes_from_now", "number", "How many minutes from now to trigger the reminder")
                required("text", "minutes_from_now")
            }
        ),
        ToolDefinition(
            name = "write_note",
            description = "Save a note for the user.",
            parameters = schema {
                property("text", "string", "Note content")
                required("text")
            }
        ),
        ToolDefinition(
            name = "list_notes",
            description = "List the user's saved notes.",
            parameters = schema { }
        ),
        ToolDefinition(
            name = "send_sms",
            description = "Send a text message via SMS.",
            parameters = schema {
                property("phone_number", "string", "Recipient's phone number")
                property("message", "string", "Message body")
                required("phone_number", "message")
            }
        ),
        ToolDefinition(
            name = "make_call",
            description = "Place a phone call.",
            parameters = schema {
                property("phone_number", "string", "Number to call")
                required("phone_number")
            }
        ),
        ToolDefinition(
            name = "send_whatsapp_message",
            description = "Open a WhatsApp chat with a message pre-filled, ready to send.",
            parameters = schema {
                property("phone_number", "string", "Recipient's phone number, with country code")
                property("message", "string", "Message text")
                required("phone_number", "message")
            }
        ),
        ToolDefinition(
            name = "read_screen",
            description = "Read the text currently visible on screen (requires the Accessibility Service to be enabled).",
            parameters = schema { }
        ),
        ToolDefinition(
            name = "tap_screen",
            description = "Tap the screen at a specific coordinate (requires the Accessibility Service). Use read_screen first to find coordinates.",
            parameters = schema {
                property("x", "number", "X coordinate in pixels")
                property("y", "number", "Y coordinate in pixels")
                required("x", "y")
            }
        ),
        ToolDefinition(
            name = "swipe_screen",
            description = "Swipe from one point to another (requires the Accessibility Service).",
            parameters = schema {
                property("x1", "number", "Start X")
                property("y1", "number", "Start Y")
                property("x2", "number", "End X")
                property("y2", "number", "End Y")
                required("x1", "y1", "x2", "y2")
            }
        ),
        ToolDefinition(
            name = "take_photo",
            description = "Capture a photo using the phone's camera.",
            parameters = schema {
                property("use_front_camera", "boolean", "true for the selfie camera, false for the rear camera")
            }
        ),
        ToolDefinition(
            name = "listen_microphone",
            description = "Listen to the microphone and transcribe what's said.",
            parameters = schema { }
        )
    )

    private class SchemaBuilder {
        val properties = mutableListOf<Triple<String, String, String>>()
        val requiredFields = mutableListOf<String>()

        fun property(name: String, type: String, description: String) {
            properties += Triple(name, type, description)
        }

        fun required(vararg names: String) {
            requiredFields += names
        }
    }

    private fun schema(build: SchemaBuilder.() -> Unit) = buildJsonObject {
        val builder = SchemaBuilder().apply(build)
        put("type", "object")
        putJsonObject("properties") {
            for ((name, type, description) in builder.properties) {
                putJsonObject(name) {
                    put("type", type)
                    put("description", description)
                }
            }
        }
        if (builder.requiredFields.isNotEmpty()) {
            putJsonArray("required") {
                builder.requiredFields.forEach { add(JsonPrimitive(it)) }
            }
        }
    }
}
