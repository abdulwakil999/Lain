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
class MessageFlow(context: Context) {

    private val phone = PhoneController(context)

    enum class Channel { AUTO, WHATSAPP, SMS }

    /**
     * @param recipient a contact name or a phone number
     * @param message   what to send
     */
    suspend fun send(recipient: String, message: String, channel: Channel): ToolResult {
        if (message.isBlank()) {
            return ToolResult.fail(FailureKind.INVALID_INPUT, "No message text was given.")
        }

        val number = resolveNumber(recipient)
            ?: return ToolResult.fail(
                FailureKind.INVALID_INPUT,
                "No contact matching \"$recipient\", and it isn't a phone number either. " +
                    "Ask the user for the number, or call lookup_contact with a different spelling."
            )

        return when (channel) {
            Channel.SMS -> sendSms(number, message)
            Channel.WHATSAPP -> sendWhatsApp(number, message)
            // SMS is the one channel that completes without touching the UI at all,
            // so it's the default when the user didn't name an app.
            Channel.AUTO -> sendSms(number, message)
        }
    }

    private fun resolveNumber(recipient: String): String? {
        val trimmed = recipient.trim()
        val digitsOnly = trimmed.replace(Regex("[\\s()\\-.]"), "")
        if (digitsOnly.matches(Regex("\\+?\\d{5,15}"))) return digitsOnly

        val lookup = phone.lookupContact(trimmed)
        if (lookup !is AutomationResult.Success) return null
        // lookupContact returns "Name: number" lines; take the first match's number.
        return lookup.message.lineSequence().firstOrNull()
            ?.substringAfterLast(':')
            ?.trim()
            ?.replace(Regex("[\\s()\\-.]"), "")
            ?.takeIf { it.isNotBlank() }
    }

    private fun sendSms(number: String, message: String): ToolResult =
        when (val result = phone.sendSms(number, message)) {
            is AutomationResult.Success -> ToolResult.ok("Sent to $number by SMS: \"$message\"")
            is AutomationResult.Failure -> ToolResult.fail(FailureKind.TOOL_FAILURE, result.reason)
            is AutomationResult.MissingPermission ->
                ToolResult.fail(FailureKind.PERMISSION, "SMS needs the ${result.permission} permission, which isn't granted.")
        }

    /**
     * WhatsApp exposes no send API, so this drives its UI — but drives all of it in
     * one go rather than handing each tap back to the model.
     */
    private suspend fun sendWhatsApp(number: String, message: String): ToolResult {
        val opened = phone.openWhatsAppChat(number, message)
        if (opened !is AutomationResult.Success) {
            val reason = (opened as? AutomationResult.Failure)?.reason ?: "WhatsApp couldn't be opened"
            return ToolResult.fail(FailureKind.APP_UNAVAILABLE, reason)
        }

        val service = LainAccessibilityService.instance
            ?: return ToolResult.ok(
                "WhatsApp is open with the message drafted to $number, but Lain can't tap Send — the Accessibility " +
                    "Service isn't connected. The user needs to tap Send themselves, or switch the service on."
            )

        // The deep link has to resolve, load the thread and fill the composer.
        service.awaitSettle(maxWait = 4000L)

        // "Send" is the content-description on WhatsApp's send button; the composer's
        // IME action is the fallback for builds that don't expose it.
        val tapped = service.tapByText("Send") || service.pressImeAction()
        if (!tapped) {
            return ToolResult.fail(
                FailureKind.TOOL_FAILURE,
                "The message to $number is typed into WhatsApp but the Send control couldn't be found. " +
                    "Call read_screen to see what's there and tap it directly.",
                data = mapOf("screen" to service.readScreenCompact())
            )
        }

        service.awaitSettle(maxWait = 2500L)

        // Verify rather than assume — but verify the right thing. A sent message stays
        // visible in the thread, so "is the text still on screen" would report failure
        // for every successful send. What actually distinguishes the two is whether the
        // text is still sitting in the *composer*, so only [INPUT] lines are examined.
        val screen = service.readScreenCompact()
        val needle = message.take(30).lowercase()
        val stillInComposer = screen.lineSequence()
            .filter { it.startsWith("[INPUT]") }
            .any { it.lowercase().contains(needle) }

        return if (stillInComposer) {
            ToolResult.fail(
                FailureKind.TOOL_FAILURE,
                "Tapped Send but the message is still sitting in the WhatsApp composer for $number — it did not go.",
                data = mapOf("screen" to screen)
            )
        } else {
            ToolResult.ok("Sent to $number on WhatsApp: \"$message\"")
        }
    }
}
