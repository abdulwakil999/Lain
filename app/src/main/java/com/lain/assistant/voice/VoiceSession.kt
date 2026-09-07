package com.lain.assistant.voice

import com.lain.assistant.LainApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * The one thing that knows what the voice pipeline is doing, and the only thing
 * allowed to hand out the microphone.
 *
 * Two problems this exists to solve, both of which were real.
 *
 * **Two listeners at once.** The chat engine holds a recogniser and the music
 * identifier holds a recorder. Nothing coordinated them, so starting one while the
 * other was live got ERROR_RECOGNIZER_BUSY — from the outside, Lain simply ignoring
 * you. [claimMicrophone] makes the contention explicit and always resolvable.
 *
 * **Nothing could get unstuck.** With the state spread across three classes there
 * was no place to notice that "Listening…" had been true for four minutes. There is
 * now, and [watchdog] is the whole reason the states carry [VoiceState.isTransient].
 *
 * An object rather than an injected instance because the microphone is a device
 * singleton — a second arbiter would arbitrate nothing.
 */
object VoiceSession {

    /** Names used when claiming the mic, so a leak says who leaked it. */
    const val OWNER_COMMAND = "command"

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** The most recent failure, cleared when the pipeline recovers. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val owner = AtomicReference<String?>(null)

    /** Who holds the microphone, or null. */
    val microphoneOwner: String? get() = owner.get()

    /**
     * Takes the microphone for [who], or fails.
     *
     * Compare-and-set rather than a lock: a caller that cannot have the mic must
     * find out immediately and do something else, not block a coroutine waiting for
     * a holder that may never let go.
     */
    fun claimMicrophone(who: String): Boolean = owner.compareAndSet(null, who)

    /** Releases only if [who] is still the holder, so a late release can't steal it. */
    fun releaseMicrophone(who: String) {
        owner.compareAndSet(who, null)
    }

    /**
     * Takes the microphone from whoever has it.
     *
     * Used on one path only: the user interrupting. Everything else negotiates.
     */
    fun forceReleaseMicrophone() {
        owner.set(null)
    }

    // ------------------------------------------------------------------ state

    fun enter(next: VoiceState, error: String? = null) {
        if (next == VoiceState.ERROR) _lastError.value = error ?: "Voice unavailable"
        else if (next != VoiceState.IDLE) _lastError.value = null
        _state.value = next
        restartWatchdog(next)
    }

    /** True when voice is doing anything the user would notice. */
    val isActive: Boolean get() = _state.value != VoiceState.IDLE

    // -------------------------------------------------------------- watchdog

    private var watchdog: Job? = null

    /**
     * The promise that Lain never sits on "Listening…" forever.
     *
     * Each transient state gets a ceiling generous enough that a slow but working
     * step is never cut off, and short enough that a dead one is noticed while the
     * user is still in the room. Speaking gets the longest because a long reply
     * genuinely takes a while to read out.
     */
    private fun timeoutFor(state: VoiceState): Long = when (state) {
        VoiceState.LISTENING_FOR_COMMAND -> 20_000L
        VoiceState.PROCESSING -> 90_000L
        VoiceState.EXECUTING -> 180_000L
        VoiceState.SPEAKING -> 120_000L
        else -> 0L
    }

    private fun restartWatchdog(state: VoiceState) {
        watchdog?.cancel()
        if (!state.isTransient) return
        val limit = timeoutFor(state)
        watchdog = LainApplication.appScope.launch {
            delay(limit)
            // Still here, still the same state: whatever we were waiting on is not
            // coming. Let go of the microphone before going idle, or the next attempt
            // fails on a token nobody owns any more.
            if (_state.value == state) {
                forceReleaseMicrophone()
                _lastError.value = "Voice timed out in ${state.name.lowercase().replace('_', ' ')}"
                _state.value = VoiceState.IDLE
            }
        }
    }

    /** Called when voice is switched off, so nothing is left armed. */
    fun shutDown() {
        watchdog?.cancel()
        watchdog = null
        forceReleaseMicrophone()
        _state.value = VoiceState.IDLE
    }
}
