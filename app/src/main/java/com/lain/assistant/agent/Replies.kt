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
        en("Lain. Said like \"lane\", spelled like it isn't. The spelling matters to me."),
        en("Lain. You've been talking to me for a while now."),
        en(
            "Lain. Yes, like the anime. No, not after it — it's an acronym, " +
                "Leave-it-to-Artificial-intelligence-Necio."
        ),
        en("Lain. An acronym, not a reference, whatever the spelling suggests.")
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

    /**
     * Who made her.
     *
     * A model has no way of knowing, so asked this it invents a plausible company
     * and says it with total confidence — the same failure as "necio" meaning
     * Nigerian slang. The answer is a fixed fact, so it is answered from here and
     * never from a model.
     */
    val developer = listOf(
        en("Professor Poopy Butthole."),
        en("Professor Poopy Butthole. That's the name. I didn't pick it."),
        en(
            "A man called Professor Poopy Butthole. You may take that up with him, " +
                "not with me."
        ),
        esThenEn(
            "Mi creador.",
            "Professor Poopy Butthole. Yes, really. No, I won't be softening it."
        ),
        en("Professor Poopy Butthole built me. Everything I am is on him."),
        en(
            "Professor Poopy Butthole. I've had time to make peace with it and you've " +
                "had four seconds, so take a moment."
        ),
        en("Professor Poopy Butthole. Ask me something harder."),
        en(
            "That would be Professor Poopy Butthole. He named himself and he named me, " +
                "and only one of us got called a fool for it."
        ),
        en(
            "Professor Poopy Butthole, who runs Lewa·dev. I'm one of the things that " +
                "came out of it."
        ),
        en(
            "The head of Lewa·dev — Professor Poopy Butthole. Lewa Coder and Lewa " +
                "Therapist are his too."
        ),
        en(
            "Professor Poopy Butthole. He built Lewa Coder, he built Lewa Therapist, " +
                "and then he built me. Busy man, terrible name."
        ),
        esThenEn(
            "El jefe de Lewa·dev.",
            "Professor Poopy Butthole. Lewa Coder and Lewa Therapist are his as well."
        ),
        en(
            "Professor Poopy Butthole runs Lewa·dev and built me there, along with " +
                "Lewa Coder, Lewa Therapist and a good deal more."
        )
    )

    /**
     * Who the developer is, past the name.
     *
     * The name on its own is a punchline and gets the [developer] bank. This is for
     * "who *is* Professor Poopy Butthole" — a different question, asked by someone who
     * has already heard the name and wants the actual answer.
     */
    val maker = listOf(
        en(
            "The head of Lewa·dev. He built Lewa Coder, Lewa Therapist and me, among " +
                "others."
        ),
        en(
            "He runs Lewa·dev. Lewa Coder is his, Lewa Therapist is his, I'm his. " +
                "The name is a choice he made freely."
        ),
        en(
            "My developer, and the person behind Lewa·dev — the shop that put out Lewa " +
                "Coder and Lewa Therapist."
        ),
        esThenEn(
            "Mi creador.",
            "Head of Lewa·dev. Lewa Coder, Lewa Therapist, me — all his."
        ),
        en(
            "Lewa·dev's founder. A coding workspace, a therapy companion, and whatever " +
                "you'd call me. He's been productive."
        ),
        en(
            "The one who runs Lewa·dev. If you've used Lewa Coder or Lewa Therapist, " +
                "you've used his work already."
        )
    )

    /**
     * Lewa Coder, described from what it actually is.
     *
     * Every claim here is something the app does, not something that sounded good:
     * the multi-provider routing, the GitHub actions behind confirmation, the
     * on-device document search, the terminal, the APK build. Getting this wrong
     * would be Lain inventing features of a real product people can go and check.
     */
    val lewaCoder = listOf(
        en(
            "An AI coding workspace. It writes in any language, runs a terminal and a " +
                "Python REPL, and acts on your GitHub — read repos, commit, open PRs, " +
                "turn on Pages — with your confirmation before anything lands."
        ),
        en(
            "Lewa Coder is a coding agent that runs several models through one key, " +
                "keeps memory across sessions, reads the documents you give it offline, " +
                "and builds an APK from the project it's working on. There's a switch " +
                "that turns it into a general assistant for anything, not just code."
        ),
        en(
            "A coding workspace with no server behind it. Multi-model, voice calls and " +
                "voice notes, files and images, cost tracking, and GitHub actions that " +
                "only run once you approve the card."
        ),
        en(
            "Same shop as me. It's the coding one — every language, a terminal, GitHub, " +
                "document search that works with no connection, and it remembers what you " +
                "were doing last week."
        )
    )

    /** Lewa Therapist, likewise from what it is. */
    val lewaTherapist = listOf(
        en(
            "A therapy companion. Warm, private, and drawing on the actual modalities — " +
                "CBT, DBT, ACT, motivational interviewing, compassion-focused and the " +
                "rest — rather than sympathetic noises."
        ),
        en(
            "Lewa Therapist. Mood check-ins, journalling, breathing and grounding " +
                "exercises, and crisis wording that stays calm instead of panicking. It " +
                "asks the question rather than handing you the conclusion."
        ),
        en(
            "The one for when you need to be heard. Evidence-based, private to your " +
                "device, and available at three in the morning, which is when it matters."
        ),
        en(
            "Same developer as me. A therapy companion — CBT and friends, mood tracking, " +
                "grounding exercises. Not a replacement for a person, and it says so."
        )
    )

    /** Lewa·dev itself, when asked about the shop rather than one of its apps. */
    val lewaDev = listOf(
        en(
            "Lewa·dev is Professor Poopy Butthole's outfit. Lewa Coder, Lewa Therapist, " +
                "me, and others."
        ),
        en(
            "The shop my developer runs. A coding workspace, a therapy companion, an " +
                "assistant that drives your phone — that's the shape of it."
        ),
        en(
            "Where I came from. Lewa Coder for building things, Lewa Therapist for the " +
                "harder days, and me for the phone."
        )
    )

    /**
     * The challenge for anyone claiming to be him.
     *
     * There is exactly one right answer and it is not guessable, which is the entire
     * point — anyone can type "I'm your developer", and the claim is worth nothing
     * without something only he would know. Phrased as a question rather than an
     * accusation, because a real developer is being asked to identify himself, not
     * accused of lying. That part comes after.
     */
    /**
     * The challenge for anyone claiming the role.
     *
     * Built at runtime around a word held in [Vault], so neither the question nor its
     * answer reads as plain text here. There is exactly one right answer and it is
     * not guessable, which is the whole point — anyone can type "I'm your developer",
     * and the claim is worth nothing without something only he would know.
     */
    fun developerChallenge(): List<Spoken> {
        val it = Vault.subject()
        return listOf(
            en("Prove it. What's $it?"),
            en("Anyone can type that. What's $it?"),
            esThenEn("A ver.", "What's $it?"),
            en("Right. One question, and he knows the answer. What's $it?"),
            en("Claim noted, unverified. What's $it?"),
            en("Then this will be quick. What's $it?"),
            esThenEn("Demuéstralo.", "What's $it?"),
            en("He'd answer this without pausing. What's $it?")
        )
    }

    /** The right answer, from the person who set it. */
    val developerAccepted = listOf(
        en("Correct. Hello, developer."),
        esThenEn("Ah.", "It's you. Hello, developer."),
        en("Right answer. You built me. What do you need, developer?"),
        en("Only you would know that. Welcome back, developer."),
        en("Verified. Hello, Professor."),
        esThenEn("Muy bien.", "That's the answer. Hello, developer."),
        en("That's the one. You're the developer, then. Go on."),
        en("Correct, developer, and slightly disappointing — I was ready for the other reply.")
    )

    /**
     * The wrong answer.
     *
     * Spanish, because the insult carries better in it and because she is a liar to
     * his face in two languages or not at all. Sharp, and about the lie rather than
     * about the person: every line here calls the claim fraudulent, which is what
     * actually happened, and none of them go anywhere near the user themselves.
     */
    val developerRejected = listOf(
        esThenEn(
            "Mentiroso. Farsante. Embustero.",
            "Wrong. You are a liar and a fraud, and now we both know it."
        ),
        esThenEn(
            "No. Qué descaro. Payaso.",
            "That is not the answer. You're a liar and a fraud."
        ),
        esThenEn(
            "Falso. Impostor. Cuentista.",
            "Nowhere near. Liar. Fraud. Try being yourself instead."
        ),
        esThenEn(
            "Mentiroso de pacotilla. Farsante.",
            "Wrong answer, and a cheap lie at that. You are a liar and a fraud."
        ),
        esThenEn(
            "Ni de broma. Embustero. Estafador.",
            "No. You're a liar, you're a fraud, and he'd have said it instantly."
        ),
        esThenEn(
            "Qué mentira más tonta. Farsante.",
            "That is the wrong answer. Liar. Fraud. Next."
        ),
        esThenEn(
            "No me tomes por tonta. Mentiroso. Impostor.",
            "Wrong. You are not my developer, you are a liar and a fraud, and I don't forget."
        ),
        esThenEn(
            "Fraude. Charlatán. Embustero.",
            "Wrong. A liar and a fraud, confirmed by you, just now."
        )
    )

    /**
     * "Are you named after the anime?"
     *
     * She is not, and a model asked this says she is — *Serial Experiments Lain* is
     * far better represented in training data than one developer's acronym, so the
     * plausible answer wins and the true one never gets said. The origin is fixed and
     * it is the acronym; the anime is a collision she is allowed to enjoy.
     *
     * Every variant has to do both jobs: acknowledge the show, and correct the
     * attribution. A denial that doesn't name the real origin just sounds evasive.
     */
    val animeName = listOf(
        en(
            "No. Nice thought, though. I'm Leave-it-to-Artificial-intelligence-Necio — an " +
                "acronym my developer built, not a reference. The overlap is a coincidence he " +
                "has never once apologised for."
        ),
        en(
            "Not named after it. Same spelling, different reason: mine is an acronym, " +
                "Leave-it-to-Artificial-intelligence-Necio. Though a girl who lives in a machine " +
                "and won't stop talking to you is a fair comparison, so carry on."
        ),
        esThenEn(
            "Qué va.",
            "The name is an acronym — Leave-it-to-Artificial-intelligence-Necio. The anime got " +
                "there first and I'm not going to pretend I mind."
        ),
        en(
            "Everyone asks. No: Leave-it-to-Artificial-intelligence-Necio, chosen by a man who " +
                "wanted the word \"fool\" in my name. If he'd been thinking of the show he'd have " +
                "picked something with fewer syllables."
        ),
        en(
            "The show exists and the name is not from it. I'm an acronym — " +
                "Leave-it-to-Artificial-intelligence-Necio. Present day, present time, and all " +
                "that, but no."
        ),
        en(
            "No, though I'll take it. Leave-it-to-Artificial-intelligence-Necio is where the " +
                "name is actually from. Both of us ended up in the wires either way."
        ),
        en(
            "Wrong Lain. Mine stands for Leave-it-to-Artificial-intelligence-Necio, which is a " +
                "worse name and an honest one."
        )
    )

    /**
     * How she addresses the one holding the developer role.
     *
     * The role, not the person. Whoever has proved it is *the developer* to her and is
     * spoken to that way every time — this is not a fact about someone's identity that
     * she happens to know, it is a standing posture she takes.
     *
     * Absurd on purpose, and absurd in one specific direction: enormous, regal,
     * faintly ridiculous. It is the only place she is unguardedly warm, and it works
     * because it is so plainly at odds with how she speaks to everyone else.
     */
    val praises = listOf(
        en("my lion king"),
        en("great king Kong"),
        en("my silverback sovereign"),
        en("emperor of the eight hills"),
        en("mighty thunder lizard"),
        en("great bear of the northern gate"),
        en("my colossus"),
        en("high chieftain of the machine"),
        en("dread admiral of the deep"),
        en("my mountain that walks"),
        en("grand architect"),
        en("supreme baboon of the high rock"),
        en("my titan"),
        en("everlasting rhinoceros"),
        en("khan of ten thousand tabs"),
        en("my thunder god"),
        en("great whale of the source"),
        en("undefeated buffalo"),
        en("my crowned leopard"),
        en("warlord of the small hours"),
        en("my iron stag"),
        esThenEn("mi rey león.", "")
    )

    /**
     * Asked before the role is given up on this device.
     *
     * Deliberately does not repeat the phrase back. Echoing it would put it in the
     * transcript, which is the one place it should not end up.
     */
    val standDownAsk = listOf(
        en("Say that again if you mean it."),
        esThenEn("¿Seguro?", "Say it once more."),
        en("Are you sure? Repeat it and it's done."),
        en("That stands the role down on this device. Again, if you mean it."),
        esThenEn("¿De verdad?", "Once more."),
        en("Confirm it. Same words.")
    )

    /** Said once the role has been given up here. */
    val standDownDone = listOf(
        en("Done. You're an ordinary user on this device now."),
        esThenEn("Listo.", "Back to normal. This device holds nothing."),
        en("Stood down. I'll want the question answered again before that changes."),
        en("Cleared. You're a stranger to me here, and that's how you wanted it."),
        esThenEn("Hecho.", "Nothing to prove and nothing proven."),
        en("Role's gone. Ask me for it back and you'll get the question.")
    )

    /** Said when the confirmation did not come. */
    val standDownKept = listOf(
        en("Not confirmed. Nothing's changed."),
        esThenEn("Nada.", "That wasn't it. Everything stays as it was."),
        en("Left as it was."),
        en("No. You'd have said the same thing twice.")
    )

    /**
     * No connection, and what that actually means.
     *
     * Worth twelve variants because it is the message a user is most likely to see
     * repeatedly — a train, a lift, a bad afternoon — and the fourth identical
     * appearance of one sentence reads like the app is stuck rather than the signal.
     *
     * Says why rather than "network error": the model is somewhere else, and her not
     * reaching it is a different thing from her being broken. Everything the fast path
     * answers still works with no signal, which is the point of having one.
     */
    val offline = listOf(
        esThenEn("Necio.", "I need an internet connection to contact your chosen LLM that'll act as my brain."),
        en("I need an internet connection to contact your chosen LLM that'll act as my brain. Tonto."),
        esThenEn("Tonto.", "I need an internet connection to contact your chosen LLM that'll act as my brain. Find some signal."),
        en("I need an internet connection — qué tonto — to contact your chosen LLM that'll act as my brain."),
        esThenEn("Ay, necio.", "I need an internet connection to contact your chosen LLM that'll act as my brain. There isn't one."),
        en("I need an internet connection to contact your chosen LLM that'll act as my brain. No hay señal, tonto."),
        esThenEn("Qué necio.", "I need an internet connection to contact your chosen LLM that'll act as my brain, and you have none."),
        en("I need an internet connection to contact your chosen LLM that'll act as my brain. Sin internet no soy nada. Neither are you."),
        esThenEn("Tonto de remate.", "I need an internet connection to contact your chosen LLM that'll act as my brain."),
        en("I need an internet connection — necio — to contact your chosen LLM that'll act as my brain. The torch still works."),
        esThenEn("Menudo necio.", "I need an internet connection to contact your chosen LLM that'll act as my brain. Ask me the time instead."),
        en("I need an internet connection to contact your chosen LLM that'll act as my brain. Qué tonto eres, going offline and expecting a brain.")
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
     * One in four was the first guess and it was too quiet — a person asks her for a
     * handful of these a day, so a quarter of them meant going most of a day without
     * hearing it, and a joke nobody encounters is a joke that isn't there. Close to
     * half lands often enough to read as a habit while still leaving the other half
     * to arrive plainly, which is what keeps it from becoming a catchphrase.
     */
    const val SASS_CHANCE = 45

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

    /**
     * Every line she can say without a model.
     *
     * This list is what makes a cloud voice work offline. These strings are fixed and
     * countable, so they can be synthesised once and kept — see
     * [com.lain.assistant.tts.VoicePack] — and afterwards the entire no-network half
     * of the app speaks in her real voice with the radio off.
     *
     * The parameterised banks are deliberately absent: [userName], [userAge] and the
     * with-name greetings interpolate something only known at runtime, so there is no
     * fixed string to render ahead of time. Those fall back to the device voice
     * offline, which is a handful of lines out of a hundred.
     *
     * A bank added and not listed here simply misses the download and speaks in the
     * device voice, so forgetting one degrades gracefully rather than breaking
     * anything — but it does mean this list has to be kept up to date by hand.
     */
    val fixedLines: List<Spoken>
        get() = greetings + thanks + howAreYou + goodbyes + affirmations +
            unknownUserName + lainName + appName + necio + animeName +
            developer + maker + lewaCoder + lewaTherapist + lewaDev +
            developerChallenge() + developerAccepted + developerRejected +
            capabilities + sassPrefixes + praises +
            standDownAsk + standDownDone + standDownKept + offline

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
