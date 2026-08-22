package com.lain.assistant.agent

import com.lain.assistant.data.ChatMessage
import com.lain.assistant.data.Sender

/**
 * Which request a "send again" should actually re-run.
 *
 * Pulled out of the engine because it is the one part of the message actions with
 * a decision in it, and the decision is easy to get subtly wrong: resending one of
 * Lain's replies has to re-run the question that produced it, not the reply text —
 * feeding her own answer back as a fresh request produces a confident non-sequitur
 * rather than a second attempt.
 */
object MessageActions {

    /**
     * @return the text to send, or null when there is nothing sensible to re-run —
     *         an opening greeting from Lain with no question before it, or a
     *         message that is no longer in the transcript.
     */
    fun resendTarget(messages: List<ChatMessage>, messageId: String): String? {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return null

        val text = if (messages[index].sender == Sender.USER) {
            messages[index].text
        } else {
            // The user turn this reply was answering.
            messages.take(index).lastOrNull { it.sender == Sender.USER }?.text
        }
        return text?.takeIf { it.isNotBlank() }
    }
}
