package com.lain.assistant.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Note(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class Reminder(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val triggerAtMillis: Long
)
