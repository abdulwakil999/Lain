package com.lain.assistant.tools

/**
 * Why something failed, so the agent can choose a sensible response instead of
 * treating every error as the same dead end. The distinction that matters most:
 * some of these are worth retrying, most are not.
 */
enum class FailureKind(val retryable: Boolean, val label: String) {
    PERMISSION(false, "permission"),
    NETWORK(true, "network"),
    RATE_LIMIT(true, "rate limit"),
    TIMEOUT(true, "timeout"),
    INVALID_INPUT(false, "invalid input"),
    APP_UNAVAILABLE(false, "app unavailable"),
    CAPABILITY_UNAVAILABLE(false, "capability unavailable"),
    TOOL_FAILURE(false, "tool failure"),
    MODEL_FAILURE(true, "model failure"),
    UNKNOWN(false, "unknown");
}

/**
 * Structured outcome of a tool call.
 *
 * The agent needs to distinguish "I asked for this", "it was attempted" and "it
 * actually worked" — collapsing those into one prose string is how assistants end
 * up confidently reporting success for things that never happened. [success] is
 * only ever set by a tool that genuinely confirmed the result.
 */
data class ToolResult(
    val success: Boolean,
    /** Human/model-readable outcome. Always present. */
    val result: String,
    val error: String? = null,
    val failure: FailureKind? = null,
    /** Extra structured payload (screen dumps, search hits) kept out of [result]. */
    val data: Map<String, String> = emptyMap(),
    /** Base64 JPEG, when the tool produced an image for a vision-capable model. */
    val image: String? = null,
    /** Whether repeating this exact call could plausibly help. */
    val retryable: Boolean = failure?.retryable ?: false
) {
    /** Compact serialization handed back to the model as the tool message. */
    fun toModelText(): String = buildString {
        append(if (success) "SUCCESS" else "FAILED")
        if (!success && failure != null) append(" [${failure.label}]")
        append(": ")
        append(result)
        if (!success && !error.isNullOrBlank() && error != result) {
            append("\nDetail: ").append(error)
        }
        data["screen"]?.let { append("\n\nScreen now:\n").append(it) }
        data["sources"]?.let { append("\n\nSources:\n").append(it) }
    }

    companion object {
        fun ok(result: String, data: Map<String, String> = emptyMap(), image: String? = null) =
            ToolResult(success = true, result = result, data = data, image = image)

        fun fail(
            kind: FailureKind,
            result: String,
            error: String? = null,
            data: Map<String, String> = emptyMap()
        ) = ToolResult(success = false, result = result, error = error, failure = kind, data = data)
    }
}

/**
 * What a tool is for and what it costs, described once so the agent can choose
 * correctly and the app can gate risky actions.
 */
data class ToolMeta(
    val name: String,
    val purpose: String,
    val permissions: List<String> = emptyList(),
    val commonFailures: List<String> = emptyList(),
    /** Irreversible or outward-facing actions the user should confirm first. */
    val requiresConfirmation: Boolean = false,
    val reversible: Boolean = true,
    /** Needs the Accessibility Service to be connected. */
    val needsAccessibility: Boolean = false,
    /** Only meaningful on a vision-capable model. */
    val needsVision: Boolean = false
)

object ToolRegistry {
    val meta: Map<String, ToolMeta> = listOf(
        ToolMeta("open_app", "Launch an installed app by display name.",
            commonFailures = listOf("app not installed", "no launchable activity")),
        ToolMeta("close_app", "Dismiss the foreground app via Recents.",
            needsAccessibility = true, reversible = true,
            commonFailures = listOf("OEM recents layout differs")),
        ToolMeta("open_url", "Open a web page in the browser."),
        ToolMeta("open_settings_page", "Open a specific Android Settings screen."),
        ToolMeta("current_app", "Report which app is in the foreground.", needsAccessibility = true),
        ToolMeta("read_screen", "Read visible on-screen text and tap targets.", needsAccessibility = true),
        ToolMeta("look_at_screen", "Screenshot the screen and look at it visually.",
            needsAccessibility = true, needsVision = true,
            commonFailures = listOf("Android below 11", "screenshot blocked by app")),
        ToolMeta("tap_text", "Tap the element matching a visible label.", needsAccessibility = true, reversible = false),
        ToolMeta("tap_screen", "Tap an exact coordinate.", needsAccessibility = true, reversible = false),
        ToolMeta("type_text", "Type into the focused text field.", needsAccessibility = true, reversible = false),
        ToolMeta("press_key", "Press back/home/recents/notifications or submit.", needsAccessibility = true),
        ToolMeta("swipe_screen", "Scroll or swipe.", needsAccessibility = true),
        ToolMeta("wait", "Pause for the screen to settle."),
        ToolMeta("make_call", "Place a phone call.",
            permissions = listOf("CALL_PHONE"), requiresConfirmation = true, reversible = false,
            commonFailures = listOf("permission denied", "third-party dialer intercepts")),
        ToolMeta("send_sms", "Send an SMS directly.",
            permissions = listOf("SEND_SMS"), requiresConfirmation = true, reversible = false),
        ToolMeta("lookup_contact", "Find a contact's number by name.", permissions = listOf("READ_CONTACTS")),
        ToolMeta("send_whatsapp_message", "Open a WhatsApp chat with text prefilled (does not send).",
            reversible = true, commonFailures = listOf("WhatsApp not installed")),
        ToolMeta("set_reminder", "Schedule a reminder notification.", permissions = listOf("SCHEDULE_EXACT_ALARM")),
        ToolMeta("write_note", "Save a note."),
        ToolMeta("list_notes", "List saved notes."),
        ToolMeta("take_photo", "Capture a photo and look at it.",
            permissions = listOf("CAMERA"), needsVision = true),
        ToolMeta("listen_microphone", "Transcribe speech from the mic.", permissions = listOf("RECORD_AUDIO")),
        ToolMeta("remember", "Store a durable fact about the user."),
        ToolMeta("forget", "Delete stored facts matching a query.", reversible = false),
        ToolMeta("recall", "Search stored long-term memory."),
        ToolMeta("web_search", "Search the web and read results for current information.",
            commonFailures = listOf("no network", "search unavailable")),
        ToolMeta("device_status", "Battery, connectivity and volume state."),
        ToolMeta("clipboard", "Read or write the clipboard."),
        ToolMeta("list_files", "List files in app-accessible storage."),
        ToolMeta("read_file", "Read a text file."),
        ToolMeta("write_file", "Create or overwrite a text file.", reversible = false),
    ).associateBy { it.name }

    fun needsAccessibility(tool: String) = meta[tool]?.needsAccessibility == true
    fun needsVision(tool: String) = meta[tool]?.needsVision == true
    fun requiresConfirmation(tool: String) = meta[tool]?.requiresConfirmation == true
}
