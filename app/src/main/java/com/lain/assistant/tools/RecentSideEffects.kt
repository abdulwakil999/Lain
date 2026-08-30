package com.lain.assistant.tools

/**
 * Stops the same outward action happening twice in a row.
 *
 * The failure this exists for is not the user tapping send twice. It is the agent
 * loop: a model calls `send_sms`, the result comes back, the model does not
 * recognise it as a success, and it calls `send_sms` again with identical
 * arguments. The tool works perfectly both times. Someone receives the same message
 * twice, or gets rung twice, and there is nothing in the transcript that looks like
 * an error — which is why it survives testing and shows up in real use.
 *
 * Only actions that reach outside the phone are guarded, plus deletion. Opening an
 * app twice is a wasted second; sending a message twice cannot be taken back.
 *
 * A short window on purpose. Someone deliberately sending the same words to the
 * same person half a minute later is doing it knowingly, and blocking that would be
 * the tool deciding it knows better than the person holding the phone. Twenty
 * seconds catches the loop and almost nothing else.
 */
object RecentSideEffects {

    /** Actions that cannot be undone by doing them again. */
    private val GUARDED = setOf(
        "send_sms", "make_call", "message_contact", "send_whatsapp_message", "delete_file"
    )

    private const val WINDOW_MS = 20_000L

    private class Done(val signature: String, val at: Long)

    @Volatile
    private var last: Done? = null

    /**
     * @return a result to return instead of acting, or null to go ahead.
     */
    fun duplicate(name: String, argumentsJson: String): ToolResult? {
        if (name !in GUARDED) return null
        val previous = last ?: return null
        if (previous.signature != signature(name, argumentsJson)) return null

        val elapsed = System.currentTimeMillis() - previous.at
        if (elapsed > WINDOW_MS) return null

        // Reported as a success, because the thing the user wanted did happen — it
        // happened a moment ago. Calling it a failure would have the model tell them
        // their message did not go out, which is the opposite of the truth.
        return ToolResult.ok(
            "Not repeated: an identical $name went through ${elapsed / 1000} seconds ago and " +
                "succeeded. It has already happened — say so, and do not call this again."
        )
    }

    /** Called only after the action actually succeeded. */
    fun record(name: String, argumentsJson: String) {
        if (name !in GUARDED) return
        last = Done(signature(name, argumentsJson), System.currentTimeMillis())
    }

    /**
     * Cleared when a turn ends, so the guard never spans two separate requests.
     *
     * Without this, a user who asks for the same thing twice in quick succession —
     * "text him again" — would be refused by a window that was meant to catch a
     * model repeating itself inside one task.
     */
    fun clear() {
        last = null
    }

    /** Whitespace-insensitive, so identical arguments formatted differently still match. */
    private fun signature(name: String, argumentsJson: String): String =
        name + "|" + argumentsJson.filterNot { it.isWhitespace() }
}
