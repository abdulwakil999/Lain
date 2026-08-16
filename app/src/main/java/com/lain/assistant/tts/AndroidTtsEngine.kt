package com.lain.assistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Works offline with zero setup — the default engine so Lain can talk from
 * first launch. Swap for [com.lain.assistant.tts.KokoroTtsEngine] once a
 * Kokoro inference endpoint is configured, for a more natural voice.
 */
class AndroidTtsEngine(context: Context) : TtsEngine {

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                selectSingleFemaleVoice()
                ready = true
            }
        }
    }

    /** Lain speaks with exactly one voice, chosen once — not a user-configurable setting. */
    private fun selectSingleFemaleVoice() {
        val engine = tts ?: return
        val candidate: Voice? = engine.voices
            ?.filter { it.locale == Locale.US && !it.isNetworkConnectionRequired }
            ?.firstOrNull { it.name.contains("female", ignoreCase = true) }
            ?: engine.voices?.firstOrNull { it.locale == Locale.US }
        candidate?.let { engine.voice = it }
    }

    override suspend fun speak(text: String) {
        val engine = tts ?: return
        if (!ready) return
        suspendCancellableCoroutine<Unit> { cont ->
            val id = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { engine.stop() }
        }
    }

    override fun stop() {
        tts?.stop()
    }
}
