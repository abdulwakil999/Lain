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
        ToolMeta("list_apps", "List what is actually installed, so absence is checked rather than assumed."),
        // No longer accessibility-only: a background app is reaped through
        // ActivityManager, which needs no service at all.
        ToolMeta("close_app", "Close an app, from the foreground or the background.",
            permissions = listOf("KILL_BACKGROUND_PROCESSES"), reversible = true,
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
        ToolMeta("message_contact", "Resolve a contact, send them a message, and confirm it went — one call.",
            permissions = listOf("READ_CONTACTS", "SEND_SMS"), requiresConfirmation = true, reversible = false,
            commonFailures = listOf("no matching contact", "WhatsApp send control not found")),
        ToolMeta("send_whatsapp_message", "Open a WhatsApp chat with text prefilled (does not send).",
            reversible = true, commonFailures = listOf("WhatsApp not installed")),
        ToolMeta("set_reminder", "Schedule a reminder notification.", permissions = listOf("SCHEDULE_EXACT_ALARM")),
        ToolMeta("schedule_task", "Set an alarm, reminder or recurring task.",
            permissions = listOf("SCHEDULE_EXACT_ALARM"),
            commonFailures = listOf("time couldn't be parsed", "exact alarms not permitted")),
        ToolMeta("list_scheduled_tasks", "List alarms and scheduled tasks."),
        ToolMeta("cancel_scheduled_task", "Cancel a scheduled task.", reversible = false),
        ToolMeta("set_do_not_disturb", "Turn Do Not Disturb on or off, in-app.",
            permissions = listOf("ACCESS_NOTIFICATION_POLICY"),
            commonFailures = listOf("notification policy access not granted")),
        ToolMeta("set_ringer_mode", "Set silent, vibrate or normal, in-app.",
            permissions = listOf("ACCESS_NOTIFICATION_POLICY")),
        ToolMeta("open_quick_toggle", "Show the system wifi/data/bluetooth switch over Lain."),
        ToolMeta("set_preferred_sim", "Remember which SIM to use for calls and texts.",
            permissions = listOf("READ_PHONE_STATE")),
        ToolMeta("recite_quran", "Play or stop Qur'an recitation in-app.",
            commonFailures = listOf("no connection", "surah name not recognised")),
        ToolMeta("where_am_i", "Report roughly where the phone is.",
            permissions = listOf("ACCESS_COARSE_LOCATION"),
            commonFailures = listOf("permission not granted", "no recent fix")),
        ToolMeta("search_in_app", "Open an app and search inside it, in one call.",
            commonFailures = listOf("search box not found", "app not installed")),
        ToolMeta("set_system_toggle", "Turn wifi/bluetooth/data/location on or off.",
            needsAccessibility = true, reversible = true,
            commonFailures = listOf("tile not on the first page", "OEM renamed the tile")),
        ToolMeta("clear_recent_apps", "Clear the recent apps list.", needsAccessibility = true,
            reversible = false),
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
        ToolMeta("delete_file", "Delete a file from app storage.",
            requiresConfirmation = true, reversible = false,
            commonFailures = listOf("folder not empty", "no such file")),
        ToolMeta("make_folder", "Create a folder in app storage."),
        ToolMeta("find_files", "Search app storage for a file by name."),
        ToolMeta("read_notifications", "Read the current notification shade.",
            permissions = listOf("BIND_NOTIFICATION_LISTENER_SERVICE"),
            commonFailures = listOf("notification access not granted")),
        ToolMeta("edit_memory", "Correct a stored fact in place.", reversible = false),
        ToolMeta("read_calendar", "Read upcoming events from the phone's calendar.",
            permissions = listOf("READ_CALENDAR"),
            commonFailures = listOf("calendar permission not granted")),
        ToolMeta("add_calendar_event", "Add an event to the calendar.",
            permissions = listOf("WRITE_CALENDAR"), reversible = false,
            commonFailures = listOf("calendar permission not granted", "no writable calendar")),
        ToolMeta("compose_email", "Open a written email for the user to send."),
        // Public and effectively permanent — a deleted post has still been seen.
        ToolMeta("post_to_reddit", "Submit a post to a subreddit.",
            requiresConfirmation = true, reversible = false,
            commonFailures = listOf("credentials not set", "subreddit rules refused it")),
        ToolMeta("post_to_discord", "Send a message to a Discord channel.",
            requiresConfirmation = true, reversible = false,
            commonFailures = listOf("webhook or bot token not set", "bot not in that channel")),
    ).associateBy { it.name }

    fun needsAccessibility(tool: String) = meta[tool]?.needsAccessibility == true
    fun needsVision(tool: String) = meta[tool]?.needsVision == true
    /**
     * Matches a scheduled task that will reach another person unattended.
     *
     * schedule_task is mostly harmless — an alarm, a reminder to take tablets — so
     * gating every use behind a prompt would make setting an alarm a two-step
     * conversation. Only the outward-facing forms need approval, and only the SMS
     * form actually fires on its own: a scheduled call rings and waits for a tap.
     */
    private val OUTWARD_SCHEDULE = Regex("\"action\"\\s*:\\s*\"(sms|call)\"", RegexOption.IGNORE_CASE)

    fun requiresConfirmation(tool: String, argumentsJson: String = ""): Boolean {
        if (meta[tool]?.requiresConfirmation == true) return true
        return tool == "schedule_task" && OUTWARD_SCHEDULE.containsMatchIn(argumentsJson)
    }
}
