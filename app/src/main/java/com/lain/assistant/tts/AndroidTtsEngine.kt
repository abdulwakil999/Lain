package com.lain.assistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.lain.assistant.agent.LainName
import com.lain.assistant.agent.Language
import com.lain.assistant.agent.Replies
import com.lain.assistant.agent.SpokenSegments
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

    private companion object {
        /** Long enough for a cold engine, short enough not to strand a voice turn. */
        const val INIT_TIMEOUT_MS = 5_000L
    }

    private var tts: TextToSpeech? = null

    /**
     * Completed when the engine reports its init result, successful or not.
     *
     * This replaces a plain boolean that [speak] checked and, if unset, returned
     * from. TextToSpeech signals readiness through a callback that lands tens to
     * hundreds of milliseconds after construction, so the first line Lain tried to
     * say after a cold start — the greeting, or the answer to the command that woke
     * her — was reliably dropped with no error anywhere. Waiting is the fix; a flag
     * you can lose a race against is the bug.
     */
    private val initialised = kotlinx.coroutines.CompletableDeferred<Boolean>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                tts?.language = Locale.US
                selectSingleFemaleVoice()
            }
            initialised.complete(ok)
        }
    }

    /**
     * Blocks until the engine is up, or gives up.
     *
     * Bounded because a missing or broken TTS service never calls back at all, and a
     * caller waiting on that forever is a hands-free session that never hands the
     * microphone back.
     */
    private suspend fun awaitReady(): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(INIT_TIMEOUT_MS) { initialised.await() } == true

    override suspend fun warmUp() {
        awaitReady()
    }

    /**
     * Lain speaks with exactly one English voice, chosen once — not a user setting.
     *
     * Female, because she is. Where a phone ships no voice that names itself as one,
     * the fallback below takes whatever US English voice exists rather than leaving
     * her mute — a wrong-sounding voice is recoverable, silence is not.
     */
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
        // The engine may still be starting: wait for it rather than dropping the line.
        if (!awaitReady()) return
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
            // A locally-produced line knows where its own languages change. Anything
            // else gets a whole-string guess, which is right for a reply that is all
            // one language and is all a model reply ever is.
            val segments = SpokenSegments.claim(text)
                ?: listOf(Replies.Spoken.Segment(text, Language.of(text)))

            // Queued rather than flushed after the first, so a mixed line is one
            // continuous utterance in two voices instead of the second cutting off
            // the first.
            segments.forEachIndexed { index, segment ->
                applyLanguage(segment.language)
                val spoken = if (currentLanguage == Language.Tag.ENGLISH) {
                    LainName.forSpeech(segment.text)
                } else {
                    segment.text
                }
                val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                engine.speak(spoken, mode, null, if (index == segments.lastIndex) id else "$id-$index")
            }
            cont.invokeOnCancellation { engine.stop() }
        }
    }

    override fun stop() {
        tts?.stop()
    }
}
