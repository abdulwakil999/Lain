package com.lain.assistant.data

import java.util.UUID

enum class Sender { USER, LAIN }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: Sender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isThinking: Boolean = false,
    /**
     * The model's working, when it produced any.
     *
     * Kept beside the reply rather than mixed into it, so the transcript shows an
     * answer and the working stays folded away behind a tap. Never spoken: hearing
     * a paragraph of deliberation read aloud before the actual answer is the worst
     * possible version of this, and it is the one hands-free users would get.
     */
    val monologue: String? = null
)
