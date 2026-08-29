package com.lain.assistant.legal

/**
 * Lain's privacy policy and terms, held in the app rather than on a website.
 *
 * In the app because the app works offline for a great deal of what it does, and a
 * policy behind a link is a policy nobody can read on a train. Every claim below
 * was written against the code that implements it, not against what the app is
 * meant to do — the effective privacy behaviour of an assistant with Accessibility
 * access is exactly the thing users cannot verify for themselves, so the least it
 * can do is describe itself accurately.
 *
 * Not legal advice, and it says so. Anyone shipping this commercially should have
 * a lawyer read it against their jurisdiction.
 */
object LegalText {

    /**
     * Bumped whenever the meaning of either document changes, never for a typo.
     *
     * The policy promises that a changed policy is shown again before you carry on
     * using the app, and this number is how that promise is kept: the version you
     * accepted is stored, and a higher one here puts the documents back in front of
     * you. A promise in a privacy policy that nothing in the code implements is
     * exactly the kind of claim this file exists not to make.
     *
     * 2 — location is now requested and can be read; code and schoolwork; permissions
     *     are asked for once; "Knows" is now "Memoria".
     */
    const val VERSION = 2

    /**
     * What changed since the last version, shown above the documents on the re-consent
     * screen.
     *
     * Present because "the policy has been updated, please re-accept" with three
     * thousand words underneath is how nobody reads a policy twice. The list is short
     * enough to actually be read, and every line names something that genuinely
     * changed rather than restating what the app already did.
     */
    val WHATS_CHANGED = """
        WHAT CHANGED

        Location. Lain now asks for coarse location, once, and uses it for one thing:
        answering "where am I". The previous policy said location was never requested.
        That is no longer true, so this is being put back in front of you. Refusing it
        costs you that one answer and nothing else.

        Permissions are asked for once. Anything you decline is not asked for again on
        the next launch. Where a feature needs something you refused, she says so.

        Code and schoolwork. Lain will now write code and help with assignments. Those
        questions go to your AI provider like any other, so treat anything you paste
        into them the same way.

        "Knows" is now called "Memoria". Same screen, same data, same delete buttons.
    """.trimIndent()

    val PRIVACY = """
        PRIVACY POLICY

        Last updated: this version of the app.

        The short version
        Lain keeps your data on your phone. There are no accounts, no analytics, no
        advertising and no servers belonging to us — because there are no servers
        belonging to us at all. The one place your words leave the device is the AI
        provider you choose and give your own API key to.

        1. WHAT STAYS ON YOUR PHONE
        Your name, nickname, age and gender, entered during setup.
        Your conversations with Lain.
        What Lain remembers about you — facts she saves as you talk.
        Your alarms, reminders and scheduled tasks.
        Notes you ask her to keep.
        Your API key, held in Android's encrypted storage.

        All of it lives in this app's private storage. Backup to the cloud and
        device-to-device transfer are both switched off, so none of it is copied
        anywhere by Android either. Uninstalling the app deletes every bit of it.

        2. WHAT LEAVES YOUR PHONE, AND WHERE IT GOES
        When you send Lain something she cannot answer on the device, she sends it to
        the AI provider you selected in Settings — OpenRouter, Anthropic, OpenAI,
        Google or xAI — using your API key. What goes with it:

          - Your message.
          - Recent conversation, so the reply makes sense in context.
          - Facts she has remembered that look relevant to what you asked.
          - Your name and nickname, so she can address you.
          - When you ask her to do something with your screen: what is on it, as text.
          - When you ask her to look at your screen and the model can see images: a
            screenshot.
          - When you attach a file: the file, or the text in it.
          - When you ask her to write code or help with schoolwork: the question, and
            any code or coursework you paste into it.
          - When you ask where you are during a task she is carrying out for you: the
            description she worked out, in words ("Yaba, Lagos"), never raw
            coordinates. Asking her "where am I" on its own is answered on the device
            and sent nowhere.

        That provider is not us. Their handling of your data is governed by their
        privacy policy and the terms of the account your API key belongs to. Read
        them. If you use a free model, assume the provider may retain and train on
        what you send, because most free tiers do.

        Lain also contacts DuckDuckGo when you ask her to search the web, sending
        only the search terms.

        Nothing else is transmitted. Not your contacts list, not your files, not your
        coordinates, and nothing at all about what you do on your phone when you have
        not asked her something.

        3. THE ACCESSIBILITY SERVICE
        This is the permission that deserves the most explanation, because it is the
        most powerful one on the phone.

        With it switched on, Lain can read what is on your screen and tap, type and
        swipe on it. That is how she operates other apps for you, and how she works
        for someone who cannot see the screen.

        She reads the screen only while carrying out something you asked for. She
        does not run in the background collecting what you look at, does not log
        screen contents to storage, and does not send screen contents anywhere except
        to your chosen AI provider during a task that needs them. You can switch the
        service off at any time in Android Settings and everything else keeps working.

        4. OTHER PERMISSIONS, AND WHY
        Microphone — speech input. Recognition is done by Android's own recogniser,
        which on many phones means Google processes the audio; Lain asks for on-device
        recognition where the phone supports it. She does not record audio to a file.
        Contacts — to turn "call Ade" into a number. Read when asked, never uploaded
        as a list; a number resolved this way may go to the AI provider as part of the
        request.
        Phone and SMS — to place calls and send texts you asked for.
        Camera — only when you take a photo to show her.
        Notifications — to show alarms and reminders. Notification access, if you
        grant it, lets her read your notification shade when you ask what you missed.
        Location — coarse only, and only to answer "where am I". She reads the fix
        Android already has rather than switching the GPS on, turns it into a place
        name using the phone's own geocoder, and stores nothing. If you decline it,
        that one question stops working and everything else carries on. She can also
        switch the location toggle on or off when you ask, which needs no permission
        at all.

        Each of these is asked for once. Decline one and Lain does not ask again on the
        next launch; where something you refused is needed, she tells you what is
        missing instead of demanding it again. You can grant or revoke any of them
        later in Android Settings.

        5. CHILDREN
        Lain is not designed for children and should not be given to them. She can
        place calls, send messages and operate the phone.

        6. YOUR CONTROL
        Everything Lain remembers is listed in the app under "Memoria", and anything
        there can be deleted individually or all at once. Conversations can be deleted
        message by message. Uninstalling removes everything.

        We cannot delete anything held by your AI provider — that is between you and
        them, through your account with them.

        7. CHANGES
        A changed policy is shown again before you continue using the app. The version
        you accepted is recorded on the device; when this document changes in a way
        that matters, it reappears with a summary of what changed at the top.

        8. CONTACT
        Lain is made by an independent developer. Questions go to the address on the
        page you downloaded it from.

        This document describes the software honestly. It is not legal advice.
    """.trimIndent()

    val TERMS = """
        TERMS OF SERVICE

        By using Lain you agree to these terms. If you do not, do not use the app.

        1. WHAT LAIN IS
        An assistant that runs on your phone and can operate it on your behalf. She
        uses an AI model from a provider you choose, paid for with an API key you
        supply.

        2. YOUR API KEY AND YOUR COSTS
        You bring your own key. Whatever it costs to use is between you and that
        provider. Lain shows you which models are free, but a free tier is theirs to
        change or withdraw and nothing here guarantees one stays free. You are
        responsible for keeping your key safe and for anything done with it.

        3. WHAT YOU ARE RESPONSIBLE FOR
        Lain does what you tell her. If you tell her to send a message, she sends it.
        If you tell her to call someone, she rings them. You are responsible for what
        you instruct and for anything that follows.

        Actions that reach other people or cannot be undone are held until you
        confirm. That is a safeguard, not a guarantee — check what she is about to do.

        4. SHE WILL SOMETIMES BE WRONG
        AI models make mistakes, and the free ones make more of them. Lain is built to
        report honestly when something failed and to avoid claiming success she cannot
        verify, but she can still misread a screen, tap the wrong thing or
        misunderstand you.

        Do not rely on her for anything where being wrong matters: medical, legal or
        financial decisions, or anything with a deadline you cannot afford to miss.

        Code she writes has not been run. Read it before you run it, and never paste
        anything that deletes, sends or spends without understanding it first. She can
        invent a function that does not exist in a library, and on a free model she
        does it more often.

        5. SCHOOLWORK
        She will explain a method, work a problem through and outline an essay. What
        you then submit is yours, and so is the consequence. Most schools and
        universities have rules about AI, they differ, and they are yours to know and
        follow. Lain is a tutor, not a ghostwriter, and neither she nor the developer
        is responsible for how you use what she produces.

        6. NOT FOR EMERGENCIES
        Do not use Lain to call emergency services. Dial them yourself, directly. A
        call placed through an assistant can fail for reasons neither of you can see —
        a permission, a locked screen, a third-party dialler — and an emergency is
        not the moment to discover it.

        7. THE ACCESSIBILITY SERVICE
        Switching it on grants Lain the ability to read and control your screen. Grant
        it only if you understand that. It is optional; she works without it, with
        less reach.

        8. NO WARRANTY
        Lain is provided as is, with no warranty of any kind. She may stop working
        when Android changes, when a provider retires a model, or when a phone
        manufacturer decides to kill background apps. To the fullest extent the law
        allows, the developer is not liable for any loss arising from use of the app —
        including messages sent in error, calls placed in error, alarms that did not
        fire, or data a provider retained.

        Some jurisdictions do not allow limits like these, in which case they apply
        only as far as the law permits.

        9. AGE
        You must be old enough to enter a contract where you live, and old enough to
        hold the AI account whose key you are using.

        10. THIRD PARTIES
        Your AI provider's terms apply to your use of their model, in addition to
        these. Where they conflict about their service, theirs win.

        11. HOW SHE TALKS

        Lain's name is short for Leave-it-to-Artificial-intelligence-Necio, and necio
        is Spanish for fool. She is written to be blunt, and when you ask her for
        something you could plainly have done yourself she will occasionally say so —
        sometimes in Spanish. It is a joke about the name, aimed at nobody but the
        person who chose to use her, and it never comes instead of doing the thing. If
        that is not what you want from an assistant, this is not the app.

        12. ENDING IT
        Uninstall whenever you like. That ends these terms and deletes your data from
        the phone.

        This document describes the software honestly. It is not legal advice.
    """.trimIndent()
}
