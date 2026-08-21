package com.lain.assistant.agent

/**
 * What the user asked for, when that can be worked out without a language model.
 *
 * Each variant maps to something Android answers directly. Anything the router
 * isn't confident about is absent from this list and becomes [Route.Model].
 */
sealed class LocalIntent {
    /** "what time is it", "what's the date" */
    data class Clock(val wantsDate: Boolean) : LocalIntent()

    /** "what's my battery", "am I charging" */
    object Battery : LocalIntent()

    /** "open youtube", "launch spotify" */
    data class OpenApp(val appName: String) : LocalIntent()

    /** "close this", "go home", "go back" */
    data class Navigate(val key: String) : LocalIntent()

    /** "set a timer for 10 minutes", "remind me in an hour to stretch" */
    data class Timer(val minutes: Int, val label: String) : LocalIntent()

    /** "open wifi settings", "open bluetooth settings" */
    data class SettingsPage(val page: String) : LocalIntent()

    /** "call John", "ring mum" */
    data class Call(val contact: String) : LocalIntent()

    /** "volume up", "set volume to 40" */
    data class Volume(val percent: Int?, val direction: Int) : LocalIntent()

    /** "torch on", "turn off the flashlight" */
    data class Torch(val on: Boolean) : LocalIntent()

    /** "what's on screen", used constantly in eyes-free operation */
    object ReadScreen : LocalIntent()

    /** "wifi on/off" and friends, which Android only lets an app deep-link to. */
    data class ToggleRequest(val page: String, val what: String) : LocalIntent()

    /** "pause", "skip", "next track" — routed to whichever app owns the media session. */
    data class Transport(val action: TransportAction) : LocalIntent()

    /**
     * "play Burna Boy on Spotify", "play something", "put some music on".
     * [query] blank means "just start playing".
     */
    data class PlayMusic(val query: String, val app: String?) : LocalIntent()

    /** "what's 15% of 240", "convert 5km to miles" — answered exactly, offline. */
    data class Calculate(val result: Calculator.Result) : LocalIntent()
}

enum class TransportAction { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, STOP }

/** Where a message should go. */
sealed class Route {
    /** Android can answer this outright. Zero model calls, works with no internet. */
    data class Local(val intent: LocalIntent) : Route()

    /** Ordinary conversation: one model call, no tools. */
    object Chat : Route()

    /** Needs the model, and probably tools. */
    object Model : Route()
}

/**
 * A deterministic, offline-first parser that answers the question "does this need
 * a language model at all?"
 *
 * The principle it enforces: Lain should not spend a network round trip — let
 * alone two — on something the phone already knows. "What's my battery?" was
 * costing a request to decide to call `device_status`, then a second request to
 * turn the result into a sentence. BatteryManager answers it in microseconds.
 * Against a free model, that difference is roughly four seconds versus none.
 *
 * Three properties matter more than coverage here:
 *
 *  - **Fast.** Plain string work over a normalised copy of the input. No
 *    allocation-heavy backtracking, no reflection, no I/O. It runs on the calling
 *    thread before anything else happens.
 *  - **Offline.** Every branch resolves to a platform API, so these commands keep
 *    working with no signal — which is when a phone assistant is most annoying to
 *    be without.
 *  - **Conservative.** A wrong local answer is far worse than a slow model
 *    answer, so anything carrying conversational hedging, a question about *why*
 *    or *how*, or trailing clauses the parser can't account for is handed to the
 *    model. Under-matching costs latency; over-matching costs correctness.
 */
object FastRouter {

    /**
     * Words that turn an apparent command into a conversation about the command.
     * "Open Spotify" is an instruction; "should I open Spotify" is a question, and
     * "why won't Spotify open" is a support request. Both need the model.
     */
    private val conversational = Regex(
        "\\b(why|how come|should i|could you explain|what do you think|do you think|" +
            "explain|compare|difference between|help me understand|what happens if|is it (better|worth)|" +
            "instead of|rather than|any idea|not sure|wondering)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Polite wrappers that don't change the instruction, stripped before matching. */
    private val politePrefix = Regex(
        "^(hey |hi |ok |okay |please |can you |could you |would you |will you |i want you to |" +
            "i need you to |lain,? |go ahead and |just )+",
        RegexOption.IGNORE_CASE
    )

    private val politeSuffix = Regex(
        "( please| for me| now| thanks| thank you| pls| mate| man)+[.!?]*$",
        RegexOption.IGNORE_CASE
    )

    fun route(message: String): Route {
        val normalised = normalise(message)
        if (normalised.isEmpty()) return Route.Chat

        // A question about the mechanism is never a command to perform it.
        if (conversational.containsMatchIn(normalised)) return Route.Model

        localIntent(normalised)?.let { return Route.Local(it) }

        // No local match: fall back to the existing coarse split so plain conversation
        // still skips the tool surface.
        return when (IntentClassifier.classify(message)) {
            TurnIntent.CHAT -> Route.Chat
            TurnIntent.ACT -> Route.Model
        }
    }

    private fun normalise(message: String): String = message
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .replace(politePrefix, "")
        .replace(politeSuffix, "")
        .trim()
        .trimEnd('.', '!', '?')

    // ------------------------------------------------------------------ intents

    private fun localIntent(t: String): LocalIntent? =
        clock(t) ?: battery(t) ?: torch(t) ?: volume(t) ?: timer(t)
            ?: navigate(t) ?: readScreen(t) ?: settingsOrToggle(t) ?: call(t)
            // Music is checked before the generic app launcher so "play spotify" is
            // understood as playback rather than as opening an app called "spotify".
            ?: transport(t) ?: playMusic(t)
            // Arithmetic is checked late: it only matches strings that are
            // unambiguously expressions, so it can't shadow a real command.
            ?: calculate(t)
            ?: openApp(t)

    private fun clock(t: String): LocalIntent? = when {
        t.matches(Regex("(what('s| is) the )?time( is it)?")) ||
            t.matches(Regex("what time is it( (right )?now)?")) ||
            t == "time" || t == "current time" -> LocalIntent.Clock(wantsDate = false)

        t.matches(Regex("(what('s| is) )?(the )?(today's )?date( is it)?( today)?")) ||
            t.matches(Regex("what day is it( today)?")) ||
            t == "what's today" || t == "today's date" -> LocalIntent.Clock(wantsDate = true)

        else -> null
    }

    private fun battery(t: String): LocalIntent? {
        if (!t.contains("battery") && !t.contains("charging") && !t.contains("charge level")) return null
        // "battery saver settings" is a settings request, not a status read.
        if (t.contains("setting") || t.contains("saver")) return null
        return LocalIntent.Battery
    }

    private fun torch(t: String): LocalIntent? {
        if (!t.contains("torch") && !t.contains("flashlight") && !t.contains("flash light")) return null
        val off = t.contains("off") || t.contains("kill") || t.contains("stop") || t.contains("disable")
        return LocalIntent.Torch(on = !off)
    }

    private fun volume(t: String): LocalIntent? {
        if (!t.contains("volume") && !t.contains("sound")) return null
        if (t.contains("setting")) return null

        Regex("(\\d{1,3})\\s*(%|percent)?").find(t)?.let { m ->
            val value = m.groupValues[1].toIntOrNull()
            if (value != null && value in 0..100 &&
                (t.contains("set") || t.contains("to ") || t.contains("%") || t.contains("percent"))
            ) {
                return LocalIntent.Volume(percent = value, direction = 0)
            }
        }
        return when {
            t.contains("mute") || t.contains("silence") || t.contains("silent") ->
                LocalIntent.Volume(percent = 0, direction = 0)
            t.contains("max") || t.contains("full") || t.contains("loudest") ->
                LocalIntent.Volume(percent = 100, direction = 0)
            t.contains("up") || t.contains("increase") || t.contains("louder") || t.contains("raise") ->
                LocalIntent.Volume(percent = null, direction = 1)
            t.contains("down") || t.contains("decrease") || t.contains("quieter") || t.contains("lower") ->
                LocalIntent.Volume(percent = null, direction = -1)
            else -> null
        }
    }

    /** "set a timer for 10 minutes", "remind me in 2 hours to call the bank". */
    private fun timer(t: String): LocalIntent? {
        val isTimer = t.startsWith("set a timer") || t.startsWith("set timer") ||
            t.startsWith("timer for") || t.startsWith("remind me") || t.startsWith("set an alarm for") ||
            t.startsWith("wake me in")
        if (!isTimer) return null

        val match = Regex("(\\d+)\\s*(sec|second|seconds|min|minute|minutes|hour|hours|hr|hrs)\\b").find(t)
            ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        val minutes = when {
            match.groupValues[2].startsWith("sec") -> 1 // the alarm API's floor is a minute
            match.groupValues[2].startsWith("h") -> amount * 60
            else -> amount
        }
        if (minutes !in 1..(60 * 24)) return null

        // Everything after "to ..." is what the reminder is about.
        val label = Regex("\\bto\\s+(.+)$").find(t)?.groupValues?.get(1)?.trim()
            ?: Regex("\\babout\\s+(.+)$").find(t)?.groupValues?.get(1)?.trim()
            ?: ""
        return LocalIntent.Timer(minutes = minutes, label = label)
    }

    private fun navigate(t: String): LocalIntent? = when (t) {
        "go home", "home screen", "go to home" -> LocalIntent.Navigate("home")
        "go back", "back", "press back" -> LocalIntent.Navigate("back")
        "recents", "recent apps", "show recents" -> LocalIntent.Navigate("recents")
        "notifications", "show notifications", "open notifications" -> LocalIntent.Navigate("notifications")
        else -> null
    }

    private fun readScreen(t: String): LocalIntent? = when (t) {
        "what's on screen", "what is on screen", "what's on the screen", "read the screen",
        "read screen", "what do you see", "what's on my screen" -> LocalIntent.ReadScreen
        else -> null
    }

    /** Settings pages Lain can deep-link to, keyed by the words people actually use. */
    private val settingsPages = mapOf(
        "wifi" to "wifi", "wi-fi" to "wifi", "internet" to "wifi",
        "bluetooth" to "bluetooth",
        "display" to "display", "brightness" to "display", "screen timeout" to "display",
        "sound" to "sound", "volume settings" to "sound",
        "battery" to "battery",
        "apps" to "apps", "applications" to "apps",
        "accessibility" to "accessibility",
        "location" to "location", "gps" to "location",
        "storage" to "storage",
        "security" to "security",
        "keyboard" to "keyboard",
        "notification" to "notifications",
        "date" to "date", "time settings" to "date",
        "airplane" to "wireless", "aeroplane" to "wireless", "flight mode" to "wireless",
        "mobile data" to "wireless", "data" to "wireless", "hotspot" to "wireless"
    )

    private fun settingsOrToggle(t: String): LocalIntent? {
        val mentionsSettings = t.contains("settings")
        val toggling = Regex("\\b(turn|switch|enable|disable|toggle)\\b").containsMatchIn(t)
        if (!mentionsSettings && !toggling) return null

        val hit = settingsPages.entries
            // Longest key first so "time settings" wins over "time".
            .sortedByDescending { it.key.length }
            .firstOrNull { t.contains(it.key) }
            ?: return null

        return if (mentionsSettings && !toggling) {
            LocalIntent.SettingsPage(hit.value)
        } else {
            // Android forbids a normal app flipping Wi-Fi, Bluetooth or aeroplane mode
            // itself — that was locked down in Android 10 precisely to stop apps doing
            // this. The honest, fast answer is to land the user on the right screen and
            // say so, rather than spending two model calls to discover the same wall.
            LocalIntent.ToggleRequest(page = hit.value, what = hit.key)
        }
    }

    // ---------------------------------------------------------------- music

    /**
     * Transport controls. These go to whichever app owns the media session, so they
     * work regardless of which player is running and without any network.
     */
    private fun transport(t: String): LocalIntent? {
        val action = when (t) {
            "pause", "pause music", "pause the music", "pause it", "pause playback",
            "stop the music", "stop music" -> TransportAction.PAUSE

            "resume", "resume music", "unpause", "continue playing", "keep playing",
            "play", "play music", "play it", "carry on" -> TransportAction.PLAY

            "next", "skip", "next song", "next track", "skip song", "skip track",
            "skip this", "next one" -> TransportAction.NEXT

            "previous", "previous song", "previous track", "go back a song",
            "last song", "back a track", "replay that" -> TransportAction.PREVIOUS

            "stop", "stop playing", "stop playback" -> TransportAction.STOP

            "toggle playback", "play pause" -> TransportAction.TOGGLE

            else -> return null
        }
        return LocalIntent.Transport(action)
    }

    private val musicApps = listOf(
        "spotify", "youtube music", "yt music", "youtube", "soundcloud",
        "deezer", "tidal", "apple music", "audiomack", "boomplay"
    )

    /** "play <something> [on <player>]", and the vaguer "put some music on". */
    private fun playMusic(t: String): LocalIntent? {
        // Bare "some music"/"a song" with no title: let the player choose.
        if (Regex("^(play|put on|put)\\s+(some\\s+)?(music|songs?|tunes|something)( on)?$").matches(t)) {
            return LocalIntent.PlayMusic(query = "", app = null)
        }
        Regex("^(?:play|put on)\\s+(?:some\\s+)?(?:music|songs?|something)\\s+on\\s+(.+)$").find(t)?.let { m ->
            val app = musicApps.firstOrNull { m.groupValues[1].contains(it) } ?: return@let
            return LocalIntent.PlayMusic(query = "", app = app)
        }
        // "play spotify" reads as "start Spotify playing", not "open the app".
        Regex("^(?:play|open up)\\s+(${musicApps.joinToString("|")})$").find(t)?.let { m ->
            return LocalIntent.PlayMusic(query = "", app = m.groupValues[1])
        }

        val m = Regex("^play\\s+(.{2,80})$").find(t) ?: return null
        var rest = m.groupValues[1].trim()

        // Compound instructions belong to the agent loop.
        if (rest.contains(" and then ") || rest.contains(" then ")) return null

        // "... on spotify" names the player; anything else after "on" is part of the
        // title ("Live on Broadway"), so only a known player counts.
        var app: String? = null
        Regex("^(.*)\\s+(?:on|in|using|through|with)\\s+([a-z ]+)$").find(rest)?.let { split ->
            val candidate = split.groupValues[2].trim().removeSuffix(" app").trim()
            val known = musicApps.firstOrNull { it == candidate || candidate == "$it music" }
            if (known != null) {
                app = known
                rest = split.groupValues[1].trim()
            }
        }

        // "play the next one", "play it again" are transport, not search.
        if (rest in setOf("it", "that", "this", "again", "it again", "that again", "the next one")) return null
        if (rest.isBlank()) return null

        rest = rest.removePrefix("the song ").removePrefix("song ")
            .removePrefix("the album ").removePrefix("album ")
            .removePrefix("me ").trim()
        if (rest.isBlank()) return LocalIntent.PlayMusic(query = "", app = app)

        return LocalIntent.PlayMusic(query = rest, app = app)
    }

    // ----------------------------------------------------------- arithmetic

    private fun calculate(t: String): LocalIntent? =
        Calculator.evaluate(t)?.let { LocalIntent.Calculate(it) }

    private fun call(t: String): LocalIntent? {
        val m = Regex("^(call|ring|phone|dial)\\s+(.{2,40})$").find(t) ?: return null
        val target = m.groupValues[2].trim()
        // "call it a day", "call back later" — verbs of speech, not of telephony.
        if (target.startsWith("it ") || target.startsWith("back") || target.startsWith("me ")) return null
        // A second instruction after the name means this is a multi-step job.
        if (target.contains(" and ") || target.contains(" then ")) return null
        return LocalIntent.Call(target)
    }

    private fun openApp(t: String): LocalIntent? {
        val m = Regex("^(open|launch|start|run|fire up|bring up)\\s+(.{2,40})$").find(t) ?: return null
        var target = m.groupValues[2].trim().removePrefix("the ").removePrefix("my ")
        if (target.endsWith(" app")) target = target.removeSuffix(" app").trim()

        // "open settings" alone is the settings root, handled above only when a specific
        // page was named; bare "settings" still belongs here.
        if (target.isBlank()) return null
        // Compound instructions ("open whatsapp and text ade") need the agent loop.
        if (target.contains(" and ") || target.contains(" then ") || target.contains(" to ")) return null
        // A URL is a browser job, not an app launch.
        if (target.contains("://") || target.contains(".com") || target.contains("www.")) return null
        return LocalIntent.OpenApp(target)
    }
}
