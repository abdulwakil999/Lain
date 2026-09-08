package com.lain.assistant.voice

/**
 * Where the voice pipeline is, as one value.
 *
 * Before this there was no such value. "Am I listening" lived in ChatEngine, "is the
 * detector running" lived in a static on the service, and "is she speaking" lived in
 * StreamingSpeaker — three sources that could and did disagree, which is how a voice
 * assistant ends up stuck on "Listening…" with the microphone already released. One
 * state, one owner, one place to put a watchdog.
 */
enum class VoiceState {
    /** Voice is off entirely. Nothing holds the microphone. */
    IDLE,

    /** Hands-free is on: one open stream, measured for loudness and nothing else. */
    LISTENING_FOR_WAKE_WORD,

    /** Her name was heard. A momentary state — the chirp plays here. */
    WAKE_WORD_DETECTED,

    /** Recording the command, with full speech recognition. */
    LISTENING_FOR_COMMAND,

    /** The command is with the router or the model. */
    PROCESSING,

    /** A tool is running: an app opening, a message going out. */
    EXECUTING,

    /** Reading the reply aloud. */
    SPEAKING,

    /** Something failed. Carries a reason; recovers on its own. */
    ERROR;

    /** What the UI says. Kept here so every surface says the same thing. */
    val label: String
        get() = when (this) {
            IDLE -> "Tap to talk"
            LISTENING_FOR_WAKE_WORD -> "Say Lain"
            WAKE_WORD_DETECTED -> "Listening…"
            LISTENING_FOR_COMMAND -> "Listening…"
            PROCESSING -> "Thinking…"
            EXECUTING -> "Working…"
            SPEAKING -> "Speaking…"
            ERROR -> "Voice unavailable"
        }

    /** True while the microphone is genuinely in use, for the UI and the arbiter. */
    val holdsMicrophone: Boolean
        get() = this == LISTENING_FOR_WAKE_WORD || this == LISTENING_FOR_COMMAND

    /**
     * States that must not last.
     *
     * Every one of them is waiting on something that can fail silently — a
     * recogniser that never calls back, a synthesiser that never reports done, a
     * request that never returns. The watchdog reads this list rather than a
     * hand-maintained one somewhere else.
     */
    val isTransient: Boolean
        get() = this == WAKE_WORD_DETECTED || this == LISTENING_FOR_COMMAND ||
            this == PROCESSING || this == EXECUTING || this == SPEAKING
}
