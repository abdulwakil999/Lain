package com.lain.assistant.automation

import android.content.Context
import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolResult

/**
 * "Text Ade on WhatsApp" as one action instead of eight.
 *
 * Driven step-by-step through the generic tools, that request cost a model round
 * trip per step — look up the contact, open the app, read the screen, tap the
 * composer, type, read again, find Send, tap it, read again to confirm — with a
 * screen dump shipped through the model each time. On a free model that is
 * minutes of wall-clock for something the phone itself does instantly.
 *
 * The sequence never actually varies, so it doesn't need a language model in the
 * loop: it's encoded here and runs locally at device speed. The model makes one
 * call and gets back one verified answer. Each step still checks what actually
 * happened, so a failure reports where it stopped rather than claiming success.
 */
class MessageFlow(private val context: Context) {

    private val phone = PhoneController(context)

    enum class Channel { AUTO, WHATSAPP, TELEGRAM, SIGNAL, SMS }

    /**
     * @param recipient a contact name or a phone number
     * @param message   what to send
     */
    suspend fun send(recipient: String, message: String, channel: Channel): ToolResult {
        if (message.isBlank()) {
            return ToolResult.fail(FailureKind.INVALID_INPUT, "No message text was given.")
        }

        val number = when (val resolved = resolveRecipient(recipient)) {
            is Recipient.Number -> resolved.number
            is Recipient.Ambiguous -> return ToolResult.fail(
                FailureKind.INVALID_INPUT,
                // Two different people, similarly named. Picking one and messaging
                // them is not recoverable, so the model is told to ask.
                "\"$recipient\" matches more than one contact:\n" +
                    resolved.options.joinToString("\n") { "- ${it.name} (${it.number})" } +
                    "\nAsk the user which one they meant. Do not guess.",
                data = mapOf("candidates" to resolved.options.joinToString(", ") { it.name })
            )
            Recipient.NoPermission -> return ToolResult.fail(
                FailureKind.PERMISSION,
                "Looking up \"$recipient\" needs contacts permission, which hasn't been granted."
            )
            Recipient.None -> return ToolResult.fail(
                FailureKind.INVALID_INPUT,
                "No contact matching \"$recipient\", and it isn't a phone number either. " +
                    "Ask the user for the number, or call lookup_contact with a different spelling."
            )
        }

        return when (channel) {
            Channel.SMS -> sendSms(number, message)
            Channel.WHATSAPP -> sendThrough("whatsapp", number, message)
            Channel.TELEGRAM -> sendThrough("telegram", number, message)
            Channel.SIGNAL -> sendThrough("signal", number, message)
            // SMS is the one channel that completes without touching the UI at all,
            // so it's the default when the user didn't name an app.
            Channel.AUTO -> sendSms(number, message)
        }
    }

    sealed class Recipient {
        data class Number(val number: String) : Recipient()
        data class Ambiguous(val options: List<PhoneController.Contact>) : Recipient()
        object None : Recipient()
        object NoPermission : Recipient()
    }

    /**
     * Turns what the user said into a number to message.
     *
     * Digits are taken at face value. A name goes through fuzzy contact matching,
     * and an ambiguous name is surfaced rather than resolved — this used to take
     * whichever row the provider happened to return first, which is how "text Moyo"
     * could reach a different Moyo entirely.
     */
    fun resolveRecipient(recipient: String): Recipient {
        val trimmed = recipient.trim()
        val digitsOnly = trimmed.replace(Regex("[\\s()\\-.]"), "")
        if (digitsOnly.matches(Regex("\\+?\\d{5,15}"))) return Recipient.Number(digitsOnly)

        return when (val match = phone.resolveContact(trimmed)) {
            is PhoneController.ContactMatch.One ->
                Recipient.Number(match.contact.number.replace(Regex("[\\s()\\-.]"), ""))
            is PhoneController.ContactMatch.Several -> Recipient.Ambiguous(match.contacts)
            PhoneController.ContactMatch.NoPermission -> Recipient.NoPermission
            PhoneController.ContactMatch.None -> Recipient.None
        }
    }

    private fun sendSms(number: String, message: String): ToolResult =
        when (val result = phone.sendSms(number, message)) {
            is AutomationResult.Success -> ToolResult.ok("Sent to $number by SMS: \"$message\"")
            is AutomationResult.Failure -> ToolResult.fail(FailureKind.TOOL_FAILURE, result.reason)
            is AutomationResult.MissingPermission ->
                ToolResult.fail(FailureKind.PERMISSION, "SMS needs the ${result.permission} permission, which isn't granted.")
        }

    /**
     * Sends through a chat app by driving its UI, in one go rather than a tap at a time.
     *
     * None of these expose a send API, so the sequence is: open the conversation,
     * put the text in, find Send, press it, then check whether the text left the
     * composer. Every step reports what actually happened — the point of doing it
     * here rather than through the model is that the checks are not optional.
     *
     * [Messengers] supplies the package and the link, which is where the WhatsApp
     * bug lived: the package was a constant, so a phone with WhatsApp Business and
     * no consumer WhatsApp was told the app was not installed.
     */
    private suspend fun sendThrough(key: String, number: String, message: String): ToolResult {
        val app = Messengers.byKey(key)
            ?: return ToolResult.fail(FailureKind.INVALID_INPUT, "Lain doesn't send through \"$key\".")

        val intent = Messengers.composeIntent(context, app, number, message)
            ?: return ToolResult.fail(
                FailureKind.APP_UNAVAILABLE,
                other(app)?.let {
                    "${app.label} isn't installed on this phone. $it is — offer that, or SMS."
                } ?: "${app.label} isn't installed on this phone. SMS will work."
            )

        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (!opened) {
            return ToolResult.fail(
                FailureKind.APP_UNAVAILABLE,
                "${app.label} is installed but refused to open the chat with $number."
            )
        }

        val service = LainAccessibilityService.instance
            ?: return ToolResult.ok(
                if (app.compose == Messengers.Compose.PREFILLED) {
                    "${app.label} is open with the message drafted to $number, but Lain can't tap Send — " +
                        "the Accessibility Service isn't connected. The user needs to tap Send themselves, " +
                        "or switch the service on."
                } else {
                    "${app.label} is open on the chat with $number, but nothing has been typed — ${app.label} " +
                        "has no way to prefill a message, and typing it needs the Accessibility Service, " +
                        "which isn't connected. The user needs to type and send it themselves."
                }
            )

        // The deep link has to resolve and load the thread before anything is there
        // to type into or tap.
        service.awaitSettle(maxWait = 4000L)

        if (app.compose == Messengers.Compose.TYPED) {
            if (!service.typeText(message)) {
                return ToolResult.fail(
                    FailureKind.TOOL_FAILURE,
                    "${app.label} is open on the chat with $number but the message couldn't be typed — " +
                        "no editable field was found. Nothing has been sent.",
                    data = mapOf("screen" to service.readScreenCompact())
                )
            }
            service.awaitSettle(maxWait = 1500L)
        }

        // Send is a content-description on every one of these; the composer's IME
        // action is the fallback for builds that don't expose it.
        val tapped = app.sendLabels.any { service.tapByText(it) } || service.pressImeAction()
        if (!tapped) {
            return ToolResult.fail(
                FailureKind.TOOL_FAILURE,
                "The message to $number is typed into ${app.label} but the Send control couldn't be found. " +
                    "Call read_screen to see what's there and tap it directly.",
                data = mapOf("screen" to service.readScreenCompact())
            )
        }

        service.awaitSettle(maxWait = 2500L)

        // Verify the right thing. A sent message stays visible in the thread, so "is
        // the text still on screen" would report failure for every successful send.
        // What distinguishes the two is whether it is still in the *composer*, so
        // only [INPUT] lines are examined.
        val screen = service.readScreenCompact()
        val needle = message.take(30).lowercase()
        val stillInComposer = screen.lineSequence()
            .filter { it.startsWith("[INPUT]") }
            .any { it.lowercase().contains(needle) }

        return if (stillInComposer) {
            ToolResult.fail(
                FailureKind.TOOL_FAILURE,
                "Tapped Send but the message is still sitting in the ${app.label} composer for $number — " +
                    "it did not go.",
                data = mapOf("screen" to screen)
            )
        } else {
            ToolResult.ok("Sent to $number on ${app.label}: \"$message\"")
        }
    }

    /** Another chat app that is installed, for suggesting when the asked-for one isn't. */
    private fun other(missing: Messengers.Messenger): String? =
        Messengers.installed(context).firstOrNull { it.key != missing.key }?.label

}
