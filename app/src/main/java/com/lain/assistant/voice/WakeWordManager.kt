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
 * **Two listeners at once.** The wake-word service held a recogniser and the chat
 * engine held another. Nothing coordinated them, so the handover after a wake word
 * was a race: on a slow phone the command recogniser started before the wake-word
 * one had let go, and Android gave the second one ERROR_RECOGNIZER_BUSY. From the
 * outside that is Lain waking up and then ignoring you. [claimMicrophone] makes the
 * contention explicit and always resolvable.
 *
 * **Nothing could get unstuck.** With the state spread across three classes there
 * was no place to notice that "Listening…" had been true for four minutes. There is
 * now, and [watchdog] is the whole reason the states carry [VoiceState.isTransient].
 *
 * An object rather than an injected instance because the microphone is a device
 * singleton — a second arbiter would arbitrate nothing.
 */
object WakeWordManager {

    /** Names used when claiming the mic, so a leak says who leaked it. */
    const val OWNER_WAKE_WORD = "wake-word"
    const val OWNER_COMMAND = "command"

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** The most recent failure, cleared when the pipeline recovers. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val owner = AtomicReference<String?>(null)

    /** Who holds the microphone, or null. Read by the detector before it opens one. */
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

    /**
     * What Lain is reading aloud right now, or empty.
     *
     * Lives here rather than on the detector because the two are in different
     * components with no reference to each other, and the detector needs it for one
     * specific reason: a phone's speaker is the loudest thing its own microphone
     * hears, so without knowing her own words she wakes herself up on any reply that
     * happens to contain her name. Echo cancellation gets most of it and not all.
     */
    @Volatile
    var spokenAloud: String = ""

    // ------------------------------------------------------------ diagnostics

    /**
     * Enough to tell a working detector from a broken one, from the Settings screen.
     *
     * This exists because the last two attempts at hands-free shipped broken and the
     * only report available was "it doesn't work" — which is true, and cannot be
     * acted on. A count of checks, a count of wakes and the last thing the recogniser
     * said turns the next round into reading rather than guessing.
     *
     * Nothing here is stored or sent. It is in memory and dies with the process.
     */
    private val _checks = MutableStateFlow(0)
    val checks: StateFlow<Int> = _checks.asStateFlow()

    private val _wakes = MutableStateFlow(0)
    val wakes: StateFlow<Int> = _wakes.asStateFlow()

    private val _diagnostic = MutableStateFlow<String?>(null)
    val diagnostic: StateFlow<String?> = _diagnostic.asStateFlow()

    /** One candidate utterance was sent for checking. */
    fun recordCheck() {
        _checks.value = _checks.value + 1
    }

    /** Her name was actually found. */
    fun recordWake() {
        _wakes.value = _wakes.value + 1
    }

    fun recordDiagnostic(note: String) {
        _diagnostic.value = note
    }

    /** A one-line summary for the Settings screen. */
    fun diagnosticSummary(): String {
        val c = _checks.value
        val w = _wakes.value
        val base = when {
            c == 0 -> "Nothing has sounded like speech yet."
            w == 0 -> "$c candidate(s) checked, none were her name."
            else -> "$c checked, $w wake(s)."
        }
        return _diagnostic.value?.let { "$base Last: $it" } ?: base
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

    /**
     * Callbacks the hosting service registers so the watchdog can actually recover
     * rather than only report. Null when no service is running, which is itself the
     * correct answer — there is nothing to recover to.
     */
    @Volatile
    var onRecover: (() -> Unit)? = null

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
        VoiceState.WAKE_WORD_DETECTED -> 5_000L
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
            // coming. Let go of the microphone before recovering, or the next attempt
            // fails on a token nobody owns any more.
            if (_state.value == state) {
                forceReleaseMicrophone()
                _lastError.value = "Voice timed out in ${state.name.lowercase().replace('_', ' ')}"
                val recover = onRecover
                if (recover != null) recover() else _state.value = VoiceState.IDLE
            }
        }
    }

    /** Called when voice is switched off, so nothing is left armed. */
    fun shutDown() {
        _checks.value = 0
        _wakes.value = 0
        _diagnostic.value = null
        watchdog?.cancel()
        watchdog = null
        onRecover = null
        forceReleaseMicrophone()
        _state.value = VoiceState.IDLE
    }
}
