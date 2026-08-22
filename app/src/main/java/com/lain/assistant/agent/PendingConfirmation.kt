package com.lain.assistant.agent

import com.lain.assistant.network.ToolCall

/**
 * An action that is about to do something the user cannot take back, held until
 * they say yes.
 *
 * [com.lain.assistant.tools.ToolMeta.requiresConfirmation] has always described
 * which tools these are, and nothing ever read it — so a model that decided to
 * ring someone simply rang them. On a strong model that is rare; on a weak one
 * misreading "should I text Ade?" as an instruction is a genuinely likely
 * failure, and the cost is a real phone call to a real person.
 *
 * Reading the screen or checking the battery is not on this list. The bar is
 * whether an outsider notices: a call placed, a message delivered, a file
 * destroyed. Everything else stays frictionless, because a confirmation prompt on
 * a harmless action trains people to tap through the ones that matter.
 */
data class PendingConfirmation(
    val call: ToolCall,
    /** Plain-language description of exactly what will happen, for the user to approve. */
    val summary: String,
    /** The conversation this belongs to, so a stale prompt can't fire into a new one. */
    val conversationId: String
) {
    companion object {

        /**
         * Describes the call in the terms the user cares about — who, what, where —
         * rather than as a function signature.
         *
         * Deliberately concrete: "Send this to +234…?" invites a real decision in a
         * way that "Confirm send_sms?" does not.
         */
        fun describe(call: ToolCall, args: Map<String, String>): String {
            fun arg(vararg keys: String): String =
                keys.firstNotNullOfOrNull { args[it]?.takeIf { v -> v.isNotBlank() } } ?: ""

            return when (call.name) {
                "make_call" -> {
                    val who = arg("phone_number", "contact", "name")
                    "Call $who?"
                }

                "send_sms" -> {
                    val who = arg("phone_number", "contact")
                    val text = arg("message")
                    "Text $who: \"${text.take(140)}\"?"
                }

                "message_contact" -> {
                    val who = arg("contact", "phone_number")
                    val text = arg("message")
                    val via = arg("app").takeIf { it.isNotBlank() }?.let { " on $it" } ?: ""
                    "Message $who$via: \"${text.take(140)}\"?"
                }

                "send_whatsapp_message" -> {
                    val who = arg("phone_number", "contact")
                    "Open WhatsApp to $who with a message drafted?"
                }

                "delete_file" -> "Delete \"${arg("path")}\"? This can't be undone."

                "write_file" -> {
                    val path = arg("path")
                    if (args["append"]?.toBooleanStrictOrNull() == true) {
                        "Append to \"$path\"?"
                    } else {
                        "Overwrite \"$path\"?"
                    }
                }

                "forget" -> "Forget everything matching \"${arg("key", "query")}\"?"

                else -> "Go ahead with ${call.name.replace('_', ' ')}?"
            }
        }

        /**
         * Whether the user's reply is an approval.
         *
         * Only an affirmative counts. Silence, a follow-up question, or a changed
         * subject all mean no — the safe reading of ambiguity here is "don't", since
         * declining costs one more message and approving wrongly costs a phone call
         * to somebody.
         */
        fun isApproval(reply: String): Boolean {
            val t = reply.trim().lowercase().trimEnd('.', '!', ' ')
            if (t.isEmpty()) return false
            // A negation anywhere overrides an otherwise affirmative-looking phrase,
            // so "yes, but don't send it" is not an approval.
            if (NEGATIONS.any { t.contains(it) }) return false
            return t in AFFIRMATIVES || AFFIRMATIVE_PREFIXES.any { t.startsWith(it) }
        }

        private val AFFIRMATIVES = setOf(
            "y", "yes", "yeah", "yep", "yup", "ya", "sure", "ok", "okay", "k",
            "go", "go ahead", "do it", "send it", "send", "confirm", "confirmed",
            "please do", "affirmative", "correct", "right", "alright", "aye"
        )

        private val AFFIRMATIVE_PREFIXES = listOf(
            "yes ", "yeah ", "yep ", "sure ", "ok ", "okay ", "go ahead", "do it", "send it"
        )

        private val NEGATIONS = listOf(
            "don't", "dont", "do not", "no ", "nope", "cancel", "stop", "wait",
            "not yet", "hold on", "nevermind", "never mind", "abort"
        )
    }
}
