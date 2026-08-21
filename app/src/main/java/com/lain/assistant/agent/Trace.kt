package com.lain.assistant.agent

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where the time actually goes on one user command.
 *
 * Latency work without measurement is guesswork, and the guesses had been wrong
 * before: the obvious suspect (model speed) turned out to be sharing the blame
 * with a new TLS handshake per message and a response that wasn't read until it
 * was complete. This records a timeline per turn so the answer comes from the
 * device rather than from reasoning about the code.
 *
 * Deliberately cheap: a marker is a string and a long appended to a lock-free
 * queue, so leaving it on costs nothing measurable next to the milliseconds it
 * reports. [enabled] still gates it, because a released build has no reason to
 * write logcat lines nobody reads.
 */
object Trace {

    private const val TAG = "LainPerf"

    /** Flip to true (or set from BuildConfig.DEBUG) to emit timings. */
    @Volatile
    var enabled: Boolean = false

    /** Optional sink so the app — or a test — can read timings without logcat. */
    @Volatile
    var sink: ((Turn) -> Unit)? = null

    /** One measured stage. */
    data class Mark(val label: String, val atMs: Long)

    /**
     * A complete user command, from the moment input arrived to the moment the
     * reply was fully delivered.
     */
    class Turn(val input: String) {
        private val startedAt = SystemClock.elapsedRealtime()
        private val marks = ConcurrentLinkedQueue<Mark>()

        /** How many times a model was asked to generate anything for this one command. */
        val llmRequests = AtomicInteger(0)

        /** Which path the router chose, for grouping timings by kind of request. */
        @Volatile
        var route: String = "unrouted"

        fun mark(label: String) {
            if (!enabled) return
            marks += Mark(label, SystemClock.elapsedRealtime() - startedAt)
        }

        /** Times a block and records how long it took, keeping the call site readable. */
        inline fun <T> time(label: String, block: () -> T): T {
            val begin = SystemClock.elapsedRealtime()
            try {
                return block()
            } finally {
                mark("$label=${SystemClock.elapsedRealtime() - begin}ms")
            }
        }

        fun countLlmRequest() {
            llmRequests.incrementAndGet()
        }

        val elapsedMs: Long get() = SystemClock.elapsedRealtime() - startedAt

        fun snapshot(): List<Mark> = marks.toList()

        fun finish() {
            if (!enabled) return
            mark("TOTAL")
            Log.i(TAG, render())
            sink?.invoke(this)
        }

        fun render(): String = buildString {
            append("[$route] llm_requests=${llmRequests.get()} total=${elapsedMs}ms \"")
            append(input.take(40))
            append("\"\n")
            // Absolute offsets rather than deltas: what matters is when the user first
            // saw something, not how long each internal stage took in isolation.
            marks.forEach { append("    +${it.atMs}ms  ${it.label}\n") }
        }
    }

    fun start(input: String): Turn = Turn(input)
}
