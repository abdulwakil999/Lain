package com.lain.assistant.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.lain.assistant.agent.LainName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Listens for her name without transcribing the room.
 *
 * The previous version looped Android's SpeechRecognizer forever: it transcribed
 * every sound in the room, threw almost all of it away, and did that on a service
 * binding, a model inference and an IPC round trip per cycle. That is expensive
 * enough to show up in the battery breakdown, and it means continuous speech
 * recognition is running on everything said near the phone — which is not a
 * privacy posture anyone should have to accept from an assistant.
 *
 * This is two stages instead, and the split is the entire design:
 *
 *  **Stage one — energy.** A raw [AudioRecord] at 16 kHz, read in 20 ms frames,
 *  reduced to one number: RMS in dBFS. That is a few hundred floating-point
 *  operations per frame and nothing else — no model, no service, no network, no
 *  buffer kept. Silence never leaves this stage, so most of the time the phone is
 *  doing arithmetic on a number and discarding it. The noise floor is learned
 *  continuously, so a quiet bedroom and a moving car both work without a setting.
 *
 *  **Stage two — the name.** Only when stage one has heard sustained speech does a
 *  recogniser start, and only for one short utterance. On Android 13+ the
 *  *on-device* recogniser is used where the platform provides it, so the audio
 *  never leaves the phone at all. If the name is not in it, everything is torn down
 *  and stage one resumes. Nothing is stored and nothing is sent.
 *
 * The honest limitation, stated rather than buried: this is not a trained keyword
 * spotter like Porcupine. Doing that properly needs either a licensed engine with
 * a key the user must go and get, or a bundled acoustic model of tens of megabytes,
 * and neither belongs in an app whose whole premise is working out of the box on a
 * cheap phone. What this gives up is a little accuracy at distance; what it keeps
 * is that stage one is genuinely cheap and genuinely local.
 *
 * The class owns the microphone through [WakeWordManager], never both stages at
 * once, and never at the same time as the command recogniser.
 */
class WakeWordDetector(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Called on the main thread with the transcript that contained her name. */
    private val onWake: (String) -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        private const val SAMPLE_RATE = 16_000

        /** 20 ms of mono 16-bit audio. Short enough to react, long enough to be stable. */
        private const val FRAME_SAMPLES = 320

        /**
         * How far above the learned noise floor counts as somebody talking.
         *
         * Low enough to catch a normal speaking voice across a room, high enough
         * that a fridge, a fan or traffic does not keep waking stage two.
         */
        private const val SPEECH_MARGIN_DB = 9.0

        /** Consecutive speech frames before stage two runs: ~240 ms of actual voice. */
        private const val FRAMES_TO_TRIGGER = 12

        /** Frames of quiet that reset the counter, so a cough doesn't accumulate. */
        private const val FRAMES_TO_RESET = 15

        /** How quickly the noise floor follows the room. Slow on purpose. */
        private const val FLOOR_RISE = 0.02
        private const val FLOOR_FALL = 0.15

        /** Absolute floor, so a silent room doesn't drive the threshold to nothing. */
        private const val MIN_FLOOR_DB = -55.0

        /** Longest stage two ever runs before giving up and going back to stage one. */
        private const val RECOGNISE_WINDOW_MS = 3_500L

        /**
         * Quiet period after a wake, and after Lain starts speaking.
         *
         * Without the first, her own "Yes?" retriggers the detector. Without the
         * second, the opening syllable of every reply does.
         */
        private const val COOLDOWN_MS = 1_200L

        /** Backoff ceiling when the recorder or recogniser keeps failing. */
        private const val MAX_BACKOFF_MS = 30_000L
        private const val BASE_BACKOFF_MS = 500L
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var suspended = false

    private var loop: Job? = null
    private var recogniser: SpeechRecognizer? = null
    private var backoff = BASE_BACKOFF_MS

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun start() {
        if (running) return
        if (!hasPermission()) {
            onError("Microphone permission isn't granted")
            return
        }
        running = true
        suspended = false
        backoff = BASE_BACKOFF_MS
        loop = scope.launch(Dispatchers.Default) { listenLoop() }
    }

    /** Stops for a while — during a command, or while the screen is off. */
    fun suspendDetection() {
        suspended = true
    }

    fun resumeDetection() {
        if (!running) return
        suspended = false
    }

    fun stop() {
        running = false
        suspended = false
        loop?.cancel()
        loop = null
        tearDownRecogniser()
        WakeWordManager.releaseMicrophone(WakeWordManager.OWNER_WAKE_WORD)
    }

    // ------------------------------------------------------------- stage one

    private suspend fun listenLoop() {
        while (scope.isActive && running) {
            if (suspended) {
                delay(200)
                continue
            }
            if (!hasPermission()) {
                // Revoked while running. Not an error to retry in a tight loop: the
                // user has to go and grant it, so back off and keep checking cheaply.
                onError("Microphone permission was revoked")
                delay(5_000)
                continue
            }
            // Somebody else has the mic — the command recogniser, or another app.
            // Waiting is correct; fighting for it is not.
            if (!WakeWordManager.claimMicrophone(WakeWordManager.OWNER_WAKE_WORD)) {
                delay(400)
                continue
            }

            val outcome = runCatching { awaitSpeech() }

            // Released before stage two starts. One microphone user at a time is not
            // a style preference — a recogniser started while an AudioRecord is open
            // returns ERROR_RECOGNIZER_BUSY on most devices and nothing at all on the
            // rest.
            WakeWordManager.releaseMicrophone(WakeWordManager.OWNER_WAKE_WORD)

            val failure = outcome.exceptionOrNull()
            if (failure != null) {
                onError(failure.message ?: "Microphone unavailable")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            backoff = BASE_BACKOFF_MS

            if (outcome.getOrDefault(false) != true || suspended || !running) continue

            val transcript = recogniseOnce()
            if (transcript != null && isWakePhrase(transcript)) {
                suspended = true
                withContext(Dispatchers.Main) { onWake(transcript) }
            }
            delay(120)
        }
    }

    /**
     * Blocks on the microphone until somebody talks, or until detection is stopped.
     *
     * @return true when speech was heard, false when the loop should simply go round
     *   again (suspended, stopped, or the mic taken).
     */
    private suspend fun awaitSpeech(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) throw IllegalStateException("This device won't open a 16 kHz mono recorder")

        val bufferSize = maxOf(minBuffer, FRAME_SAMPLES * 2 * 4)
        val recorder = try {
            @Suppress("MissingPermission")
            AudioRecord(
                // VOICE_RECOGNITION rather than MIC: the platform applies its
                // recognition tuning, and on most devices routes the echo canceller,
                // which is what stops her hearing herself.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (t: Throwable) {
            throw IllegalStateException("Microphone is not available")
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            throw IllegalStateException("Another app is using the microphone")
        }

        val effects = attachEffects(recorder.audioSessionId)

        return try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("The microphone didn't start")
            }

            val frame = ShortArray(FRAME_SAMPLES)
            var floor = MIN_FLOOR_DB
            var speechFrames = 0
            var quietFrames = 0

            while (scope.isActive && running && !suspended) {
                val read = recorder.read(frame, 0, FRAME_SAMPLES)
                if (read <= 0) {
                    // A negative read is the recorder having been invalidated under
                    // us, which is what happens when a call comes in.
                    if (read < 0) throw IllegalStateException("The microphone was taken")
                    continue
                }

                val db = levelDb(frame, read)
                val speaking = db > floor + SPEECH_MARGIN_DB

                if (speaking) {
                    speechFrames++
                    quietFrames = 0
                    if (speechFrames >= FRAMES_TO_TRIGGER) return true
                } else {
                    quietFrames++
                    if (quietFrames >= FRAMES_TO_RESET) speechFrames = 0
                    // The floor only learns from quiet. Learning from speech would
                    // let a long sentence raise the threshold above itself.
                    val rate = if (db > floor) FLOOR_RISE else FLOOR_FALL
                    floor = (floor + (db - floor) * rate).coerceAtLeast(MIN_FLOOR_DB)
                }
            }
            false
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            effects.forEach { runCatching { it.release() } }
        }
    }

    /**
     * Echo cancellation and noise suppression, where the device has them.
     *
     * Both are optional hardware features. Their absence is not an error — it makes
     * self-triggering more likely, which the spoken-text check below still catches.
     */
    private fun attachEffects(sessionId: Int): List<android.media.audiofx.AudioEffect> = buildList {
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true; add(it) }
            }
        }
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.also { it.enabled = true; add(it) }
            }
        }
    }

    /** RMS of one frame, in dBFS. The only thing stage one ever computes. */
    private fun levelDb(frame: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val sample = frame[i].toDouble() / Short.MAX_VALUE
            sum += sample * sample
        }
        val rms = sqrt(sum / count)
        if (rms <= 0.0) return -100.0
        return 20.0 * kotlin.math.log10(rms)
    }

    // ------------------------------------------------------------- stage two

    /**
     * One short recognition, on-device where the platform offers it.
     *
     * Runs only after stage one heard a voice, so this is a handful of times an
     * hour in a quiet room rather than continuously. The result is examined and
     * dropped; nothing is stored and nothing is sent anywhere.
     */
    private suspend fun recogniseOnce(): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext null
        if (!WakeWordManager.claimMicrophone(WakeWordManager.OWNER_WAKE_WORD)) return@withContext null

        val result = kotlinx.coroutines.withTimeoutOrNull(RECOGNISE_WINDOW_MS) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val engine = runCatching { buildRecogniser() }.getOrNull()
                if (engine == null) {
                    if (cont.isActive) cont.resume(null) { _, _, _ -> }
                    return@suspendCancellableCoroutine
                }
                recogniser = engine

                var settled = false
                fun settle(value: String?) {
                    if (settled) return
                    settled = true
                    if (cont.isActive) cont.resume(value) { _, _, _ -> }
                }

                engine.setRecognitionListener(object : RecognitionListener {
                    override fun onPartialResults(partialResults: Bundle) {
                        val best = best(partialResults) ?: return
                        // Settling on a partial is what makes waking feel instant:
                        // there is no reason to wait out the trailing silence once
                        // the name has already been said.
                        if (isWakePhrase(best)) settle(best)
                    }

                    override fun onResults(results: Bundle) = settle(best(results))
                    override fun onError(error: Int) = settle(null)
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                cont.invokeOnCancellation { main.post { tearDownRecogniser() } }
                runCatching { engine.startListening(recogniserIntent()) }
                    .onFailure { settle(null) }
            }
        }

        tearDownRecogniser()
        WakeWordManager.releaseMicrophone(WakeWordManager.OWNER_WAKE_WORD)
        result
    }

    private fun buildRecogniser(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            // The whole point: on 13+ this never touches the network, so waiting for
            // her name costs no data and leaks no audio.
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun recogniserIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        // Asked for even below 13, where some OEM recognisers honour it.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
    }

    private fun best(bundle: Bundle): String? =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull { isWakePhrase(it) }
            ?: bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun tearDownRecogniser() {
        recogniser?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recogniser = null
    }

    /**
     * Her name, said to her.
     *
     * Not a fixed phrase. "Hello Lain", "hi Lain", "sup Lain", "Lain, open
     * WhatsApp" and the name on its own all wake her, because that is how people
     * actually address someone. [LainName] already knows the difference between the
     * name and the road it sounds like, so this asks it rather than keeping a second
     * list that would drift.
     */
    private fun isWakePhrase(heard: String): Boolean {
        if (!LainName.isAddressed(heard)) return false
        // Her own voice, coming back through the microphone. Echo cancellation gets
        // most of it; this gets the rest.
        val said = WakeWordManager.spokenAloud.lowercase()
        if (said.isNotEmpty()) {
            val words = heard.lowercase().split(Regex("\\s+")).filter { it.length > 3 }
            if (words.isNotEmpty() && words.count { said.contains(it) } * 2 >= words.size) return false
        }
        return true
    }

    /** Held after a wake and after each reply starts, so she doesn't answer herself. */
    suspend fun cooldown() = delay(COOLDOWN_MS)
}
