package com.lain.assistant.automation

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.lain.assistant.agent.LainName
import com.lain.assistant.agent.Misheard
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Mic input, transcribed on-device via Android's SpeechRecognizer.
 *
 * Two things here are latency work rather than plumbing.
 *
 * **The recognizer is kept warm.** `createSpeechRecognizer` binds to the system
 * recognition service, and that bind is not free — it was being paid on every
 * single utterance, before the user had said anything, as dead time between
 * pressing the mic and the microphone actually listening. One instance is created
 * on the main thread and reused.
 *
 * **The end-of-speech window is tightened.** Android's default trailing-silence
 * timeout is generous, tuned for dictating paragraphs. An assistant command is a
 * short phrase, and waiting a second and a half after the user has plainly
 * finished is the single most noticeable delay in voice mode. The values below are
 * hints — some OEM recognizers ignore them — but where they're honoured they cut
 * most of that pause.
 *
 * Partial results are enabled so the caller can show words as they're recognised.
 *
 * **It can read somebody else's stream.** Given an [audioSource] pipe it recognises
 * from that instead of opening the microphone itself. That is what lets hands-free
 * answer a command without the wake detector letting go of the recorder: one stream
 * stays open the whole time, and the privacy indicator stays lit and steady instead
 * of blinking off and on at every turn. Android 13 and up only — below that there is
 * no such API and the microphone is opened here as before.
 */
/**
 * The piped stream was not accepted, so the same command is worth one more attempt
 * on the microphone itself.
 */
class PipedAudioRefused(message: String) : IllegalStateException(message)

class VoiceInputController(private val context: Context) {

    companion object {
        /** Silence after speech before the result is finalised. */
        private const val COMPLETE_SILENCE_MS = 900L

        /** Shorter window applied when the recognizer thinks the phrase may already be complete. */
        private const val POSSIBLY_COMPLETE_SILENCE_MS = 700L

        /** Don't wait forever for someone who never speaks. */
        private const val MINIMUM_LENGTH_MS = 700L

        /**
         * Hard ceiling on a single listen.
         *
         * The recogniser's own timeouts are hints that several OEM implementations
         * ignore. This one is ours and cannot be ignored.
         */
        private const val LISTEN_CEILING_MS = 25_000L

        /** What the wake detector captures at, and therefore what a piped stream is. */
        const val PIPE_SAMPLE_RATE = 16_000

        /**
         * Recogniser errors that mean the piped audio was not accepted.
         *
         * Distinct from "nothing was said": these are the ones that say the stream
         * itself was refused, and they are the signal to stop trying the pipe and go
         * back to opening the microphone.
         */
        val AUDIO_REJECTED = setOf(
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT
        )
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * SpeechRecognizer is main-thread-only and single-use-at-a-time, so one instance
     * is held and reused rather than rebuilt per utterance.
     */
    @Volatile
    private var recognizer: SpeechRecognizer? = null

    /**
     * Binds the recognition service ahead of time.
     *
     * Called when a voice surface opens, so the bind cost overlaps the user reaching
     * for the button instead of landing between the tap and the mic going live.
     */
    fun prewarm() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        main.post { ensureRecognizer() }
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        return runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
            .getOrNull()
            ?.also { recognizer = it }
    }

    /** Releases the service binding. Worth doing when voice features are switched off. */
    fun release() {
        main.post {
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }

    /**
     * @param onPartial called on the main thread with the best guess so far, so the UI
     *   can show words appearing while the user is still talking.
     */
    /**
     * One utterance, or a failure.
     *
     * Wrapped in a timeout because the failure mode that matters most here is not an
     * error code — it is an OEM recogniser that accepts startListening and then never
     * calls back at all. Without a ceiling that is a permanent "Listening…" and a
     * microphone nobody ever releases.
     */
    suspend fun listenOnce(
        onPartial: (String) -> Unit = {},
        audioSource: ParcelFileDescriptor? = null
    ): Result<String> {
        val result: Result<String>? =
            kotlinx.coroutines.withTimeoutOrNull(LISTEN_CEILING_MS) { listenInternal(onPartial, audioSource) }
        if (result != null) return result
        // Timed out with no callback at all: cancel the recogniser so the next
        // attempt isn't refused as busy, and report it rather than hanging.
        main.post { runCatching { recognizer?.cancel() } }
        return Result.failure(IllegalStateException("The recogniser stopped responding"))
    }

    private suspend fun listenInternal(
        onPartial: (String) -> Unit,
        audioSource: ParcelFileDescriptor?
    ): Result<String> =
        suspendCancellableCoroutine<Result<String>> { cont ->
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                cont.resume(Result.failure(IllegalStateException("No speech recognition service on this device")))
                return@suspendCancellableCoroutine
            }

            main.post {
                val engine = ensureRecognizer()
                if (engine == null) {
                    if (cont.isActive) {
                        cont.resume(Result.failure(IllegalStateException("Couldn't start speech recognition")))
                    }
                    return@post
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        POSSIBLY_COMPLETE_SILENCE_MS
                    )
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MINIMUM_LENGTH_MS)
                    // Only the top hypothesis is ever used.
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

                    // Reading a caller's stream rather than the microphone. The
                    // format has to be stated because a pipe carries no header —
                    // it is raw PCM and nothing else.
                    if (audioSource != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSource)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, PIPE_SAMPLE_RATE)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    }
                }

                engine.setRecognitionListener(object : RecognitionListener {
                    /** Guards against a recognizer that reports both a result and an error. */
                    private var settled = false

                    private fun settle(result: Result<String>) {
                        if (settled) return
                        settled = true
                        if (cont.isActive) cont.resume(result)
                    }

                    override fun onResults(results: Bundle) {
                        // Corrected here, at the single point every spoken message enters
                        // the app. Recognisers have never heard of the name and return
                        // "hello lane" — which then reaches the model as a request
                        // addressed to a road. Misheard does the same for the rest of
                        // the vocabulary they have never heard either: Claude comes
                        // back as "cloud", DeepSeek as "deep seek".
                        val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.let(LainName::normaliseHeard)
                            ?.let(Misheard::correct)
                        settle(
                            if (!text.isNullOrBlank()) Result.success(text)
                            else Result.failure(IllegalStateException("Didn't catch that"))
                        )
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let(LainName::normaliseHeard)
                            ?.let(Misheard::correct)
                            ?.let(onPartial)
                    }

                    override fun onError(error: Int) {
                        // Tagged so the caller can tell "the pipe was refused" from
                        // "nobody said anything" and fall back to the microphone
                        // rather than reporting a broken assistant.
                        val fromPipe = audioSource != null && error in AUDIO_REJECTED
                        settle(
                            Result.failure(
                                if (fromPipe) PipedAudioRefused(describe(error))
                                else IllegalStateException(describe(error))
                            )
                        )
                    }

                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                engine.startListening(intent)
            }

            // The recognizer is reused, so cancellation stops the current utterance
            // rather than destroying the binding we just paid for.
            cont.invokeOnCancellation { main.post { runCatching { recognizer?.cancel() } } }
        }

    /** Error codes are opaque integers; the user deserves a sentence. */
    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't hear anything"
        // Distinguished from a permission problem on purpose: this one usually means
        // a call, a recorder or a headset change took the microphone, and the fix is
        // to wait rather than to go to Settings.
        SpeechRecognizer.ERROR_AUDIO -> "The microphone isn't available — something else may be using it"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission isn't granted"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition needs a network connection on this device"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser is busy — try again"
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition was cancelled"
        SpeechRecognizer.ERROR_SERVER -> "The recognition service refused that"
        else -> "Speech recognition failed (code $error)"
    }
}
