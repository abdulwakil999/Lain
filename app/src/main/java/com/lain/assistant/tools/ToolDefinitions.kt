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
        // ---------------------------------------------------------- apps
        ToolDefinition(
            name = "open_app",
            description = "Open an app already installed on the phone by its display name. After this, the app needs a moment to load — call read_screen next to see what appeared before doing anything else.",
            parameters = schema {
                property("app_name", "string", "Display name of the app, e.g. \"Chrome\", \"Spotify\", \"WhatsApp\"")
                required("app_name")
            }
        ),
        ToolDefinition(
            name = "close_app",
            description = "Close/dismiss the app currently in the foreground.",
            parameters = schema {
                property("app_name", "string", "Display name of the app to close")
                required("app_name")
            }
        ),
        ToolDefinition(
            name = "open_url",
            description = "Open a web address in the browser.",
            parameters = schema {
                property("url", "string", "Full URL including https://")
                required("url")
            }
        ),

        // ------------------------------------------------- screen perception
        ToolDefinition(
            name = "read_screen",
            description = "Read what's on screen right now as a list of labelled elements with tap coordinates, each marked [INPUT] (text field), [BUTTON] (tappable) or [text]. NOTE: every action tool already returns the updated screen for you, so you do NOT need to call this after tapping, typing or opening something — only use it to look before acting.",
            parameters = schema { }
        ),
        ToolDefinition(
            name = "look_at_screen",
            description = "Take a screenshot and visually look at it. Use when layout, images, colours or a game board matter more than text labels — read_screen cannot see those.",
            parameters = schema { }
        ),

        // ----------------------------------------------------- screen control
        ToolDefinition(
            name = "tap_text",
            description = "Tap the on-screen element whose label matches the given text. Prefer this over tap_screen — it's far more reliable than guessing coordinates.",
            parameters = schema {
                property("text", "string", "Visible label of the thing to tap, e.g. \"Search\", \"Send\", a contact's name")
                required("text")
            }
        ),
        ToolDefinition(
            name = "tap_screen",
            description = "Tap an exact pixel coordinate. Only use when tap_text can't identify the target; get coordinates from read_screen first.",
            parameters = schema {
                property("x", "number", "X coordinate in pixels")
                property("y", "number", "Y coordinate in pixels")
                required("x", "y")
            }
        ),
        ToolDefinition(
            name = "type_text",
            description = "Type text into the currently focused text field. Tap the field first (tap_text) so it has focus, then call this. This is how you fill in search boxes, message composers, and forms.",
            parameters = schema {
                property("text", "string", "The text to type")
                required("text")
            }
        ),
        ToolDefinition(
            name = "press_key",
            description = "Press a system navigation key.",
            parameters = schema {
                property("key", "string", "One of: back, home, recents, notifications, enter")
                required("key")
            }
        ),
        ToolDefinition(
            name = "swipe_screen",
            description = "Swipe from one point to another — for scrolling lists or dismissing things.",
            parameters = schema {
                property("x1", "number", "Start X")
                property("y1", "number", "Start Y")
                property("x2", "number", "End X")
                property("y2", "number", "End Y")
                required("x1", "y1", "x2", "y2")
            }
        ),
        ToolDefinition(
            name = "wait",
            description = "Pause briefly to let the screen finish loading or an animation settle, then continue. Use after opening an app or tapping something slow.",
            parameters = schema {
                property("seconds", "number", "How long to wait, 1-5 seconds")
                required("seconds")
            }
        ),

        // ------------------------------------------------------- communication
        ToolDefinition(
            name = "send_sms",
            description = "Send a text message via SMS directly (does not open any app).",
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
            name = "lookup_contact",
            description = "Look up a saved contact's phone number by name. Use this before make_call or send_sms when the user names a person instead of a number.",
            parameters = schema {
                property("name", "string", "Contact name to search for")
                required("name")
            }
        ),
        ToolDefinition(
            name = "send_whatsapp_message",
            description = "Open a WhatsApp chat with a message pre-filled. It does NOT send by itself — after calling this, use read_screen then tap_text(\"Send\") to actually send it.",
            parameters = schema {
                property("phone_number", "string", "Recipient's phone number, with country code")
                property("message", "string", "Message text")
                required("phone_number", "message")
            }
        ),

        // ----------------------------------------------------------- utility
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
            name = "take_photo",
            description = "Capture a photo using the phone's camera and look at it.",
            parameters = schema {
                property("use_front_camera", "boolean", "true for the selfie camera, false for the rear camera")
            }
        ),
        ToolDefinition(
            name = "listen_microphone",
            description = "Listen to the microphone and transcribe what's said.",
            parameters = schema { }
        ),

        // ------------------------------------------------------------ memory
        ToolDefinition(
            name = "remember",
            description = "Permanently remember a fact about the user — who a nickname refers to, a preference, an app they use for something. Save these whenever you learn one, without being asked.",
            parameters = schema {
                property("key", "string", "Short identifier, e.g. \"mum's number\", \"favourite music app\"")
                property("value", "string", "The fact to remember")
                required("key", "value")
            }
        ),
        ToolDefinition(
            name = "forget",
            description = "Delete something previously remembered.",
            parameters = schema {
                property("key", "string", "The identifier to forget")
                required("key")
            }
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
