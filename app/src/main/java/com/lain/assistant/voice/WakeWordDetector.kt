package com.lain.assistant.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Listens for her name on one microphone stream that is opened once and stays open.
 *
 * The version before this one cycled the microphone. It opened an [AudioRecord],
 * waited for speech, then closed the recorder — releasing both audio effects with it
 * — bound a [SpeechRecognizer], checked the transcript, tore that down and opened a
 * fresh recorder. Every time anybody said anything. In a room with a television that
 * is an open/close cycle every couple of seconds, and each one costs an audio HAL
 * round trip, a route reconfiguration and two AudioEffect allocations. It is also
 * exactly what the privacy indicator flickering on and off looks like from outside.
 *
 * Assistants that do this properly never cycle the mic, and neither does this now:
 *
 *  - **One recorder for the life of the detector.** Opened in [start], released in
 *    [stop], and at no point in between. The read loop is a 20 ms frame reduced to
 *    RMS — a few hundred float operations, no allocation, nothing kept.
 *  - **No handover on Android 13+.** `RecognizerIntent.EXTRA_AUDIO_SOURCE` lets a
 *    recogniser read from a pipe instead of opening the microphone itself, so
 *    checking a candidate means writing the audio we are already holding into a file
 *    descriptor. The microphone is never touched. This is the path that removes the
 *    churn entirely.
 *  - **Rare handover below 13.** Older releases have no such API, so the recorder
 *    genuinely must be released for the recogniser. The gate below makes that
 *    uncommon rather than constant.
 *
 * The gate is the other half of the fix. Triggering on any 240 ms of speech meant a
 * conversation or a TV kept stage two busy permanently. A wake word has a shape —
 * a short burst of voice followed by a pause — and continuous speech does not, so
 * only completed short utterances are checked, and a run of failures widens a
 * refractory period so a noisy room quietly costs less rather than more.
 *
 * The honest limitation is unchanged: this is not a DSP keyword spotter. Android
 * reserves `AlwaysOnHotwordDetector` and `HotwordDetectionService` for whichever app
 * currently holds the system voice-interaction role, and the phrases those accept
 * are the ones burned into the OEM's hardware model — "Lain" is not among them. A
 * licensed engine needs a key the user must go and fetch; a bundled acoustic model
 * is tens of megabytes. This is what is available to an ordinary app, done without
 * waste.
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
        private const val FRAMES_PER_SECOND = SAMPLE_RATE / FRAME_SAMPLES

        /** How far above the learned noise floor counts as somebody talking. */
        private const val SPEECH_MARGIN_DB = 8.0

        /**
         * How quiet the floor is allowed to learn down to.
         *
         * This was -55 dBFS, and that single number made her deaf. The floor is
         * clamped *up* to it, so the trigger threshold could never be lower than
         * -46 dBFS — which is roughly someone speaking into the phone. A normal
         * voice a metre away sits well below that and never crossed it, so stage two
         * hardly ever ran. A real room floor is -65 to -75.
         */
        private const val MIN_FLOOR_DB = -75.0

        /** Where the floor starts before it has learned anything. */
        private const val INITIAL_FLOOR_DB = -60.0
        private const val FLOOR_RISE = 0.02
        private const val FLOOR_FALL = 0.15

        /**
         * The shape of a wake word: a short burst of voice, then a pause.
         *
         * Anything shorter is a door or a cough; anything longer is a sentence, and a
         * sentence is what a television produces continuously. Checking only completed
         * short utterances is what stopped stage two running all evening.
         */
        private const val MIN_UTTERANCE_FRAMES = FRAMES_PER_SECOND / 4      // 250 ms
        private const val MAX_UTTERANCE_FRAMES = FRAMES_PER_SECOND * 2      // 2 s
        private const val TRAILING_SILENCE_FRAMES = FRAMES_PER_SECOND / 4   // 250 ms

        /** Audio kept before the trigger, so the first syllable isn't clipped off. */
        private const val PREROLL_SECONDS = 1

        /** Quiet period after a check that found nothing. Grows in a noisy room. */
        private const val BASE_REFRACTORY_MS = 1_500L
        /**
         * Ceiling on the quiet period after a miss.
         *
         * Twelve seconds was too long: if verification is broken on a device — the
         * recogniser refusing the piped audio, say — every check misses and she goes
         * effectively deaf while still appearing to listen. Four seconds keeps a noisy
         * room cheap without ever making her unresponsive.
         */
        private const val MAX_REFRACTORY_MS = 4_000L

        /** Longest a verification runs before it is abandoned. */
        private const val VERIFY_WINDOW_MS = 3_500L

        /** After a wake, and after Lain starts talking, so she doesn't answer herself. */
        private const val COOLDOWN_MS = 1_200L

        /**
         * Ceiling on audio fed to one check: about two seconds at 16 kHz mono.
         *
         * Sized under a pipe's ~64 KB buffer on purpose, so the write never blocks
         * the read loop.
         */
        private const val MAX_VERIFY_BYTES = 60_000

        private const val MAX_BACKOFF_MS = 30_000L
        private const val BASE_BACKOFF_MS = 500L

        /** The pipe path exists from Android 13; below that a handover is unavoidable. */
        private val PIPE_AVAILABLE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        /** Piped checks that may fail before the device is written off as not supporting it. */
        private const val PIPE_FAILURES_BEFORE_FALLBACK = 2

        /**
         * Recogniser errors that mean it never got our audio, as opposed to hearing
         * nothing in it. The first group is a reason to change approach; the second is
         * simply a miss.
         */
        private val AUDIO_REJECTED = setOf(
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        )
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var suspended = false

    private var loop: Job? = null
    private var recorder: AudioRecord? = null
    private var effects: List<AudioEffect> = emptyList()
    private var recogniser: SpeechRecognizer? = null

    private var backoff = BASE_BACKOFF_MS
    private var refractory = BASE_REFRACTORY_MS
    private var quietUntil = 0L

    /**
     * What one check concluded.
     *
     * [audioRejected] is the distinction that matters. A recogniser that heard
     * nothing and a recogniser that could not read our audio at all look identical
     * from a bare transcript, and treating them the same is why a device whose
     * recogniser ignores the piped audio went quietly deaf instead of falling back.
     */
    private data class Verdict(val text: String, val audioRejected: Boolean = false)

    /** Set by the verification listener; read by the loop. */
    private val verdict = AtomicReference<Verdict?>(null)

    /**
     * Whether the piped path is usable on this device.
     *
     * `EXTRA_AUDIO_SOURCE` is honoured by the recognisers that implement it and
     * ignored by the ones that don't — and a recogniser that ignores it opens the
     * microphone itself, finds ours already on it, and fails. There is no way to ask
     * in advance, so this tries, watches, and stops trying.
     */
    @Volatile private var pipeWorks = PIPE_AVAILABLE
    private var pipeFailures = 0

    /** A piped check is in flight: the loop keeps reading and feeds the recogniser. */
    @Volatile private var verifying = false

    /**
     * A pre-Android-13 check is in flight: the recogniser has the microphone and the
     * loop must not try to reopen it. Separate from [verifying] because the two
     * states are opposites — one keeps the recorder, the other gives it up — and
     * sharing a flag between them is how the loop and the recogniser ended up
     * fighting over the same device.
     */
    @Volatile private var handingOver = false

    private var audioSink: java.io.OutputStream? = null

    /**
     * Bytes fed to the recogniser this check.
     *
     * A pipe holds about 64 KB. Writing past that blocks the loop thread — the
     * detector would stall mid-check with the microphone open — so the sink is closed
     * at the cap instead, which the recogniser reads as end-of-audio and answers.
     */
    private var bytesWritten = 0

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
        refractory = BASE_REFRACTORY_MS
        loop = scope.launch(Dispatchers.Default) { run() }
    }

    /**
     * Stands down and lets go of the microphone.
     *
     * Used for the transitions where something else genuinely needs it — the command
     * recogniser, the screen going off — and nowhere else. It is not part of the
     * detection cycle any more, which is the entire point of this rewrite.
     */
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
        handingOver = false
        loop?.cancel()
        loop = null
        closeRecorder()
        main.post { tearDownRecogniser() }
        WakeWordManager.releaseMicrophone(WakeWordManager.OWNER_WAKE_WORD)
    }

    /** Held after a wake and after each reply starts, so she doesn't answer herself. */
    suspend fun cooldown() = delay(COOLDOWN_MS)

    // ----------------------------------------------------------------- the loop

    private suspend fun run() {
        val frame = ShortArray(FRAME_SAMPLES)
        val preroll = AudioRing(SAMPLE_RATE * PREROLL_SECONDS)

        var floor = INITIAL_FLOOR_DB
        var speechFrames = 0
        var silenceFrames = 0
        var inUtterance = false

        while (scope.isActive && running) {
            // The only two reasons the recorder is ever closed: somebody else needs
            // the microphone, or the permission went away.
            if (suspended || !hasPermission()) {
                if (recorder != null) closeRecorder()
                if (!hasPermission() && running) onError("Microphone permission was revoked")
                inUtterance = false
                speechFrames = 0
                delay(250)
                continue
            }

            // The legacy check has the microphone. Reopening here would race it and
            // one of the two would lose, unpredictably.
            if (handingOver) {
                delay(50)
                continue
            }

            if (recorder == null) {
                if (!WakeWordManager.claimMicrophone(WakeWordManager.OWNER_WAKE_WORD)) {
                    delay(400)
                    continue
                }
                if (!openRecorder()) {
                    WakeWordManager.releaseMicrophone(WakeWordManager.OWNER_WAKE_WORD)
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    continue
                }
                backoff = BASE_BACKOFF_MS
                preroll.clear()
                floor = INITIAL_FLOOR_DB
            }

            val active = recorder ?: continue
            val read = active.read(frame, 0, FRAME_SAMPLES)
            if (read <= 0) {
                // A negative read is the recorder being invalidated under us — a call
                // arriving, a headset going. Close and let the loop rebuild it.
                if (read < 0) {
                    closeRecorder()
                    onError("The microphone was taken")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
                continue
            }

            preroll.write(frame, read)

            // While a check is in flight the same frames go to the recogniser, so
            // there is one reader and no second microphone.
            if (verifying) {
                writeToSink(frame, read)
                verdict.getAndSet(null)?.let { result ->
                    finishVerification()
                    when {
                        result.text.isNotEmpty() && isWakePhrase(result.text) -> {
                            pipeFailures = 0
                            refractory = BASE_REFRACTORY_MS
                            WakeWordManager.recordWake()
                            suspended = true
                            // Closed here, synchronously, before anyone is told. Setting
                            // `suspended` only makes the loop close it on its *next*
                            // pass, and the command recogniser starts long before that —
                            // finding this AudioRecord still on the microphone and
                            // failing with ERROR_RECOGNIZER_BUSY. Which is a wake word
                            // that works followed by an assistant that hears nothing.
                            closeRecorder()
                            main.post { onWake(result.text) }
                        }

                        result.audioRejected -> {
                            // The recogniser could not use the audio we handed it. Two
                            // of these and the piped route is written off for this
                            // session, which puts the device on the handover path
                            // rather than leaving it unable to hear anything.
                            pipeFailures++
                            if (pipeFailures >= PIPE_FAILURES_BEFORE_FALLBACK) {
                                pipeWorks = false
                                WakeWordManager.recordDiagnostic(
                                    "Piped audio refused; using the microphone handover instead"
                                )
                            }
                            quietUntil = System.currentTimeMillis() + BASE_REFRACTORY_MS
                        }

                        else -> {
                            // Heard, but not her name. Back off a little further each
                            // time, so a room full of talking costs less rather than more.
                            pipeFailures = 0
                            refractory = (refractory * 2).coerceAtMost(MAX_REFRACTORY_MS)
                            quietUntil = System.currentTimeMillis() + refractory
                        }
                    }
                    inUtterance = false
                    speechFrames = 0
                    silenceFrames = 0
                }
                continue
            }

            val db = levelDb(frame, read)
            val speaking = db > floor + SPEECH_MARGIN_DB

            if (speaking) {
                speechFrames++
                silenceFrames = 0
                if (speechFrames >= MIN_UTTERANCE_FRAMES) inUtterance = true

                // Speech that runs long used to set inUtterance = false and then never
                // verify at all — so "Lain, open WhatsApp", the exact sentence this is
                // for, could not wake her. It is checked *now* instead: her name is at
                // the start of such a sentence, and the pre-roll still holds it.
                if (speechFrames >= MAX_UTTERANCE_FRAMES) {
                    inUtterance = false
                    speechFrames = 0
                    if (System.currentTimeMillis() >= quietUntil) {
                        beginVerification(preroll.snapshot())
                    }
                }
            } else {
                silenceFrames++
                // Only quiet frames teach the floor. Learning from speech would let a
                // long sentence raise the threshold above itself.
                val rate = if (db > floor) FLOOR_RISE else FLOOR_FALL
                floor = (floor + (db - floor) * rate).coerceAtLeast(MIN_FLOOR_DB)

                if (inUtterance && silenceFrames >= TRAILING_SILENCE_FRAMES) {
                    inUtterance = false
                    speechFrames = 0
                    if (System.currentTimeMillis() >= quietUntil) {
                        beginVerification(preroll.snapshot())
                    }
                } else if (silenceFrames >= TRAILING_SILENCE_FRAMES) {
                    speechFrames = 0
                }
            }
        }

        closeRecorder()
    }

    // ---------------------------------------------------------------- recorder

    private fun openRecorder(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            onError("This device won't open a 16 kHz mono recorder")
            return false
        }
        val built = runCatching {
            @Suppress("MissingPermission")
            AudioRecord(
                // VOICE_RECOGNITION rather than MIC: the platform applies its
                // recognition tuning and, on most devices, routes the echo canceller —
                // which is what stops her hearing her own replies.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_SAMPLES * 2 * 8)
            )
        }.getOrNull() ?: run {
            onError("The microphone is not available")
            return false
        }

        if (built.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { built.release() }
            onError("Another app is using the microphone")
            return false
        }

        // Created once with the recorder, not once per check. Allocating and freeing
        // these on every candidate was a large part of the churn.
        effects = attachEffects(built.audioSessionId)

        return runCatching {
            built.startRecording()
            if (built.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("The microphone didn't start")
            }
            recorder = built
            true
        }.getOrElse {
            runCatching { built.release() }
            effects.forEach { e -> runCatching { e.release() } }
            effects = emptyList()
            onError(it.message ?: "The microphone didn't start")
            false
        }
    }

    private fun closeRecorder() {
        finishVerification()
        recorder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        recorder = null
        effects.forEach { runCatching { it.release() } }
        effects = emptyList()
        WakeWordManager.releaseMicrophone(WakeWordManager.OWNER_WAKE_WORD)
    }

    private fun attachEffects(sessionId: Int): List<AudioEffect> = buildList {
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

    /** RMS of one frame in dBFS. The only thing the idle path ever computes. */
    private fun levelDb(frame: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val sample = frame[i].toDouble() / Short.MAX_VALUE
            sum += sample * sample
        }
        val rms = sqrt(sum / count)
        return if (rms <= 0.0) -100.0 else 20.0 * log10(rms)
    }

    // ------------------------------------------------------------ verification

    /**
     * Checks one candidate utterance for her name.
     *
     * On Android 13+ this hands the recogniser a pipe and keeps the microphone
     * exactly where it was. Below that it has to release the recorder, because no
     * older API lets a recogniser read from anywhere but the mic — so the loop above
     * works hard to make sure it rarely gets here.
     */
    private fun beginVerification(preroll: ShortArray) {
        if (verifying) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return

        WakeWordManager.recordCheck()

        if (!pipeWorks) {
            handingOver = true
            scope.launch { verifyByHandover() }
            return
        }

        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: run {
            pipeWorks = false
            return
        }
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        verdict.set(null)
        bytesWritten = 0
        // AutoCloseOutputStream owns the descriptor. A plain FileOutputStream over
        // pfd.fileDescriptor does not, so the ParcelFileDescriptor's finaliser would
        // later close a descriptor this stream had already closed — and by then the
        // number may belong to something else entirely.
        audioSink = ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)
        verifying = true

        // The moment *before* the trigger. Written here, on the loop thread, and
        // before the recogniser starts — the pipe buffers it. Doing this from the
        // main thread instead meant two threads writing one stream and interleaving
        // their frames into noise.
        writeToSink(preroll, preroll.size)

        main.post {
            val engine = runCatching { buildRecogniser() }.getOrNull()
            if (engine == null) {
                verdict.compareAndSet(null, Verdict("", audioRejected = true))
                runCatching { readEnd.close() }
                return@post
            }
            recogniser = engine
            engine.setRecognitionListener(listener())
            val intent = baseIntent().apply {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            }
            runCatching { engine.startListening(intent) }
                .onFailure { verdict.compareAndSet(null, Verdict("", audioRejected = true)) }
            // Our copy of the read end; the recogniser holds its own.
            runCatching { readEnd.close() }
        }

        scope.launch {
            delay(VERIFY_WINDOW_MS)
            // Nothing came back. Treat it as "not her name" rather than leaving the
            // loop feeding a recogniser forever.
            if (verifying) verdict.compareAndSet(null, Verdict(""))
        }
    }

    private fun writeToSink(frame: ShortArray, count: Int) {
        val sink = audioSink ?: return
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = frame[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        runCatching { sink.write(bytes) }.onFailure {
            // The recogniser closed its end: it has decided, and the verdict is on
            // its way through the listener.
            runCatching { sink.close() }
            audioSink = null
            return
        }
        bytesWritten += bytes.size
        if (bytesWritten >= MAX_VERIFY_BYTES) {
            // Enough for anyone to have said a name. Closing is what tells the
            // recogniser the audio has ended, so it answers instead of waiting — and
            // it is what stops a full pipe blocking this thread with the mic open.
            runCatching { sink.close() }
            audioSink = null
        }
    }

    private fun finishVerification() {
        verifying = false
        audioSink?.let { runCatching { it.close() } }
        audioSink = null
        main.post { tearDownRecogniser() }
    }

    /**
     * The pre-Android-13 path: release the microphone, listen, take it back.
     *
     * Kept deliberately simple because it should be rare. It is also the reason the
     * utterance gate and the refractory period exist — on those devices, every one of
     * these is a microphone cycle the user can see in the privacy indicator.
     */
    private suspend fun verifyByHandover() {
        closeRecorder()
        val heard = kotlinx.coroutines.withTimeoutOrNull(VERIFY_WINDOW_MS) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                main.post {
                    val engine = runCatching { buildRecogniser() }.getOrNull()
                    if (engine == null) {
                        if (cont.isActive) cont.resume("") { _, _, _ -> }
                        return@post
                    }
                    recogniser = engine
                    var settled = false
                    // Hoisted out of the listener so the startListening failure below
                    // can resume the same continuation. A recogniser that refuses to
                    // start must not leave the loop waiting out the whole window.
                    val settle: (String) -> Unit = { value ->
                        if (!settled) {
                            settled = true
                            if (cont.isActive) cont.resume(value) { _, _, _ -> }
                        }
                    }
                    engine.setRecognitionListener(object : RecognitionListener {
                        override fun onPartialResults(partialResults: Bundle) {
                            val hit = best(partialResults)?.takeIf { isWakePhrase(it) }
                            if (hit != null) settle(hit)
                        }
                        override fun onResults(results: Bundle) = settle(best(results).orEmpty())
                        override fun onError(error: Int) = settle("")
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })
                    runCatching { engine.startListening(baseIntent()) }.onFailure { settle("") }
                }
            }
        }.orEmpty()

        main.post { tearDownRecogniser() }
        handingOver = false

        if (heard.isNotEmpty() && isWakePhrase(heard)) {
            refractory = BASE_REFRACTORY_MS
            WakeWordManager.recordWake()
            suspended = true
            main.post { onWake(heard) }
        } else {
            refractory = (refractory * 2).coerceAtMost(MAX_REFRACTORY_MS)
            quietUntil = System.currentTimeMillis() + refractory
        }
    }

    private fun listener() = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle) {
            // Settling on a partial is what makes waking feel instant: no reason to
            // wait out the trailing silence once the name has already been said.
            val hit = best(partialResults)?.takeIf { isWakePhrase(it) } ?: return
            verdict.compareAndSet(null, Verdict(hit))
        }
        override fun onResults(results: Bundle) {
            verdict.compareAndSet(null, Verdict(best(results).orEmpty()))
        }
        override fun onError(error: Int) {
            WakeWordManager.recordDiagnostic("recogniser error $error")
            verdict.compareAndSet(null, Verdict("", audioRejected = error in AUDIO_REJECTED))
        }
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun buildRecogniser(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            // On 13+ this never touches the network, so waiting for her name costs no
            // data and leaks no audio.
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun baseIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
    }

    private fun best(bundle: Bundle): String? {
        val all = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return null
        return all.firstOrNull { isWakePhrase(it) } ?: all.firstOrNull()
    }

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
     * Not a fixed phrase. "Hello Lain", "hi Lain", "sup Lain", "Lain, open WhatsApp"
     * and the name on its own all wake her. [LainName] already knows the difference
     * between the name and the road it sounds like, so this asks it rather than
     * keeping a second list that would drift.
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
}

/**
 * A fixed-size window of the most recent audio.
 *
 * Exists so the recogniser is handed the moment *before* the trigger fired. Without
 * it verification starts partway through the word and hears the tail of the name
 * rather than the name.
 *
 * Top-level and internal rather than nested and private, because getting the wrap
 * wrong produces audio that is subtly out of order — which sounds like nothing at
 * all to a recogniser and looks like "the wake word just doesn't work" from outside.
 * That is worth a test, and a private inner class cannot have one.
 */
internal class AudioRing(private val capacity: Int) {
    private val data = ShortArray(capacity)
    private var head = 0
    private var filled = 0

    fun clear() {
        head = 0
        filled = 0
    }

    fun write(source: ShortArray, count: Int) {
        for (i in 0 until count) {
            data[head] = source[i]
            head = (head + 1) % capacity
            if (filled < capacity) filled++
        }
    }

    /** Oldest-first copy of what is held. */
    fun snapshot(): ShortArray {
        val out = ShortArray(filled)
        val start = (head - filled + capacity) % capacity
        for (i in 0 until filled) out[i] = data[(start + i) % capacity]
        return out
    }
}
