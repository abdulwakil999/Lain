package com.lain.assistant.voice

import com.lain.assistant.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Speaking, as one queue with one owner.
 *
 * The engines underneath ([com.lain.assistant.tts.AndroidTtsEngine],
 * FishAudioTtsEngine, KokoroTtsEngine) each know how to turn a string into sound.
 * None of them knows whether something else is already speaking, whether the user
 * has interrupted, or whether the synthesiser has finished initialising — and those
 * three questions are where every real voice-assistant bug lives.
 *
 *  - **Overlap.** Two replies arriving close together used to be handed to the
 *    engine at once. Android's TextToSpeech takes the second with QUEUE_FLUSH and
 *    cuts the first off mid-word. Here utterances go through a channel drained by
 *    one coroutine, so they play in order or not at all.
 *  - **Interruption.** [stop] cancels the drain, empties the queue and stops the
 *    engine, in that order. Stopping the engine first leaves queued utterances to
 *    start playing straight afterwards, which is exactly the "old speech talking
 *    over the new interaction" this is meant to prevent.
 *  - **Speaking state.** Published rather than inferred, so the wake detector knows
 *    when to expect its own echo and the UI knows what to draw.
 *
 * [StreamingSpeaker] still handles the harder job of chunking a reply as it is
 * generated; this is the layer below it for complete lines — greetings, scheduled
 * lines, confirmations — and the single place that owns the engine's stop.
 */
class LainTtsManager(
    private val engine: TtsEngine,
    private val scope: CoroutineScope
) {

    companion object {
        /**
         * Ceiling on one utterance.
         *
         * A network voice on a dying connection can hang forever inside speak(), and
         * a hung speak() means the speaking flag never clears and hands-free mode
         * never gives the microphone back. Something has to bound it.
         */
        private const val UTTERANCE_TIMEOUT_MS = 60_000L

        /** How long to wait for the engine to come up before giving up on a line. */
        private const val WARM_UP_TIMEOUT_MS = 5_000L
    }

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)

    /** Why she is not speaking, when she should be. Null when everything is fine. */
    val failure: StateFlow<String?> = _failure.asStateFlow()

    /** What is being said right now, for the detector's echo check. */
    private val _currentText = MutableStateFlow("")
    val currentText: StateFlow<String> = _currentText.asStateFlow()

    private val queue = Channel<String>(Channel.UNLIMITED)
    private var drain: Job? = null

    /** Lines queued but not yet finished, so [isSpeaking] spans a whole reply. */
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var warmedUp = false

    init {
        startDraining()
    }

    private fun startDraining() {
        drain?.cancel()
        drain = scope.launch {
            for (line in queue) {
                if (line.isBlank()) continue
                _isSpeaking.value = true
                _currentText.value = line
                val spoke = withTimeoutOrNull(UTTERANCE_TIMEOUT_MS) {
                    runCatching { engine.speak(line) }.isSuccess
                }
                if (spoke != true) {
                    // Says what went wrong rather than going quiet. A synthesiser
                    // that silently does nothing is indistinguishable from an app
                    // that has crashed.
                    _failure.value = "The voice didn't play that line"
                } else {
                    _failure.value = null
                }
                _currentText.value = ""
                // Only drop the flag when nothing is waiting behind this line.
                // Clearing it between chunks makes the UI flicker and, worse, tells
                // hands-free mode the reply is over while it is still being read.
                if (pending.decrementAndGet() <= 0) _isSpeaking.value = false
            }
            _isSpeaking.value = false
            _currentText.value = ""
        }
    }

    /**
     * Queues a line. Returns immediately; use [isSpeaking] to know when it lands.
     *
     * The warm-up is the fix for a real and very visible bug: TextToSpeech reports
     * readiness through a callback, and the old engine simply returned without
     * speaking if the callback hadn't arrived yet. The first thing Lain ever tried
     * to say after a cold start was therefore dropped, every time, silently.
     */
    fun say(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        scope.launch {
            if (!warmedUp) {
                withTimeoutOrNull(WARM_UP_TIMEOUT_MS) { runCatching { engine.warmUp() } }
                warmedUp = true
            }
            pending.incrementAndGet()
            if (!queue.trySend(line).isSuccess) pending.decrementAndGet()
        }
    }

    /**
     * Stops now and drops anything queued.
     *
     * Order matters and is the whole method: cancel the drain so no further line can
     * start, drain the channel so a cancelled utterance is not replayed, then stop
     * the engine, then restart the drain ready for the next turn.
     */
    fun stop() {
        drain?.cancel()
        while (queue.tryReceive().isSuccess) { /* discard */ }
        pending.set(0)
        runCatching { engine.stop() }
        _isSpeaking.value = false
        _currentText.value = ""
        startDraining()
    }
}
