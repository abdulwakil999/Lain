package com.lain.assistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.lain.assistant.agent.LainName
import com.lain.assistant.agent.Language
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

    /** Lain speaks with exactly one English voice, chosen once — not a user setting. */
    private fun selectSingleFemaleVoice() {
        val engine = tts ?: return
        val candidate: Voice? = engine.voices
            ?.filter { it.locale == Locale.US && !it.isNetworkConnectionRequired }
            ?.firstOrNull { it.name.contains("female", ignoreCase = true) }
            ?: engine.voices?.firstOrNull { it.locale == Locale.US }
        candidate?.let { engine.voice = it }
    }

    /** The language currently loaded, so a run of English replies costs one switch. */
    private var currentLanguage: Language.Tag = Language.Tag.ENGLISH

    /**
     * Points the synthesiser at the language the reply is actually in.
     *
     * The engine was pinned to US English, so a French or Yoruba answer was read as
     * though the letters were English — which is not an accent, it is unintelligible.
     *
     * Falls back to English when the voice data isn't installed, and that fallback
     * matters: most phones ship no Yoruba, Hausa or Igbo voice at all. Reading it in
     * English at least conveys the words to someone who can read along, where an
     * engine left set to a missing language simply says nothing.
     */
    private fun applyLanguage(target: Language.Tag) {
        val engine = tts ?: return
        if (target == currentLanguage) return

        val result = runCatching { engine.setLanguage(target.locale()) }.getOrNull()
        val usable = result == TextToSpeech.LANG_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE

        if (usable) {
            currentLanguage = target
        } else {
            runCatching { engine.language = Locale.US }
            selectSingleFemaleVoice()
            currentLanguage = Language.Tag.ENGLISH
        }
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
            applyLanguage(Language.of(text))
            // Respelled for the synthesiser only — the transcript keeps "Lain".
            // Handed the real spelling, every English voice says "lane". Only worth
            // doing for an English voice; another language's rules make it wrong.
            val spoken =
                if (currentLanguage == Language.Tag.ENGLISH) LainName.forSpeech(text) else text
            engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, id)
            cont.invokeOnCancellation { engine.stop() }
        }
    }

    override fun stop() {
        tts?.stop()
    }
}
