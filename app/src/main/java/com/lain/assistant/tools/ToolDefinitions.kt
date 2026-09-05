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
            },
            briefDescription = "Open an installed app by name."
        ),
        ToolDefinition(
            name = "close_app",
            description = "Close an app. Dismisses it from Recents if it's on screen, or stops its background processes if it isn't. Leave app_name blank for whatever is in the foreground.",
            parameters = schema {
                property("app_name", "string", "Display name of the app to close")
                required("app_name")
            },
            briefDescription = "Close the app in the foreground."
        ),
        ToolDefinition(
            name = "open_url",
            description = "Open a web address in the browser FOR THE USER TO SEE. This does not give you the page contents — use web_search or fetch_page if you need to read it yourself.",
            parameters = schema {
                property("url", "string", "Full URL including https://")
                required("url")
            },
            briefDescription = "Open a web page in the browser for the user to see."
        ),
        ToolDefinition(
            name = "open_settings_page",
            description = "Open a specific Android Settings screen (wifi, bluetooth, display, sound, battery, apps, accessibility, location, storage, security, date, keyboard, notifications).",
            parameters = schema {
                property("page", "string", "Which settings screen")
                required("page")
            },
            briefDescription = "Open an Android settings screen (wifi, bluetooth, display, sound, battery, apps, accessibility, location, storage, security, date, keyboard, notifications)."
        ),
        ToolDefinition(
            name = "current_app",
            description = "Report which app is currently in the foreground. Use this to confirm an app actually opened before acting on it.",
            parameters = schema { },
            briefDescription = "Which app is in the foreground."
        ),

        // ------------------------------------------------- web research
        ToolDefinition(
            name = "web_search",
            description = "Search the web and read the top pages. Use whenever the answer depends on current information — news, prices, releases, live documentation, APIs, anything that may have changed since your training. Returns extracts with URLs; cite them. Do NOT use for casual conversation or things you already know.",
            parameters = schema {
                property("query", "string", "What to search for")
                required("query")
            },
            briefDescription = "Search the web for current information and read the top results."
        ),
        ToolDefinition(
            name = "fetch_page",
            description = "Read the text of a specific web page yourself.",
            parameters = schema {
                property("url", "string", "Page URL")
                required("url")
            },
            briefDescription = "Read the text of a web page."
        ),

        // ---------------------------------------------------- device
        ToolDefinition(
            name = "device_status",
            description = "Battery level and charging state, network connectivity, media volume, Android version and device model.",
            parameters = schema { },
            briefDescription = "Battery, network, volume and device model."
        ),
        ToolDefinition(
            name = "set_volume",
            description = "Set one of the phone's volumes as a percentage. The streams are separate: \"media\" for music and video, \"ring\" for the ringtone, \"notification\", \"alarm\", \"call\". Defaults to media. Do Not Disturb can refuse a ringer change, and the result says so when it does.",
            parameters = schema {
                property("percent", "number", "0-100")
                property("stream", "string", "media, ring, notification, alarm or call. Defaults to media.")
                required("percent")
            },
            briefDescription = "Set the media, ring, notification, alarm or call volume 0-100."
        ),
        ToolDefinition(
            name = "set_brightness",
            description = "Set screen brightness as a percentage, or pass auto=true to hand it back to the light sensor. Needs Android's \"Modify system settings\" permission; if it isn't granted the tool opens that screen and says so rather than failing quietly.",
            parameters = schema {
                property("percent", "number", "0-100. Ignored when auto is true.")
                property("auto", "boolean", "true to switch brightness back to automatic")
            },
            briefDescription = "Set screen brightness 0-100, or back to automatic."
        ),
        ToolDefinition(
            name = "clipboard",
            description = "Read the clipboard, or write to it. Reading only works while Lain is the focused app — an Android restriction, not a bug.",
            parameters = schema {
                property("action", "string", "\"read\" or \"write\"")
                property("text", "string", "Text to copy, when writing")
                required("action")
            },
            briefDescription = "Read or write the clipboard."
        ),
        ToolDefinition(
            name = "open_contacts",
            description = "Open the Contacts app.",
            parameters = schema { },
            briefDescription = "Open the Contacts app."
        ),

        // ----------------------------------------------------- files
        ToolDefinition(
            name = "list_files",
            description = "List files in Lain's storage folder. Android sandboxes apps, so this is Lain's own directory rather than the whole device.",
            parameters = schema {
                property("path", "string", "Sub-folder, or empty for the root")
            },
            briefDescription = "List files in Lain's storage folder."
        ),
        ToolDefinition(
            name = "read_file",
            description = "Read a text file from Lain's storage.",
            parameters = schema {
                property("path", "string", "File path")
                required("path")
            },
            briefDescription = "Read a text file from Lain's storage."
        ),
        ToolDefinition(
            name = "write_file",
            description = "Create or overwrite a text file in Lain's storage.",
            parameters = schema {
                property("path", "string", "File path")
                property("content", "string", "File contents")
                property("append", "boolean", "true to append instead of overwrite")
                required("path", "content")
            },
            briefDescription = "Write a text file in Lain's storage."
        ),
        ToolDefinition(
            name = "rename_file",
            description = "Rename or move a file within Lain's storage.",
            parameters = schema {
                property("from", "string", "Existing path")
                property("to", "string", "New path")
                required("from", "to")
            },
            briefDescription = "Rename or move a file in Lain's storage."
        ),

        ToolDefinition(
            name = "delete_file",
            description = "Delete a file from Lain's storage. Irreversible — the user is asked to confirm before it happens. Won't delete a folder that still has things in it.",
            parameters = schema {
                property("path", "string", "File path")
                required("path")
            },
            briefDescription = "Delete a file from Lain's storage. Asks the user first."
        ),
        ToolDefinition(
            name = "make_folder",
            description = "Create a folder in Lain's storage.",
            parameters = schema {
                property("path", "string", "Folder path")
                required("path")
            },
            briefDescription = "Create a folder in Lain's storage."
        ),
        ToolDefinition(
            name = "find_files",
            description = "Search Lain's storage for files whose name contains something. Use this to locate a document before reading or opening it, instead of guessing paths.",
            parameters = schema {
                property("query", "string", "Part of the filename to look for")
                required("query")
            },
            briefDescription = "Find files by name in Lain's storage."
        ),

        // ----------------------------------------------- notifications
        ToolDefinition(
            name = "read_notifications",
            description = "Read what's currently in the notification shade — app, title and text. Use for \"what did I miss\" or \"any messages\". Requires notification access, which only the user can grant in Android Settings; if it's off, say so rather than guessing.",
            parameters = schema { },
            briefDescription = "Read the current notifications."
        ),

        // ------------------------------------------------- screen perception
        ToolDefinition(
            name = "read_screen",
            description = "Read what's on screen right now as a list of labelled elements with tap coordinates, each marked [INPUT] (text field), [BUTTON] (tappable) or [text]. NOTE: every action tool already returns the updated screen for you, so you do NOT need to call this after tapping, typing or opening something — only use it to look before acting.",
            parameters = schema { },
            briefDescription = "List what's on screen with tap coordinates. Action tools already return this."
        ),
        ToolDefinition(
            name = "look_at_screen",
            description = "Take a screenshot and visually look at it. Use when layout, images, colours or a game board matter more than text labels — read_screen cannot see those.",
            parameters = schema { },
            briefDescription = "Screenshot the screen and look at it."
        ),

        // ----------------------------------------------------- screen control
        ToolDefinition(
            name = "tap_text",
            description = "Tap the on-screen element whose label matches the given text. Prefer this over tap_screen — it's far more reliable than guessing coordinates.",
            parameters = schema {
                property("text", "string", "Visible label of the thing to tap, e.g. \"Search\", \"Send\", a contact's name")
                required("text")
            },
            briefDescription = "Tap the on-screen element with this label."
        ),
        ToolDefinition(
            name = "tap_screen",
            description = "Tap an exact pixel coordinate. Only use when tap_text can't identify the target; get coordinates from read_screen first.",
            parameters = schema {
                property("x", "number", "X coordinate in pixels")
                property("y", "number", "Y coordinate in pixels")
                required("x", "y")
            },
            briefDescription = "Tap an x,y pixel coordinate."
        ),
        ToolDefinition(
            name = "type_text",
            description = "Type text into the currently focused text field. Tap the field first (tap_text) so it has focus, then call this. Set submit=true to press the keyboard's send/search key in the same step — do that whenever the text is meant to be submitted, it saves a whole round trip.",
            parameters = schema {
                property("text", "string", "The text to type")
                property("submit", "boolean", "true to submit (send/search/go) immediately after typing")
                required("text")
            },
            briefDescription = "Type into the focused field. submit=true also presses send/search."
        ),
        ToolDefinition(
            name = "press_key",
            description = "Press a system navigation key.",
            parameters = schema {
                property("key", "string", "One of: back, home, recents, notifications, enter")
                required("key")
            },
            briefDescription = "Press back, home, recents, notifications or enter."
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
            },
            briefDescription = "Swipe from one point to another to scroll."
        ),
        ToolDefinition(
            name = "wait",
            description = "Pause briefly to let the screen finish loading or an animation settle, then continue. Use after opening an app or tapping something slow.",
            parameters = schema {
                property("seconds", "number", "How long to wait, 1-5 seconds")
                required("seconds")
            },
            briefDescription = "Pause for the screen to finish loading."
        ),

        // ------------------------------------------------------- communication
        ToolDefinition(
            name = "message_contact",
            description = "Send a message to someone, start to finish, in ONE call. Resolves the contact name to a number, opens the app if it needs one, types the message, sends it, and confirms it actually went. This is the right tool for \"text Ade\", \"message mum on WhatsApp\", \"tell Sam I'm running late\" — do NOT drive that by hand with open_app/tap_text/type_text, it is many times slower and less reliable.",
            parameters = schema {
                property("contact", "string", "Contact name or phone number")
                property("message", "string", "What to say")
                property("app", "string", "\"whatsapp\" or \"sms\". Leave empty for SMS, which sends without opening anything.")
                required("contact", "message")
            },
            briefDescription = "Send someone a message end to end, in one call. Use this for any \"text/message X\" request."
        ),
        ToolDefinition(
            name = "send_sms",
            description = "Send a text message via SMS directly (does not open any app).",
            parameters = schema {
                property("phone_number", "string", "Recipient's phone number")
                property("message", "string", "Message body")
                required("phone_number", "message")
            },
            briefDescription = "Send an SMS directly."
        ),
        ToolDefinition(
            name = "make_call",
            description = "Place a phone call.",
            parameters = schema {
                property("phone_number", "string", "Number to call")
                required("phone_number")
            },
            briefDescription = "Call a phone number."
        ),
        ToolDefinition(
            name = "lookup_contact",
            description = "Look up a saved contact's phone number by name. Use this before make_call or send_sms when the user names a person instead of a number.",
            parameters = schema {
                property("name", "string", "Contact name to search for")
                required("name")
            },
            briefDescription = "Find a contact's number by name."
        ),
        ToolDefinition(
            name = "send_whatsapp_message",
            description = "Open a WhatsApp chat with a message pre-filled. It does NOT send by itself — after calling this, use read_screen then tap_text(\"Send\") to actually send it.",
            parameters = schema {
                property("phone_number", "string", "Recipient's phone number, with country code")
                property("message", "string", "Message text")
                required("phone_number", "message")
            },
            briefDescription = "Open a WhatsApp chat with text prefilled. Does not send."
        ),

        // ----------------------------------------------------------- utility
        ToolDefinition(
            name = "set_reminder",
            description = "Schedule a reminder notification.",
            parameters = schema {
                property("text", "string", "What to remind the user about")
                property("minutes_from_now", "number", "How many minutes from now to trigger the reminder")
                required("text", "minutes_from_now")
            },
            briefDescription = "Schedule a reminder notification."
        ),
        ToolDefinition(
            name = "schedule_task",
            description = "Set an alarm, a reminder, or a recurring task at a clock time. Handles \"every day\", \"every weekday\", \"tomorrow\" and delays like \"in 20 minutes\" — pass the user's own wording in `when` and Lain resolves it. Use this rather than opening the Clock app: tasks set this way can be listed and cancelled afterwards.",
            parameters = schema {
                property("when", "string", "The timing in the user's words, e.g. \"7:30 am every weekday\", \"2:30\", \"in 20 minutes\"")
                property("action", "string", "remind (notification), alarm (rings with snooze), call (rings with a Call button), sms (sends a text), whatsapp (opens the chat with the message typed), open_app")
                property("label", "string", "What it's for, e.g. \"take the tablets\"")
                property("target", "string", "Contact name or number for call/sms; app name for open_app")
                property("message", "string", "The text body, for sms only")
                property("repeat", "string", "once, daily, weekdays, weekends or weekly — only if `when` doesn't already say")
                required("when")
            },
            briefDescription = "Set an alarm, reminder or recurring task."
        ),
        ToolDefinition(
            name = "unlock_device",
            description = "Report whether the phone is locked and whether it can be used from the lock screen. On a phone with no PIN or pattern the lock screen is only a swipe and Lain works from it. Where a credential is set, only the user can answer it — say so, never claim to have unlocked it.",
            parameters = schema { },
            briefDescription = "Say whether the phone is locked and whether it needs the user."
        ),
        ToolDefinition(
            name = "show_alarms",
            description = "Open the phone's alarm list. Use this for \"what alarms have I got\" — Android publishes no way to read alarms, so showing the real list is the only honest answer. Do not recite an alarm list from memory.",
            parameters = schema { },
            briefDescription = "Open the phone's alarm list."
        ),
        ToolDefinition(
            name = "list_scheduled_tasks",
            description = "List every alarm, reminder and recurring task Lain has set.",
            parameters = schema { },
            briefDescription = "List alarms and scheduled tasks."
        ),
        ToolDefinition(
            name = "cancel_scheduled_task",
            description = "Cancel a scheduled task by describing it, e.g. \"the 7am alarm\" or \"calling mama\".",
            parameters = schema {
                property("which", "string", "Words identifying the task to cancel")
                required("which")
            },
            briefDescription = "Cancel a scheduled task."
        ),
        ToolDefinition(
            name = "set_do_not_disturb",
            description = "Turn Do Not Disturb on or off. This happens inside Lain with no screen change.",
            parameters = schema {
                property("mode", "string", "off, priority (default when turning on), alarms, or silence")
                required("mode")
            },
            briefDescription = "Turn Do Not Disturb on or off."
        ),
        ToolDefinition(
            name = "set_ringer_mode",
            description = "Put the phone on silent, vibrate or normal. Happens inside Lain.",
            parameters = schema {
                property("mode", "string", "silent, vibrate or normal")
                required("mode")
            },
            briefDescription = "Set silent, vibrate or normal."
        ),
        ToolDefinition(
            name = "open_quick_toggle",
            description = "Bring up the system switch for wifi, mobile data, bluetooth or volume as a panel over Lain. Android has not allowed apps to flip these themselves since Android 10, so this is the real toggle rather than a fake one — say so when reporting back.",
            parameters = schema {
                property("what", "string", "wifi, mobile data, bluetooth, volume or nfc")
                required("what")
            },
            briefDescription = "Show the system wifi/data/bluetooth switch over Lain."
        ),
        ToolDefinition(
            name = "search_in_app",
            description = "Open an app and search inside it, in ONE call — this is the right tool for \"open chrome and search X\", \"search youtube for Y\", \"look up Z on spotify\". Do NOT do this by hand with open_app, tap_text and type_text; this handles the whole sequence and verifies it. Leave app_name blank for a plain web search.",
            parameters = schema {
                property("app_name", "string", "App to search in, e.g. \"Chrome\", \"YouTube\", \"Spotify\". Blank for a web search.")
                property("query", "string", "What to search for")
                required("query")
            },
            briefDescription = "Open an app and search inside it, in one call."
        ),
        ToolDefinition(
            name = "set_system_toggle",
            description = "Turn wifi, bluetooth, mobile data, hotspot, location, aeroplane mode, torch or auto-rotate on or off. Lain presses the Quick Settings tile and then reads the state back to confirm it actually changed.",
            parameters = schema {
                property("what", "string", "wifi, bluetooth, mobile data, hotspot, location, aeroplane mode, torch or auto-rotate")
                property("on", "boolean", "true to turn on, false to turn off")
                required("what", "on")
            },
            briefDescription = "Turn wifi/bluetooth/data/location etc on or off."
        ),
        ToolDefinition(
            name = "clear_recent_apps",
            description = "Clear the recent apps list, closing what's in the background.",
            parameters = schema { },
            briefDescription = "Clear recent apps."
        ),
        ToolDefinition(
            name = "recite_quran",
            description = "Play Qur'an recitation inside Lain. Accepts a surah name, meaning or number (1-114) — \"Al-Kahf\", \"the cave\", \"18\". Leave surah blank to stop what's playing.",
            parameters = schema {
                property("surah", "string", "Surah name, meaning or number. Blank to stop.")
                property("reciter", "string", "Optional: Alafasy, Sudais, Abdul Basit, Minshawi, Husary")
            },
            briefDescription = "Play or stop Qur'an recitation."
        ),
        ToolDefinition(
            name = "where_am_i",
            description = "Report roughly where the phone is, from its last known position. Reads only; never tracks and never transmits.",
            parameters = schema { },
            briefDescription = "Say roughly where the phone is."
        ),
        ToolDefinition(
            name = "set_preferred_sim",
            description = "Remember which SIM to use on a dual-SIM phone, e.g. \"the MTN one\" or \"SIM 2\". Texts then send from it without asking; for calls it is a hint the dialler usually follows.",
            parameters = schema {
                property("which", "string", "Carrier name or slot, e.g. \"MTN\", \"SIM 1\"")
                required("which")
            },
            briefDescription = "Remember which SIM to use."
        ),
        ToolDefinition(
            name = "write_note",
            description = "Save a note for the user.",
            parameters = schema {
                property("text", "string", "Note content")
                required("text")
            },
            briefDescription = "Save a note."
        ),
        ToolDefinition(
            name = "list_notes",
            description = "List the user's saved notes.",
            parameters = schema { },
            briefDescription = "List saved notes."
        ),
        ToolDefinition(
            name = "take_photo",
            description = "Capture a photo using the phone's camera and look at it.",
            parameters = schema {
                property("use_front_camera", "boolean", "true for the selfie camera, false for the rear camera")
            },
            briefDescription = "Take a photo and look at it."
        ),
        ToolDefinition(
            name = "listen_microphone",
            description = "Listen to the microphone and transcribe what's said.",
            parameters = schema { },
            briefDescription = "Listen and transcribe what's said."
        ),

        // ------------------------------------------------------------ memory
        ToolDefinition(
            name = "remember",
            description = "Permanently remember a fact about the user — who a nickname refers to, a preference, an app they use for something. Save these whenever you learn one, without being asked.",
            parameters = schema {
                property("key", "string", "Short identifier, e.g. \"mum's number\", \"favourite music app\"")
                property("value", "string", "The fact to remember")
                required("key", "value")
            },
            briefDescription = "Save a durable fact about the user."
        ),
        ToolDefinition(
            name = "edit_memory",
            description = "Correct a fact you already saved, keeping the same entry rather than adding a contradicting one. Use when the user corrects something specific. Call recall first to get the id.",
            parameters = schema {
                property("id", "string", "The memory's id, from recall")
                property("fact", "string", "The corrected fact")
                property("importance", "number", "1-5, if it should change")
                required("id", "fact")
            },
            briefDescription = "Correct a saved fact by id."
        ),
        ToolDefinition(
            name = "forget",
            description = "Delete remembered facts matching a query. Use when the user asks you to forget something.",
            parameters = schema {
                property("key", "string", "What to forget")
                required("key")
            },
            briefDescription = "Delete saved facts matching a query."
        ),
        // ------------------------------------------------------- calendar
        ToolDefinition(
            name = "read_calendar",
            description = "Read what's in the phone's calendar between two times. Reaches whatever the calendar app has already synced, so it works offline. days_ahead counts from now; use 1 for today and tomorrow.",
            parameters = schema {
                property("days_ahead", "number", "How many days forward to look. 1 = the next 24 hours.")
            },
            briefDescription = "Read upcoming calendar events."
        ),
        ToolDefinition(
            name = "add_calendar_event",
            description = "Put an event in the phone's calendar. Needs the calendar permission; without it the calendar app is opened with the event filled in for the user to save. Say which of the two happened.",
            parameters = schema {
                property("title", "string", "What the event is")
                property("when", "string", "When it starts, in plain words: \"tomorrow at 3pm\", \"friday 09:00\"")
                property("duration_minutes", "number", "How long, default 60")
                property("location", "string", "Where, optional")
                required("title", "when")
            },
            briefDescription = "Add an event to the calendar."
        ),

        // ---------------------------------------------------------- email
        ToolDefinition(
            name = "compose_email",
            description = "Open the user's email app with a message written and ready. You cannot send mail — the user presses send. Never claim it was sent.",
            parameters = schema {
                property("to", "string", "Address, or several separated by commas")
                property("subject", "string", "Subject line")
                property("body", "string", "The message")
                required("to", "body")
            },
            briefDescription = "Write an email and open it for the user to send."
        ),

        // ------------------------------------------------------- channels
        ToolDefinition(
            name = "post_to_reddit",
            description = "Submit a text post to a subreddit using the user's own Reddit credentials from Settings. Public and hard to undo, so it is confirmed first.",
            parameters = schema {
                property("subreddit", "string", "Subreddit name, with or without r/")
                property("title", "string", "Post title")
                property("body", "string", "Post body")
                required("subreddit", "title")
            },
            briefDescription = "Post to a subreddit."
        ),
        ToolDefinition(
            name = "post_to_discord",
            description = "Send a message to Discord through the user's webhook or bot token from Settings. Leave channel_id empty when a webhook is set — a webhook already names its channel.",
            parameters = schema {
                property("channel_id", "string", "Channel ID, only needed with a bot token")
                property("message", "string", "What to send")
                required("message")
            },
            briefDescription = "Send a message to Discord."
        ),

        ToolDefinition(
            name = "recall",
            description = "Search everything you hold about the user: remembered facts, their saved notes, and what was said in earlier conversations. Matches on meaning, so \"the flat\" finds a note about the apartment. Relevant memories are already provided each turn, so use this when you need something specific that wasn't included.",
            parameters = schema {
                property("query", "string", "What to look for")
                required("query")
            },
            briefDescription = "Search memories, notes and past conversations."
        )
    )

    /** Everything that is dead weight without a connected Accessibility Service. */
    private val accessibilityDependent = setOf(
        "read_screen", "look_at_screen", "tap_text", "tap_screen", "type_text",
        "press_key", "swipe_screen", "current_app", "close_app"
    )

    /**
     * The tools that carry the common cases, in the order they earn their place.
     *
     * A weak model chooses worse from thirty options than from fifteen — the list
     * itself is a reasoning task. This is the order the budget trims from the bottom
     * of, so what survives is what a short conversation actually needs.
     */
    private val byImportance = listOf(
        // Things that finish a whole job in one call.
        "message_contact", "search_in_app", "open_app", "web_search", "make_call", "schedule_task",
        "set_system_toggle", "close_app",
        // Driving a screen.
        "tap_text", "type_text", "read_screen", "press_key", "swipe_screen",
        // Knowing things.
        "lookup_contact", "device_status", "remember", "recall", "forget",
        "write_note", "list_notes", "open_url", "wait", "current_app",
        // Longer tail.
        "read_calendar", "add_calendar_event", "compose_email", "show_alarms", "unlock_device",
        "read_notifications", "find_files", "edit_memory",
        "post_to_reddit", "post_to_discord",
        "open_settings_page", "set_volume", "set_brightness", "clipboard", "fetch_page", "open_contacts",
        "tap_screen", "look_at_screen", "take_photo", "listen_microphone",
        "list_files", "read_file", "write_file", "rename_file", "make_folder", "delete_file",
        "close_app", "send_sms", "send_whatsapp_message"
    )

    /**
     * The tool surface for one request, shaped to the model that will see it.
     *
     * Nothing is removed from the application — every tool remains callable, and a
     * stronger model sees all of them. What changes is how many are put in front of
     * a model at once and how verbosely they're described, because those are the two
     * things that actually degrade a small model's tool use. The work the trimmed
     * tools would have done is largely handled locally now (see FastRouter), so the
     * short list is not a smaller Lain — it's the same Lain asking less of the model.
     */
    fun forCapabilities(
        caps: com.lain.assistant.data.ModelCapabilities,
        accessibilityReady: Boolean
    ): List<ToolDefinition> = shape(caps, accessibilityReady).tools

    /**
     * The tools that exist and work but were cut from this message to fit the budget.
     *
     * Worth knowing about, because the model cannot tell the difference between a
     * tool the app does not have and a tool it was not shown — and answers both the
     * same way, with "that isn't in my toolset". Asked to list what she could do, she
     * reported the clipboard and Do Not Disturb as missing capabilities. Both have
     * been implemented for months; both had been trimmed off the bottom of a free
     * model's list.
     *
     * Excludes anything gated on Accessibility or vision. Those absences have a
     * cause the user can act on, and they are explained separately rather than
     * lumped in with "didn't fit".
     */
    fun omittedByBudget(
        caps: com.lain.assistant.data.ModelCapabilities,
        accessibilityReady: Boolean
    ): List<String> = shape(caps, accessibilityReady).omittedByBudget

    private class Shaped(val tools: List<ToolDefinition>, val omittedByBudget: List<String>)

    private fun shape(
        caps: com.lain.assistant.data.ModelCapabilities,
        accessibilityReady: Boolean
    ): Shaped {
        var tools = all
        if (!caps.supportsVision) {
            tools = tools.filterNot { it.name == "look_at_screen" || it.name == "take_photo" }
        }
        if (!accessibilityReady) {
            // Offering screen tools while the service is off means the model spends
            // round trips discovering that each one fails.
            tools = tools.filterNot { it.name in accessibilityDependent }
        }

        val available = tools
        if (tools.size > caps.toolBudget) {
            val rank = byImportance.withIndex().associate { (i, name) -> name to i }
            tools = tools.sortedBy { rank[it.name] ?: Int.MAX_VALUE }.take(caps.toolBudget)
        }

        val kept = tools.map { it.name }.toSet()
        return Shaped(
            tools = tools.map { it.describedFor(caps.useCompactToolDescriptions) },
            omittedByBudget = available.map { it.name }.filterNot { it in kept }
        )
    }

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
