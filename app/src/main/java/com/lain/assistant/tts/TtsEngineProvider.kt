package com.lain.assistant.tts

import android.content.Context

object TtsEngineProvider {
    fun create(context: Context, kokoroEndpoint: String?): TtsEngine =
        if (!kokoroEndpoint.isNullOrBlank()) {
            KokoroTtsEngine(context, kokoroEndpoint)
        } else {
            AndroidTtsEngine(context)
        }
}
