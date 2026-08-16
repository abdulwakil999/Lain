package com.lain.assistant.data

import java.util.UUID

enum class Sender { USER, LAIN }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: Sender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isThinking: Boolean = false
)
