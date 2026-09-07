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

    /**
     * "volume up", "set volume to 40", "turn the ringer down".
     *
     * [stream] names which volume, because they are separate and getting it wrong is
     * how a phone ends up ringing at full volume in a meeting.
     */
    data class Volume(val percent: Int?, val direction: Int, val stream: String = "media") : LocalIntent()

    /** "dim the screen", "brightness to 70", "auto brightness". */
    data class Brightness(val percent: Int?, val direction: Int, val auto: Boolean = false) : LocalIntent()

    /** "torch on", "turn off the flashlight" */
    data class Torch(val on: Boolean) : LocalIntent()

    /** "what's on screen", used constantly in eyes-free operation */
    object ReadScreen : LocalIntent()

    /** "tap the send button", "press Continue" — the other half of eyes-free use. */
    data class TapText(val label: String) : LocalIntent()

    /** "wifi on/off" and friends, which Android only lets an app deep-link to. */
    data class ToggleRequest(val page: String, val what: String) : LocalIntent()

    /** "pause", "skip", "next track" — routed to whichever app owns the media session. */
    data class Transport(val action: TransportAction) : LocalIntent()

    /**
     * "play Burna Boy on Spotify", "play something", "put some music on".
     * [query] blank means "just start playing".
     */
    data class PlayMusic(val query: String, val app: String?) : LocalIntent()

    /**
     * An alarm, reminder or recurring task at a clock time. The phrase is kept
     * intact and resolved by [WhenParser] at execution, so the router stays free
     * of calendar arithmetic.
     */
    data class Schedule(val phrase: String, val alarm: Boolean) : LocalIntent()

    /**
     * Someone saying they are the developer. Answered with the challenge, never
     * with belief.
     */
    object DeveloperClaim : LocalIntent()

    /** Whatever they said next, read as their answer to the challenge. */
    data class DeveloperAnswer(val text: String) : LocalIntent()

    /** The phrase that gives the role up on this device, and its confirmation. */
    object StandDownRequest : LocalIntent()
    data class StandDownAnswer(val text: String) : LocalIntent()

    /** "what alarms have I got", "list my reminders". */
    object ListSchedule : LocalIntent()

    /** "show my alarms" — opens the clock app, the only place the real list exists. */
    object ShowAlarms : LocalIntent()

    /** "cancel my 7am alarm". */
    data class CancelSchedule(val which: String) : LocalIntent()

    /** Do Not Disturb. Genuinely flippable in-process, unlike Wi-Fi and Bluetooth. */
    data class Dnd(val mode: String) : LocalIntent()

    /** Silent / vibrate / normal. */
    data class Ringer(val mode: String) : LocalIntent()

    /**
     * "turn on wifi", "switch bluetooth off" — actually switched, via the user's own
     * Quick Settings tile, then verified against the system.
     */
    data class SystemToggle(val what: String, val on: Boolean) : LocalIntent()

    /** "open chrome and search animeheaven", "search youtube for X" — one flow, no model. */
    data class SearchIn(val app: String, val query: String) : LocalIntent()

    /** "close whatsapp", "close this app". */
    data class CloseApp(val appName: String) : LocalIntent()

    /** "clear recent apps", "close background apps". */
    object ClearRecents : LocalIntent()

    /**
     * "what's my name", "how old am I", "what's your name", "what app is this".
     *
     * The profile is already in the app's own database and the identity is a
     * constant. Sending either to a language model is a network round trip to be
     * told something Lain already knows — and on a free model it produced a numbered
     * plan about how to say a two-word name.
     */
    data class Identity(val question: IdentityQuestion) : LocalIntent()

    /** "turn off my screen", "lock the phone". */
    object LockScreen : LocalIntent()

    /** "restart", "shut down" — raises the system power menu, never acts alone. */
    data class Power(val restart: Boolean) : LocalIntent()

    /** "close yourself", "exit Lain". */
    object CloseSelf : LocalIntent()

    /**
     * Greetings, thanks, and the handful of exchanges that have one right answer.
     *
     * Not an attempt at conversation — anything with content still goes to the
     * model. This is the opening and closing of one, which is a fixed set, arrives
     * constantly, and was costing a network round trip each time.
     */
    data class SmallTalk(val kind: SmallTalkKind) : LocalIntent()

    /** "recite Al-Kahf", "play surah 18", "stop the recitation". */
    data class Recite(val surah: String, val reciter: String, val stop: Boolean) : LocalIntent()

    /** "where am I", "what's my location". */
    object WhereAmI : LocalIntent()

    /** "what's 15% of 240", "convert 5km to miles" — answered exactly, offline. */
    data class Calculate(val result: Calculator.Result) : LocalIntent()
}

enum class TransportAction { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, STOP }

/** Things Lain already knows without asking anybody. */
enum class IdentityQuestion {
    USER_NAME, USER_AGE, LAIN_NAME, APP_NAME, CAPABILITIES, NECIO,
    /** "who made you" — a fact about the world that no model has, so it is answered here. */
    DEVELOPER,

    /**
     * "Who is Professor Poopy Butthole" — the follow-up, not the name.
     *
     * A different question from [DEVELOPER] and it wants a different answer: someone
     * asking this has already been told the name and wants to know what it means.
     */
    MAKER,

    /** The sibling apps. Real products, so a model must not invent their features. */
    LEWA_CODER,
    LEWA_THERAPIST,
    LEWA_DEV,

    /** "are you named after the anime" — she isn't, and a model will say she is. */
    ANIME
}

enum class SmallTalkKind { GREETING, THANKS, HOW_ARE_YOU, GOODBYE, AFFIRMATION }

/** Where a message should go. */
sealed class Route {
    /** Android can answer this outright. Zero model calls, works with no internet. */
    data class Local(val intent: LocalIntent) : Route()

    /** Ordinary conversation: one model call, no tools. */
    object Chat : Route()

    /**
     * Code, or schoolwork. One model call, no tools, room to actually write.
     *
     * Separate from [Chat] because the budget is the difference between an answer
     * and a truncated one: a conversational reply is a couple of hundred tokens and
     * a working solution is not.
     */
    object Study : Route()

    /**
     * Being taught a skill. Answered on the device, with no model.
     *
     * Only teaching is a route. *Matching* a skill needs a database read, which a
     * router that must stay synchronous and allocation-light cannot do — so that
     * happens a layer up, in the engine, once nothing local has claimed the message.
     */
    data class LearnSkill(val name: String, val steps: String) : Route()

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

    /**
     * Work that needs the model to think and to write at length: code, and school.
     *
     * These are the two requests Lain was worst at, for the same reason — both were
     * classified as "act", which sent them into the full agent loop with the whole
     * toolbox attached, a 700-token step ceiling and instructions to be terse. A
     * request for a sorting function came back as four lines of prose about sorting,
     * truncated. Neither needs a single tool: nothing here touches the phone, it is
     * all generation, so the right path is one long, tool-free, deliberate call.
     *
     * Matched on vocabulary that device commands don't share. "Open Python" is still
     * an app launch, because a local intent is checked first and wins; "write me a
     * Python script" has no local reading at all.
     */
    private val studyOrCode = Regex(
        "\\b(" +
            // Languages and the surfaces they live on.
            "python|java|kotlin|javascript|typescript|node\\.?js|c\\+\\+|c#|golang|rust|swift|" +
            "php|ruby|rails|sql|nosql|html|css|bash|shell script|powershell|matlab|assembly|" +
            "dart|flutter|scala|perl|haskell|lua|solidity|verilog|latex|" +
            // What people ask to be done with them.
            "code|coding|program(me)?|script|snippet|function|method|algorithm|regex|" +
            "compile|compiler|syntax|stack trace|traceback|segfault|debug|refactor|" +
            "recursion|big o|data structure|linked list|binary search|unit test|" +
            "pseudocode|api endpoint|json schema|docker|git rebase|leetcode|" +
            // School, in the words students actually use.
            "homework|assignment|coursework|past paper|mark scheme|exam|revision|" +
            "essay|thesis|dissertation|bibliography|cite|citation|problem set|" +
            "multiple choice|study guide|flashcards|" +
            // Subjects, where the ask is to work something out rather than look it up.
            "prove that|theorem|derivative|integral|differentiate|simplify|factorise|" +
            "factorize|solve for|stoichiometry|titration|photosynthesis|" +
            "linear algebra|matrix|probability|standard deviation" +
            ")\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * The difference between asking *about* something and asking for it to be done.
     *
     * "Should I learn Kotlin or Swift" names a language and is still a conversation —
     * an opinion, answerable in three sentences. "Refactor my Kotlin" names the same
     * language and is a job. Consulted only when a message trips both the study
     * vocabulary and the conversational hedges, to break the tie: a verb that produces
     * or repairs something, or a reference to the user's own work.
     */
    private val wantsWorkDone = Regex(
        "\\b(write|implement|generate|build|refactor|debug|fix|correct|solve|prove|derive|" +
            "compute|calculate|convert|translate|example|examples|snippet|sample|" +
            "step by step|show me|walk me through|my|mine|this)\\b",
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

        // Being taught is checked before anything else can claim the sentence. "When I
        // say X, do Y" contains an instruction and would otherwise be read as one —
        // she would do the thing instead of learning it.
        SkillTeacher.parse(message)?.let { return Route.LearnSkill(it.name, it.steps) }

        // Ahead of everything, including the local matchers: a gate is open and this
        // message is the reply to it, whatever else it looks like. Sending the word
        // that settles it to a model would be the one leak that matters.
        if (DeveloperGate.isArmed) return Route.Local(LocalIntent.DeveloperAnswer(message))
        if (DeveloperGate.isStandingDown) return Route.Local(LocalIntent.StandDownAnswer(message))
        if (DeveloperGate.looksLikeStandDown(message)) return Route.Local(LocalIntent.StandDownRequest)

        // Resolved once and reused: a local reading beats both of the checks below,
        // so "open Python" stays an app launch and "solve 12 x 4" stays arithmetic.
        val local = localIntent(normalised)

        // Ahead of the conversational check because "explain recursion in Python" is
        // both, and only one of the two paths has room to answer it. Where a message
        // is conversational as well, it takes study only if it asks for work rather
        // than for an opinion.
        if (local == null && studyOrCode.containsMatchIn(normalised) &&
            (!conversational.containsMatchIn(normalised) || wantsWorkDone.containsMatchIn(normalised))
        ) {
            return Route.Study
        }

        // A question about the mechanism is never a command to perform it — but it was
        // being sent to Route.Model, which is the *full agent loop*: the whole toolbox,
        // planning, and up to maxToolRounds network calls to answer "what do you think
        // of this". Those phrases are the substance of a conversation, so every real
        // conversation took the most expensive path in the app. That is the overthinking.
        //
        // Chat is the right destination: one streamed call, no tools. It is safe
        // because the fast path carries an escape hatch — a model that decides it
        // needs the phone after all says so, and the caller falls through to the full
        // loop. A misroute costs one cheap request, never a wrong answer.
        // A question about herself beats the conversational hedges, because the hedges
        // exist for things a model knows better than the app does — and it never knows
        // better about her own name, her developer, or what "necio" means. "Why are you
        // called Lain" was reaching a model on the strength of the word "why", and the
        // model answered with the anime. These are constants; they are answered here.
        if (local is LocalIntent.Identity) return Route.Local(local)

        if (conversational.containsMatchIn(normalised)) return Route.Chat

        if (local != null) return Route.Local(local)

        // No local match: fall back to the existing coarse split so plain conversation
        // still skips the tool surface.
        return when (IntentClassifier.classify(message)) {
            TurnIntent.CHAT -> Route.Chat
            TurnIntent.ACT -> Route.Model
        }
    }

    /**
     * Command verbs in the languages the app is actually used in.
     *
     * The router is otherwise English-only, so "abre WhatsApp" paid for a network
     * round trip that "open WhatsApp" did not — the same command, one of them slow,
     * for no reason the user could see. Rather than duplicating every matcher per
     * language, the leading verb is translated to its English equivalent and the
     * existing matchers run unchanged.
     *
     * Only the verbs, and only the frequent ones. Anything more elaborate belongs to
     * the model, which handles these languages properly; this is about not paying a
     * round trip for "open WhatsApp" in Yoruba.
     */
    private val COMMAND_TRANSLATIONS: Map<String, String> = mapOf(
        // Spanish
        "abre" to "open", "abrir" to "open", "llama" to "call", "llamar" to "call",
        "envía" to "text", "envia" to "text", "manda" to "text",
        "reproduce" to "play", "pon" to "play", "cierra" to "close", "busca" to "search",
        // French
        "ouvre" to "open", "ouvrir" to "open", "appelle" to "call", "appeler" to "call",
        "envoie" to "text", "joue" to "play", "ferme" to "close", "cherche" to "search",
        // Portuguese
        "abra" to "open", "ligue" to "call", "toque" to "play", "feche" to "close",
        "procure" to "search",
        // German
        "öffne" to "open", "offne" to "open", "ruf" to "call", "spiele" to "play",
        "schließe" to "close", "suche" to "search",
        // Yoruba
        "ṣí" to "open", "si" to "open", "pè" to "call", "pe" to "call",
        "fi" to "text", "ránṣẹ́" to "text", "ta" to "play", "wá" to "search",
        // Hausa
        "buɗe" to "open", "bude" to "open", "kira" to "call", "aika" to "text",
        "kunna" to "play", "rufe" to "close", "nema" to "search",
        // Igbo
        "mepee" to "open", "kpọọ" to "call", "kpoo" to "call", "zipu" to "text",
        "kpọ" to "play", "mechie" to "close", "chọọ" to "search",
        // Swahili
        "fungua" to "open", "piga" to "call", "tuma" to "text", "cheza" to "play",
        "funga" to "close", "tafuta" to "search"
    )

    /**
     * Swaps a leading foreign command verb for its English equivalent.
     *
     * Only the first word, and only when the rest of the message survives — the
     * target of the command ("WhatsApp", a contact name) is left exactly as spoken,
     * because translating it would be how "call Ade" becomes a call to nobody.
     */
    private fun translateCommandVerb(message: String): String {
        val firstSpace = message.indexOf(' ')
        if (firstSpace <= 0) return message
        val verb = message.substring(0, firstSpace)
        val english = COMMAND_TRANSLATIONS[verb] ?: return message
        return english + message.substring(firstSpace)
    }

    private fun normalise(message: String): String = message
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .replace(politePrefix, "")
        .replace(politeSuffix, "")
        .trim()
        .trimEnd('.', '!', '?')
        .let(::translateCommandVerb)

    // ------------------------------------------------------------------ intents

    private fun localIntent(t: String): LocalIntent? =
        // Identity first and cheapest: these are constants and a database row.
        smallTalk(t) ?: identity(t) ?: developerClaim(t) ?: recite(t) ?: whereAmI(t)
            ?: lockScreen(t) ?: power(t) ?: closeSelf(t)
            ?: clock(t) ?: battery(t) ?: torch(t)
            // Do Not Disturb before the generic volume matcher: "silence my phone"
            // means the ringer, not the media stream.
            ?: dnd(t) ?: ringer(t)
            ?: volume(t) ?: brightness(t)
            // Scheduling is checked before timer(), which only understands delays;
            // "set an alarm for 2:30" is a clock time and would otherwise fall
            // through to the model.
            ?: showAlarms(t) ?: listSchedule(t) ?: cancelSchedule(t) ?: schedule(t) ?: timer(t)
            // Before the immediate commands below, or "open WhatsApp at 2:26" opens it now.
            ?: scheduledCommand(t)
            // Search before the plain app launcher: "open chrome and search X" names an
            // app but is not an app launch, and going through the model for it cost
            // five round trips and stalled halfway.
            ?: searchIn(t)
            ?: clearRecents(t) ?: closeApp(t)
            ?: navigate(t) ?: readScreen(t) ?: tapControl(t)
            // The real toggle first; the settings-page fallback only when it misses.
            ?: systemToggle(t) ?: settingsOrToggle(t) ?: call(t)
            // Music is checked before the generic app launcher so "play spotify" is
            // understood as playback rather than as opening an app called "spotify".
            ?: transport(t) ?: playMusic(t)
            // Arithmetic is checked late: it only matches strings that are
            // unambiguously expressions, so it can't shadow a real command.
            ?: calculate(t)
            ?: openApp(t)

    private fun clock(t: String): LocalIntent? = when {
        t.matches(Regex("(what('?s| is) the )?time( is it)?")) ||
            t.matches(Regex("what time is it( (right )?now)?")) ||
            t == "time" || t == "current time" -> LocalIntent.Clock(wantsDate = false)

        t.matches(Regex("(what('?s| is) )?(the )?(today's )?date( is it)?( today)?")) ||
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
        // "Turn the ringer down" never says the word volume, and it is the phrasing
        // people actually use for the one stream it matters most to get right.
        if (!t.contains("volume") && !t.contains("sound") &&
            !t.contains("ringer") && !t.contains("ringtone")
        ) {
            return null
        }
        if (t.contains("setting")) return null

        // Which volume. Named streams win over the default, so "turn the ringer down"
        // stops being a media change that leaves the phone as loud as it was.
        val stream = when {
            t.contains("ringer") || t.contains("ringtone") || t.contains("ring volume") -> "ring"
            t.contains("notification") -> "notification"
            t.contains("alarm") -> "alarm"
            t.contains("call volume") || t.contains("in-call") || t.contains("earpiece") -> "call"
            else -> "media"
        }

        Regex("(\\d{1,3})\\s*(%|percent)?").find(t)?.let { m ->
            val value = m.groupValues[1].toIntOrNull()
            if (value != null && value in 0..100 &&
                (t.contains("set") || t.contains("to ") || t.contains("%") || t.contains("percent"))
            ) {
                return LocalIntent.Volume(percent = value, direction = 0, stream = stream)
            }
        }
        return when {
            t.contains("mute") || t.contains("silence") || t.contains("silent") ->
                LocalIntent.Volume(percent = 0, direction = 0, stream = stream)
            t.contains("max") || t.contains("full") || t.contains("loudest") ->
                LocalIntent.Volume(percent = 100, direction = 0, stream = stream)
            t.contains("up") || t.contains("increase") || t.contains("louder") || t.contains("raise") ->
                LocalIntent.Volume(percent = null, direction = 1, stream = stream)
            t.contains("down") || t.contains("decrease") || t.contains("quieter") || t.contains("lower") ->
                LocalIntent.Volume(percent = null, direction = -1, stream = stream)
            else -> null
        }
    }

    /**
     * "dim the screen", "brightness to 70", "put brightness back on auto".
     *
     * A screen brightness request has no business costing a network round trip, and
     * "dim the screen" is one of the things a person says when they are already
     * squinting at it in the dark.
     */
    private fun brightness(t: String): LocalIntent? {
        val named = t.contains("brightness") || t.contains("brighter") || t.contains("dimmer")
        val screenish = t.contains("screen") || t.contains("display")
        val dimming = (t.contains("dim") || t.contains("darken")) && screenish
        val brightening = t.contains("brighten") && screenish
        if (!named && !dimming && !brightening) return null
        // "brightness settings" is a request to see the page, not to change the value.
        if (t.contains("setting")) return null

        if (t.contains("auto") || t.contains("automatic") || t.contains("adaptive")) {
            return LocalIntent.Brightness(percent = null, direction = 0, auto = true)
        }

        Regex("(\\d{1,3})\\s*(%|percent)?").find(t)?.let { m ->
            val value = m.groupValues[1].toIntOrNull()
            if (value != null && value in 0..100 &&
                (t.contains("set") || t.contains("to ") || t.contains("%") || t.contains("percent"))
            ) {
                return LocalIntent.Brightness(percent = value, direction = 0)
            }
        }
        return when {
            t.contains("max") || t.contains("full") || t.contains("brightest") ->
                LocalIntent.Brightness(percent = 100, direction = 0)
            dimming || t.contains("dimmer") || t.contains("down") || t.contains("lower") ||
                t.contains("darker") || t.contains("decrease") ->
                LocalIntent.Brightness(percent = null, direction = -1)
            brightening || t.contains("brighter") || t.contains("up") || t.contains("raise") ||
                t.contains("increase") ->
                LocalIntent.Brightness(percent = null, direction = 1)
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

    // ------------------------------------------------------------ scheduling

    private val scheduleOpeners = listOf(
        "set an alarm", "set a alarm", "set alarm", "alarm for", "wake me",
        "remind me", "reminder", "schedule", "every day at", "every morning",
        "every weekday", "each day at"
    )

    /**
     * "set an alarm for 2:30", "wake me at 7 every weekday", "call mama every day at 8".
     *
     * Only claimed when a time can actually be resolved. A phrase the parser can't
     * read goes to the model rather than producing a confidently wrong alarm.
     */
    private fun schedule(t: String): LocalIntent? {
        val opener = scheduleOpeners.any { t.contains(it) }
        // "everyday" as one word is how most people write it, and writing it that way
        // meant nothing was recognised as recurring at all: "tell me goodnight everyday
        // at 11 pm" fell past the scheduler entirely and was answered by a model, which
        // said she could not schedule anything. WhenParser has always accepted both
        // spellings; only this gate had not.
        val recurring = Regex(
            "\\bevery ?(day|morning|night|weekday|weekend|week|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b|\\bdaily\\b|\\beach day\\b"
        ).containsMatchIn(t)
        if (!opener && !recurring) return null
        // Requires a resolvable time, so "remind me about this later" still reaches
        // the model, which can ask what "later" means.
        val parsed = WhenParser.parse(t) ?: return null

        // A plain countdown ("remind me in two hours") is a timer, and timer() already
        // phrases that better — "in two hours" rather than a wall-clock time. Only
        // clock times and repeats belong to the scheduler.
        val countdownOnly = parsed.repeat == com.lain.assistant.data.Repeat.ONCE &&
            Regex("\\bin\\s+(a|an|\\d+)\\s*(second|sec|minute|min|hour|hr)s?\\b").containsMatchIn(t)
        if (countdownOnly) return null

        val alarm = t.contains("alarm") || t.contains("wake me")
        return LocalIntent.Schedule(phrase = t, alarm = alarm)
    }

    /**
     * A command with a clock time in it is a command *for later*.
     *
     * "Open WhatsApp at 2:26 pm" opened WhatsApp immediately, because scheduling was
     * only recognised by opener words — "remind me", "set an alarm" — and this has
     * none of them. It reads as an app launch that happens to contain a number, and
     * that is precisely how it behaved. The same held for "call Ade at 6" and
     * "text mum at 9".
     *
     * The signal is the time, not the vocabulary. A future clock time attached to an
     * actionable command means the command belongs to the scheduler; without one,
     * nothing changes and the command runs now.
     *
     * Countdowns are excluded deliberately — "call her in five minutes" is a timer,
     * and [timer] phrases it better. So are past times, which almost always means the
     * number was never a time at all.
     */
    private fun scheduledCommand(t: String): LocalIntent? {
        // Only commands that actually reach out. Toggling a torch at 3pm is not a
        // thing people ask for, and treating every number as a schedule would be a
        // much worse bug than the one this fixes.
        // "tell"/"say"/"wish" are here for the same reason as the rest: they are things
        // she is being asked to do at a time, and without them "tell me goodnight at 11"
        // was conversation with a number in it.
        val actionable = Regex(
            "^(open|launch|start|call|ring|dial|text|message|whatsapp|sms|play|tell|say|wish)\\b"
        ).containsMatchIn(t)
        if (!actionable) return null

        // A preposition of time has to be present. "Call 07700900123" is a phone
        // number, not six minutes past seven.
        if (!Regex("\\b(at|by|around|on)\\b").containsMatchIn(t)) return null

        val parsed = WhenParser.parse(t) ?: return null
        if (Regex("\\bin\\s+(a|an|\\d+)\\s*(second|sec|minute|min|hour|hr)s?\\b").containsMatchIn(t)) {
            return null
        }
        // Resolved to the past means the parser latched onto something that was not a
        // time. Scheduling it would fire instantly, which looks exactly like the bug.
        if (parsed.triggerAtMillis <= System.currentTimeMillis() + 30_000) return null

        return LocalIntent.Schedule(phrase = t, alarm = false)
    }

    private fun listSchedule(t: String): LocalIntent? = when {
        Regex("\\b(what|which|any|list|show|do i have)\\b").containsMatchIn(t) &&
            Regex("\\b(alarms?|reminders?|scheduled)\\b").containsMatchIn(t) -> LocalIntent.ListSchedule
        else -> null
    }

    private fun cancelSchedule(t: String): LocalIntent? {
        if (!Regex("\\b(cancel|delete|remove|clear)\\b").containsMatchIn(t)) return null
        if (!Regex("\\b(alarms?|reminders?|scheduled task)\\b").containsMatchIn(t)) return null
        val which = t
            .replace(Regex("\\b(cancel|delete|remove|clear|my|the|for)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return LocalIntent.CancelSchedule(which.ifBlank { "alarm" })
    }

    // ------------------------------------------------------- sound profile

    private fun dnd(t: String): LocalIntent? {
        val mentions = t.contains("do not disturb") || t.contains("dnd") || t.contains("don't disturb")
        if (!mentions) return null
        val off = Regex("\\b(off|disable|stop|end|cancel)\\b").containsMatchIn(t)
        return LocalIntent.Dnd(if (off) "off" else "priority")
    }

    private fun ringer(t: String): LocalIntent? {
        // Scoped to phrases that clearly mean the ringer, so "mute the video" is
        // left to the volume matcher.
        val phone = t.contains("phone") || t.contains("ringer") || t.contains("ring")
        return when {
            (t.contains("silent") || t.contains("silence")) && phone -> LocalIntent.Ringer("silent")
            t.contains("vibrate") || t.contains("vibration only") -> LocalIntent.Ringer("vibrate")
            (t.contains("unmute") || t.contains("normal") || t.contains("sound on")) && phone ->
                LocalIntent.Ringer("normal")
            else -> null
        }
    }

    // ------------------------------------------------------------ recitation

    private fun recite(t: String): LocalIntent? {
        if (Regex("\\b(stop|pause|end)\\b").containsMatchIn(t) &&
            Regex("\\b(recitation|reciting|quran|qur'an|koran|surah|surat)\\b").containsMatchIn(t)
        ) {
            return LocalIntent.Recite("", "", stop = true)
        }

        // Named as scripture, so whatever follows is meant as a surah even if the
        // spelling is unusual.
        val named = Regex(
            "^(?:recite|play|put on|read)\\s+(?:me\\s+)?(?:the\\s+)?" +
                "(?:quran|qur'an|koran|surah|surat|sura|chapter)\\s*(.{0,40})$"
        ).find(t)

        // The bare form, where the only evidence is the verb. "Read X" is far more
        // often a request to read something on the phone, so this one has to prove
        // itself: "read the screen" used to become a recitation of a surah whose name
        // scored weakly against "the screen", and the display was never read.
        val bare = named ?: Regex("^(?:recite|read)\\s+(?:me\\s+)?(.{2,40})$").find(t)
        val m = bare ?: return null
        if (named == null) {
            val candidate = m.groupValues[1].trim()
                .substringBefore(" by ").substringBefore(" with ").trim()
            if (com.lain.assistant.automation.QuranIndex.findCertain(candidate) == null) return null
        }

        var target = m.groupValues[1].trim()
        var reciter = ""
        // "surah al-kahf by sudais"
        Regex("^(.*?)\\s+(?:by|with|from)\\s+(.+)$").find(target)?.let { split ->
            target = split.groupValues[1].trim()
            reciter = split.groupValues[2].trim()
        }
        return LocalIntent.Recite(target, reciter, stop = false)
    }

    // -------------------------------------------------------------- location

    private fun whereAmI(t: String): LocalIntent? = when {
        t == "where am i" || t == "where am i right now" || t == "my location" ||
            t == "what's my location" || t == "whats my location" ||
            t == "what is my location" || t == "where are we" ||
            t == "where am i now" -> LocalIntent.WhereAmI
        else -> null
    }

    // ------------------------------------------------------------ small talk

    /**
     * Matched on the whole message only.
     *
     * "Hi" is a greeting; "hi, can you open WhatsApp" is a request that happens to
     * start with one. Exact matching keeps the second going to the model, where it
     * belongs — the cost of being wrong here is answering "hello" to somebody who
     * asked for something.
     */
    private fun smallTalk(t: String): LocalIntent? {
        val bare = t.trim().trimEnd('!', '.', ',')
        return when (bare) {
            "hi", "hey", "hello", "yo", "hiya", "hey there", "hi there", "hello there",
            "morning", "good morning", "good afternoon", "good evening", "sup", "wassup",
            "what's up", "whats up", "hi lain", "hey lain", "hello lain" ->
                LocalIntent.SmallTalk(SmallTalkKind.GREETING)

            "thanks", "thank you", "thanks a lot", "cheers", "ta", "thx", "much appreciated",
            "appreciate it", "thank you so much", "nice one" ->
                LocalIntent.SmallTalk(SmallTalkKind.THANKS)

            "how are you", "how are you doing", "how's it going", "hows it going",
            "you good", "you alright", "how you doing" ->
                LocalIntent.SmallTalk(SmallTalkKind.HOW_ARE_YOU)

            "bye", "goodbye", "see you", "see ya", "later", "goodnight", "good night",
            "night", "cya" ->
                LocalIntent.SmallTalk(SmallTalkKind.GOODBYE)

            "ok", "okay", "cool", "nice", "great", "alright", "got it", "understood",
            "sure", "fine", "sounds good", "perfect" ->
                LocalIntent.SmallTalk(SmallTalkKind.AFFIRMATION)

            else -> null
        }
    }

    // -------------------------------------------------------------- identity

    private fun identity(t: String): LocalIntent? = when {
        Regex("^(what('?s| is) )?my name( again)?\\??$").matches(t) ||
            t == "who am i" || t == "do you know my name" || t == "say my name" ->
            LocalIntent.Identity(IdentityQuestion.USER_NAME)

        Regex("^(how old am i|what('?s| is) my age)\\??$").matches(t) ->
            LocalIntent.Identity(IdentityQuestion.USER_AGE)

        Regex("^(what('?s| is) )?your name\\??$").matches(t) || t == "who are you" ||
            t == "what are you called" || t == "what should i call you" ||
            // Asking where the name is from is asking for the acronym, and is the
            // other phrasing a model answers with the anime.
            Regex("^where (does|did) (your|the) name come from\\??$").matches(t) ||
            t == "why are you called lain" || t == "why lain" ->
            LocalIntent.Identity(IdentityQuestion.LAIN_NAME)

        Regex("^(what('?s| is) )?(the name of )?(this|your) app( called)?\\??$").matches(t) ||
            t == "what app is this" || t == "what's this app" || t == "whats this app" || t == "whats this app" ->
            LocalIntent.Identity(IdentityQuestion.APP_NAME)

        // The one thing about her own name she was getting wrong: asked what "Necio"
        // meant, a model with no way of knowing would invent something.
        // Both spellings: the name is Necio, but earlier builds said Niceo and
        // that is still what some users will type at her.
        (t.contains("necio") || t.contains("niceo")) &&
            Regex("\\b(what|why|mean|means|meaning|stand|short)\\b").containsMatchIn(t) ->
            LocalIntent.Identity(IdentityQuestion.NECIO)

        // Who built her. A model asked this has nothing to go on and answers with a
        // confident invention — an AI lab, a company, a person who does not exist —
        // so it is answered from a constant like every other fact about herself.
        Regex(
            "^who('?s| is| was)? ?(the )?(person |guy |man |one )?(that |who )?" +
                "(made|built|created|wrote|developed|designed|coded|programmed) (you|this app|lain)\\??$"
        ).matches(t) ||
            Regex("^who('?s| is) your (developer|creator|dev|maker|author|programmer)\\??$").matches(t) ||
            t == "who is behind you" || t == "who's behind you" || t == "whos behind you" ||
            t == "who made this" || t == "who owns you" ||
            Regex("^what('?s| is) your (developer|creator)('?s)? name\\??$").matches(t) ->
            LocalIntent.Identity(IdentityQuestion.DEVELOPER)

        // Serial Experiments Lain is all over any model's training data and her real
        // origin is in none of it, so left alone she confirms the wrong answer with
        // complete confidence. Checked before the plain name question, since these
        // phrasings contain it.
        (t.contains("serial experiment") || t.contains("anime") || t.contains("manga") ||
            t.contains("named after") || t.contains("name from")) &&
            Regex("\\b(you|your|lain|name)\\b").containsMatchIn(t) ->
            LocalIntent.Identity(IdentityQuestion.ANIME)

        t == "what can you do" || t == "what are you able to do" || t == "help" ||
            t == "what can i ask you" || t == "what do you do" ->
            LocalIntent.Identity(IdentityQuestion.CAPABILITIES)

        // Everything below is a fact about real software that a model has never seen
        // and will therefore invent, in detail, with total confidence. Lewa Coder and
        // Lewa Therapist exist and people can go and check what they do, which makes a
        // fabricated feature list worse than no answer.
        t.contains("poopy butthole") &&
            Regex("\\b(who|what|tell me about|know about)\\b").containsMatchIn(t) ->
            LocalIntent.Identity(IdentityQuestion.MAKER)

        t.contains("lewa coder") -> LocalIntent.Identity(IdentityQuestion.LEWA_CODER)

        (t.contains("lewa therapist") || t.contains("lewa therapy")) ->
            LocalIntent.Identity(IdentityQuestion.LEWA_THERAPIST)

        // The shop, but only when it is plainly the subject — "lewa" alone is too
        // short a word to claim on sight.
        Regex("\\blewa\\s*(\\u00b7|\\.|-)?\\s*dev\\b").containsMatchIn(t) ->
            LocalIntent.Identity(IdentityQuestion.LEWA_DEV)

        else -> null
    }

    /**
     * Someone saying they built her.
     *
     * Matched on the whole message so a sentence that merely mentions making
     * something is left alone, and kept narrow on purpose: the reply to this is a
     * challenge, and a false match means telling someone who was talking about
     * something else entirely to prove themselves.
     */
    private fun developerClaim(t: String): LocalIntent? {
        val claim = Regex(
            "^(i'?m|i am|it'?s me,? i'?m|this is) (your |the |lain'?s )?" +
                "(developer|creator|dev|maker|author|programmer|the one who (made|built|created) you)\\.?$"
        ).matches(t) ||
            Regex("^i (made|built|created|wrote|coded|developed|designed) (you|this app|lain)\\.?$").matches(t) ||
            Regex("^(i'?m|i am) professor poopy butthole\\.?$").matches(t) ||
            t == "soy tu desarrollador" || t == "yo te hice"

        return if (claim) LocalIntent.DeveloperClaim else null
    }

    /**
     * "Show my alarms", "open my alarms".
     *
     * Distinct from listing her own reminders. Android publishes no way to read the
     * clock app's alarms, so the honest answer to "what alarms have I got" is to open
     * the list rather than recite one that may not match what will actually ring.
     */
    private fun showAlarms(t: String): LocalIntent? = when {
        Regex("^(show|open|see|check)( me)? (my )?alarms?$").matches(t) ||
            t == "what alarms do i have" || t == "what alarms have i got" ||
            t == "my alarms" || t == "list my alarms" -> LocalIntent.ShowAlarms
        else -> null
    }

    // ----------------------------------------------------------- power state

    private fun lockScreen(t: String): LocalIntent? = when {
        Regex("\\b(lock|turn off|switch off|shut off|blank)\\b.*\\b(screen|phone|display)\\b")
            .containsMatchIn(t) && !t.contains("data") && !t.contains("wifi") ->
            LocalIntent.LockScreen
        t == "lock it" || t == "lock" || t == "screen off" || t == "sleep" -> LocalIntent.LockScreen
        else -> null
    }

    /**
     * Restart and power off.
     *
     * Matched narrowly and always confirmed downstream. "Restart" said to an
     * assistant could mean the app, the phone, or a song — and getting it wrong
     * means the phone goes down mid-sentence.
     */
    private fun power(t: String): LocalIntent? = when {
        Regex("^(restart|reboot)( my| the)?( phone| device)?\\.?$").matches(t) ->
            LocalIntent.Power(restart = true)
        Regex("^(shut ?down|power off|turn off)( my| the)?( phone| device)\\.?$").matches(t) ->
            LocalIntent.Power(restart = false)
        else -> null
    }

    private fun closeSelf(t: String): LocalIntent? = when (t) {
        "close yourself", "close lain", "exit", "exit lain", "quit lain",
        "close the app", "shut yourself down", "go away" -> LocalIntent.CloseSelf
        else -> null
    }

    // ------------------------------------------------------------- searching

    private fun searchIn(t: String): LocalIntent? {
        // "open chrome and search animeheaven" / "open chrome and search for X"
        Regex("^(?:open|launch|start)\\s+(.{2,30}?)\\s+(?:and\\s+)?(?:search|look up|find)(?:\\s+for)?\\s+(.{2,80})$")
            .find(t)?.let { m ->
                return LocalIntent.SearchIn(app = cleanAppName(m.groupValues[1]), query = m.groupValues[2].trim())
            }
        // "search youtube for cats" / "search for cats on youtube"
        Regex("^(?:search|look up|find)\\s+(?:on\\s+)?(.{2,30}?)\\s+for\\s+(.{2,80})$").find(t)?.let { m ->
            return LocalIntent.SearchIn(app = cleanAppName(m.groupValues[1]), query = m.groupValues[2].trim())
        }
        Regex("^(?:search|look up|find)\\s+(?:for\\s+)?(.{2,80}?)\\s+on\\s+(.{2,30})$").find(t)?.let { m ->
            return LocalIntent.SearchIn(app = cleanAppName(m.groupValues[2]), query = m.groupValues[1].trim())
        }
        return null
    }

    private fun cleanAppName(raw: String): String =
        raw.trim().removePrefix("the ").removePrefix("my ").removeSuffix(" app").trim()

    // -------------------------------------------------------------- closing

    private fun closeApp(t: String): LocalIntent? {
        Regex("^(?:close|quit|exit|kill|stop)\\s+(?:the\\s+)?(.{2,30}?)(?:\\s+app)?$").find(t)?.let { m ->
            val target = m.groupValues[1].trim()
            // "stop music" and friends belong to the transport controls.
            if (target in setOf("music", "playback", "song", "playing", "it", "that")) return null
            if (target in setOf("this", "this app", "app")) return LocalIntent.CloseApp("")
            return LocalIntent.CloseApp(target)
        }
        return null
    }

    private fun clearRecents(t: String): LocalIntent? = when {
        Regex("\\b(clear|close|kill)\\b.*\\b(recent|recents|background|all apps|everything)\\b")
            .containsMatchIn(t) -> LocalIntent.ClearRecents
        else -> null
    }

    // -------------------------------------------------------------- toggles

    /**
     * A real switch, not a trip to a settings page.
     *
     * Requires an explicit on/off. "wifi" alone is a question about Wi-Fi, and
     * flipping it because the word appeared would be the wrong kind of helpful.
     */
    private fun systemToggle(t: String): LocalIntent? {
        val on = when {
            Regex("\\b(turn on|switch on|enable|activate|put on)\\b").containsMatchIn(t) -> true
            Regex("\\b(turn off|switch off|disable|deactivate|kill|put off)\\b").containsMatchIn(t) -> false
            Regex("\\b(on)\\b$").containsMatchIn(t) -> true
            Regex("\\b(off)\\b$").containsMatchIn(t) -> false
            else -> return null
        }
        val what = listOf(
            "wifi", "wi-fi", "wi fi", "wireless", "bluetooth", "hotspot", "tethering",
            "mobile data", "cellular", "data", "location", "gps",
            "airplane mode", "aeroplane mode", "flight mode", "auto-rotate", "auto rotate", "rotation"
        ).sortedByDescending { it.length }.firstOrNull { t.contains(it) } ?: return null

        return LocalIntent.SystemToggle(what = what, on = on)
    }

    private fun navigate(t: String): LocalIntent? = when (t) {
        "go home", "home screen", "go to home" -> LocalIntent.Navigate("home")
        "go back", "back", "press back" -> LocalIntent.Navigate("back")
        "recents", "recent apps", "show recents" -> LocalIntent.Navigate("recents")
        "notifications", "show notifications", "open notifications" -> LocalIntent.Navigate("notifications")
        else -> null
    }

    /**
     * "What's on screen", in the shapes people actually use.
     *
     * This was an exact-match list of seven strings, which meant "read this page to
     * me", "what does this say" and "what am I looking at" all went to a model with
     * the whole toolbox — several seconds and a network round trip to run a screen
     * read the app can do in a millisecond. The keyword pre-check keeps the regexes
     * off the hot path for every message that has nothing to do with the screen.
     */
    private fun readScreen(t: String): LocalIntent? {
        if (t in exactScreenReads) return LocalIntent.ReadScreen
        if (!SCREEN_CUES.any { t.contains(it) }) return null
        return if (screenQuestion.matches(t) || screenCommand.matches(t)) LocalIntent.ReadScreen else null
    }

    private val exactScreenReads = setOf(
        "what's on screen", "what is on screen", "what's on the screen", "read the screen",
        "read screen", "what do you see", "what's on my screen", "what am i looking at",
        "what does this say", "what does it say", "look at the screen", "look at my screen",
        "can you see the screen", "can you see my screen", "describe the screen"
    )

    /** A cheap containment test, so the two patterns never run on an ordinary message. */
    private val SCREEN_CUES = listOf("screen", "say", "page", "showing")

    /**
     * Anchored with matches(), not containsMatchIn().
     *
     * A loose version of this caught "what is he saying", which is conversation about
     * a person and not a request to read the display. Requiring the whole message to
     * be the question is the difference between a shortcut and a misroute.
     */
    private val screenQuestion = Regex(
        "^(what|what's|whats)( does| is| are)? (this|it|that|the screen|the page)( page| screen)? " +
            "(say|says|saying|showing|show)( me)?\\??$"
    )

    private val screenCommand = Regex(
        "^(read|read out|look at|describe|check)( me)?( the| this| my)? (screen|page|display)( to me| for me)?\\??$"
    )

    /**
     * "Tap the send button", "press Continue for me".
     *
     * The point of the whole screen-control layer for somebody who cannot see the
     * screen, and it was the one part with no local route: every tap went through a
     * model, which had to read the screen, decide, and call tap_text — three round
     * trips to press a button whose name the user had already said out loud.
     *
     * Only claimed when a label survives the trimming. Navigation words are excluded
     * because [navigate] and press_key own those, and a "tap" with nothing after it
     * is not a request this can serve.
     */
    private fun tapControl(t: String): LocalIntent? {
        val m = tapPattern.find(t) ?: return null
        val label = m.groupValues[1]
            .replace(Regex("\\b(button|icon|option|link|for me|please|now)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (label.length < 2 || label.length > 40) return null
        if (label in notALabel) return null
        return LocalIntent.TapText(label)
    }

    private val tapPattern = Regex("^(?:tap|press|click|hit|select|choose)\\s+(?:on\\s+)?(?:the\\s+)?(.+)$")

    /** Words that name a gesture or a key, not something drawn on the screen. */
    private val notALabel = setOf(
        "back", "home", "recents", "recent apps", "enter", "return", "escape",
        "power", "volume up", "volume down", "screen", "it", "that", "this"
    )

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
