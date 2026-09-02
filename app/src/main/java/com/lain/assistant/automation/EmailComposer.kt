package com.lain.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolResult

/**
 * Email, as far as Android actually allows.
 *
 * Composing works everywhere: `ACTION_SENDTO` with a `mailto:` URI opens whichever
 * client the user uses, with the fields filled in, and they press send. That is a
 * deliberate stopping point rather than a limitation to route around — an assistant
 * that can send mail from your address without you seeing it is a different and much
 * more dangerous thing than one that writes the draft.
 *
 * Reading an inbox is not possible from here at all. There is no system-wide mail
 * provider on Android the way there is for calendar or contacts; each client keeps
 * its own store, and getting at Gmail means Google's API, an OAuth consent screen and
 * a verification process. That is a real piece of work rather than a missing line,
 * and [readInbox] says so instead of failing in a way that looks like a bug.
 */
class EmailComposer(private val context: Context) {

    fun compose(to: String, subject: String, body: String): ToolResult {
        val recipients = to.split(",", ";").map { it.trim() }.filter { it.isNotBlank() }
        if (recipients.isEmpty()) {
            return ToolResult.fail(FailureKind.INVALID_INPUT, "No address to send to.")
        }
        val malformed = recipients.filterNot { it.contains("@") && it.length > 3 }
        if (malformed.isNotEmpty()) {
            return ToolResult.fail(
                FailureKind.INVALID_INPUT,
                "That doesn't look like an email address: ${malformed.joinToString(", ")}"
            )
        }

        return runCatching {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                // mailto: rather than ACTION_SEND, so only mail clients are offered.
                // ACTION_SEND puts every messaging app on the phone in the chooser and
                // makes it easy to send an email to the wrong place entirely.
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, recipients.toTypedArray())
                putExtra(Intent.EXTRA_SUBJECT, subject.take(200))
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.ok(
                "Opened a draft to ${recipients.joinToString(", ")}. It's written and waiting — " +
                    "press send yourself. I can't send mail from your address."
            )
        }.getOrElse {
            ToolResult.fail(FailureKind.APP_UNAVAILABLE, "No email app is set up on this phone.")
        }
    }

    /** Named so the limit is discoverable, rather than the tool merely being absent. */
    fun readInbox(): ToolResult = ToolResult.fail(
        FailureKind.CAPABILITY_UNAVAILABLE,
        "I can't read email. Android has no shared mail store — every client keeps its own — so " +
            "reading an inbox would need that provider's API and a sign-in, which Lain doesn't have. " +
            "I can write a draft and open it for you."
    )
}
