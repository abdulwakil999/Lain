package com.lain.assistant.tts

interface TtsEngine {
    suspend fun speak(text: String)
    fun stop()
    suspend fun warmUp() {}
}
