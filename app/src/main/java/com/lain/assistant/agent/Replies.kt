package com.lain.assistant.agent

/**
 * Everything Lain says without asking a model.
 *
 * Gathered in one place because the variety is the feature. A local answer is
 * instant, which is the whole point of having one — but an instant answer that is
 * byte-identical every time reads as a vending machine, and that impression is
 * worse than the second it saved. Five or more of each, so the same question twice
 * in a day does not come back word for word.
 *
 * Some lines carry Spanish. It is marked rather than guessed at, because the
 * synthesiser has to know: "de nada" read by an English voice is not Spanish with
 * an accent, it is two English words. [Spoken] keeps the display text and the
 * pronunciation language together so the audio comes out right.
 */
object Replies {

    /**
     * A line, plus what language it should be *said* in.
     *
     * [segments] is the same text split into runs of one language each, so a reply
     * that mixes English and Spanish is spoken as both rather than as whichever one
     * happened to win a whole-string guess.
     */
    data class Spoken(val text: String, val segments: List<Segment>) {
        data class Segment(val text: String, val language: Language.Tag)

        override fun toString(): String = text
    }

    /** English only. */
    private fun en(text: String) =
        Spoken(text, listOf(Spoken.Segment(text, Language.Tag.ENGLISH)))

    /** Spanish only. */
    private fun es(text: String) =
        Spoken(text, listOf(Spoken.Segment(text, Language.Tag.SPANISH)))

    /**
     * Spanish opener, English remainder — the shape almost all of her mixing takes.
     *
     * Split explicitly rather than detected: "Vale. Anything else?" would score as
     * English overall and the Spanish word would be read as one, which is exactly
     * the complaint this exists to answer.
     */
    private fun esThenEn(spanish: String, english: String) = Spoken(
        "$spanish $english",
        listOf(
            Spoken.Segment(spanish, Language.Tag.SPANISH),
            Spoken.Segment(english, Language.Tag.ENGLISH)
        )
    )

    // ------------------------------------------------------------ small talk

    val greetings = listOf(
        en("What's up necio?"),
        es("Dime."),
        en("Here."),
        en("Go on."),
        es("¿Qué pasa?"),
        en("Listening."),
        en("What do you need?")
    )

    fun greetingsWithName(name: String) = greetings + listOf(
        en("$name."),
        esThenEn("Dime,", "$name."),
        en("Go on, $name.")
    )

    val thanks = listOf(
        en("Any time."),
        es("De nada."),
        en("Sure."),
        en("That's the job."),
        en("No trouble."),
        esThenEn("De nada.", "Next?")
    )

    val howAreYou = listOf(
        en("Running. You?"),
        en("Same as always. What do you need?"),
        en("Fine. Nothing hurts yet."),
        es("Aquí estoy."),
        en("Idle, mostly. Fix that."),
        en("Working. Which is more than most of you manage.")
    )

    val goodbyes = listOf(
        en("Later."),
        es("Hasta luego."),
        en("I'll be here."),
        en("Night."),
        en("Go on then."),
        es("Nos vemos.")
    )

    fun goodbyesWithName(name: String) = goodbyes + listOf(
        en("Night, $name."),
        esThenEn("Hasta luego,", "$name.")
    )

    val affirmations = listOf(
        en("Mm."),
        es("Vale."),
        en("Right."),
        en("Anything else?"),
        en("Noted."),
        es("Entendido.")
    )

    // -------------------------------------------------------------- identity

    fun userName(display: String) = listOf(
        en(display),
        en("$display. Same as last time you asked."),
        en("You're $display."),
        esThenEn("Te llamas", "$display."),
        en("$display — unless you've changed it since setup."),
        en("Still $display.")
    )

    val unknownUserName = listOf(
        en("You never told me. Set it in Settings and I'll stop asking."),
        en("No idea — your profile's empty."),
        en("Nothing saved. Add it in Settings.")
    )

    fun userAge(age: Int) = listOf(
        en("$age."),
        en("$age, going by what you told me."),
        en("You said $age."),
        en("$age. Ageing at the usual rate.")
    )

    val lainName = listOf(
        en("Lain. Short for Leave-it-to-Artificial-intelligence-Necio."),
        en("Lain."),
        en("Lain — the acronym's Leave-it-to-Artificial-intelligence-Necio, since you'll ask."),
        esThenEn("Me llamo Lain.", "That's it."),
        en("Lain. Not Lane. The spelling matters to me."),
        en("Lain. You've been talking to me for a while now.")
    )

    val appName = listOf(
        en("Lain. This one."),
        en("This is Lain."),
        en("Lain — the app and the assistant are the same thing."),
        en("Lain. There's only one of me on here."),
        es("Se llama Lain.")
    )

    /**
     * "Necio", explained.
     *
     * Spanish for fool — closer to "stubborn idiot" than to anything affectionate.
     * A fact only the developer has, so a model asked this invents something
     * confident and different each time; it invented Nigerian slang when I got it
     * wrong. Some variants land the joke and some actually translate the word,
     * because both are asked for.
     */
    val necio = listOf(
        en(
            "Leave-it-to-Artificial-intelligence-Necio. Necio is Spanish for fool. My developer " +
                "built me for lazy people and put the insult in my name so you'd hear it every time."
        ),
        en(
            "Necio — Spanish, means fool. Stubborn-idiot fool, not endearing fool. It's the last " +
                "word of the acronym, so the whole thing reads \"let the AI do it, fool\". " +
                "That's the joke, and you're the punchline."
        ),
        en(
            "It means fool. Spanish. My developer's word for someone who'd rather ask me than get " +
                "up, and he built the reminder into my name."
        ),
        en(
            "Short version: it's Spanish for fool. Long version: I'm " +
                "Leave-it-to-Artificial-intelligence-Necio, made for people too lazy to do it " +
                "themselves, and the name is the nudge."
        ),
        en(
            "Necio. Fool, in Spanish — the stubborn kind, the kind who won't do a thing themselves. " +
                "You could have translated that yourself, by the way. Which is rather the point."
        ),
        esThenEn(
            "Necio.",
            "Spanish for fool. It's in my name on purpose — I was built for people who won't " +
                "do it themselves, and he wanted them reminded."
        )
    )

    val capabilities = listOf(
        en(
            "Calls, texts, WhatsApp. Alarms, reminders, recurring tasks. Opening apps and searching " +
                "in them. Wi-Fi, Bluetooth, data, torch, Do Not Disturb. Reading and tapping your " +
                "screen. Music and recitation. Maths, time, where you are. Code in any language, and " +
                "schoolwork with the working shown. Ask and I'll say if I can't."
        ),
        en(
            "Most of the phone. Ring people, message them, set alarms, open and drive apps, flip the " +
                "radios, read the screen out, play things, do sums. Write you working code, walk you " +
                "through an assignment. Ask for something specific and you'll find out faster " +
                "than I can list it."
        ),
        en(
            "Anything on the phone I can reach: calling, texting, alarms, apps, toggles, music, " +
                "Qur'an, maths, the time, where you are. Off the phone: code in whatever language you " +
                "need, and homework — method included, not just the answer. And I'll tell you " +
                "plainly when something Android won't let me do."
        ),
        en(
            "Phone things — calls, messages, alarms, apps, settings, media. Screen things, if you've " +
                "switched Accessibility on. Code and coursework, in any language you like. Knowledge " +
                "things, through whichever model you picked."
        )
    )

    // ----------------------------------------------------------------- sass

    /**
     * What she says when asked for something the user could plainly do themselves.
     *
     * The name is an insult, so the personality has to earn it rather than just
     * carry it. Prefixed to the real answer and never instead of it: she does the
     * thing, and complains while doing it. Sass that costs the user the result is
     * not sass, it is a broken assistant.
     *
     * "Tonto" is Spanish for stupid, and it is doing the same job as the name.
     */
    val sassPrefixes = listOf(
        en("That's a whole two taps away, but fine."),
        esThenEn("Tonto.", "Doing it."),
        en("You have hands."),
        esThenEn("Ay, tonto.", "Fine."),
        en("This is what I'm reduced to."),
        en("The screen is right there. Anyway —"),
        esThenEn("Tonto.", "One second."),
        en("I'll allow it."),
        en("Genuinely, you could have done that faster yourself."),
        esThenEn("Qué tonto.", "Right, done in a moment.")
    )

    /**
     * How often the sass fires on a qualifying request, as a percentage.
     *
     * Low on purpose. Every time is nagging and stops being funny by the third
     * repetition; roughly one in four keeps it a surprise, which is the only way a
     * joke survives being automated.
     */
    const val SASS_CHANCE = 25

    /**
     * Sticks a sass line in front of an answer, keeping both languages intact.
     *
     * The answer itself is English — it is assembled from app strings, not picked
     * from this file — so it becomes one English segment, and the prefix keeps
     * whatever split it was written with. Without this, "Tonto. Torch on." would be
     * language-guessed as a whole and the Spanish read as English.
     */
    fun prefixed(prefix: Spoken, reply: String): Spoken = Spoken(
        "${prefix.text} $reply",
        prefix.segments + Spoken.Segment(reply, Language.Tag.ENGLISH)
    )

    // ------------------------------------------------------------------ util

    /**
     * Picks a line, avoiding the one used last.
     *
     * A pure random pick repeats about as often as people notice — twice in a row is
     * common with six options — and a repeat is exactly what makes a canned answer
     * look canned.
     */
    fun pick(options: List<Spoken>, lastUsed: String?): Spoken {
        if (options.isEmpty()) return en("")
        if (options.size == 1) return options.first()
        val fresh = options.filterNot { it.text == lastUsed }
        return fresh.random()
    }
}
