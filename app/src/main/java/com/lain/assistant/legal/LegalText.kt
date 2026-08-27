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

    const val VERSION = 1

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

        That provider is not us. Their handling of your data is governed by their
        privacy policy and the terms of the account your API key belongs to. Read
        them. If you use a free model, assume the provider may retain and train on
        what you send, because most free tiers do.

        Lain also contacts DuckDuckGo when you ask her to search the web, sending
        only the search terms.

        Nothing else is transmitted. Not your contacts list, not your files, not your
        location, not what you do on your phone when you have not asked her anything.

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
        Location is never requested. Lain can switch the location toggle on or off if
        you ask, but she cannot read where you are.

        5. CHILDREN
        Lain is not designed for children and should not be given to them. She can
        place calls, send messages and operate the phone.

        6. YOUR CONTROL
        Everything Lain remembers is listed in the app under "Knows", and anything
        there can be deleted individually or all at once. Conversations can be deleted
        message by message. Uninstalling removes everything.

        We cannot delete anything held by your AI provider — that is between you and
        them, through your account with them.

        7. CHANGES
        A changed policy is shown again before you continue using the app.

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

        5. NOT FOR EMERGENCIES
        Do not use Lain to call emergency services. Dial them yourself, directly. A
        call placed through an assistant can fail for reasons neither of you can see —
        a permission, a locked screen, a third-party dialler — and an emergency is
        not the moment to discover it.

        6. THE ACCESSIBILITY SERVICE
        Switching it on grants Lain the ability to read and control your screen. Grant
        it only if you understand that. It is optional; she works without it, with
        less reach.

        7. NO WARRANTY
        Lain is provided as is, with no warranty of any kind. She may stop working
        when Android changes, when a provider retires a model, or when a phone
        manufacturer decides to kill background apps. To the fullest extent the law
        allows, the developer is not liable for any loss arising from use of the app —
        including messages sent in error, calls placed in error, alarms that did not
        fire, or data a provider retained.

        Some jurisdictions do not allow limits like these, in which case they apply
        only as far as the law permits.

        8. AGE
        You must be old enough to enter a contract where you live, and old enough to
        hold the AI account whose key you are using.

        9. THIRD PARTIES
        Your AI provider's terms apply to your use of their model, in addition to
        these. Where they conflict about their service, theirs win.

        10. ENDING IT
        Uninstall whenever you like. That ends these terms and deletes your data from
        the phone.

        This document describes the software honestly. It is not legal advice.
    """.trimIndent()
}
