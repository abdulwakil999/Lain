package com.lain.assistant.automation

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** What the Accessibility Service is doing, as far as this process can tell. */
enum class AccessibilityState {
    /** Never observed — the app has just started and nothing has reported in yet. */
    UNKNOWN,

    /** Switched on in Settings, but this process holds no live binding yet. */
    CONNECTING,

    /** Bound and usable. Actions will actually reach the screen. */
    CONNECTED,

    /** Not available. Any screen action will fail, and must be reported as failing. */
    DISCONNECTED
}

/**
 * Tracks the Accessibility Service's real state and records why it changed.
 *
 * The service was previously represented by a nullable static and a health check
 * computed on demand. That is enough to answer "can I tap right now", and useless
 * for the question actually being asked — *why did it stop*. A disconnect left no
 * evidence whatsoever, so every investigation started from zero.
 *
 * This keeps two things: an observable state, so the UI reacts to a drop the
 * moment it happens rather than on the next resume; and a bounded ring of
 * timestamped events that can be read out of the app and pasted into a bug
 * report.
 *
 * Deliberately does nothing clever. It does not attempt to restart the service,
 * re-enable it, or work around Android's controls — none of which an app is
 * permitted to do, and all of which would be the wrong response to a permission
 * the user owns. It observes, records, and tells the truth.
 */
object AccessibilityMonitor {

    private const val TAG = "LainA11y"

    /** Enough history to cover a session's worth of transitions without unbounded growth. */
    private const val MAX_EVENTS = 200

    /** The event vocabulary, fixed so logs are greppable and comparable across runs. */
    enum class Event {
        SERVICE_CREATED,
        SERVICE_CONNECTED,
        SERVICE_INTERRUPTED,
        SERVICE_UNBOUND,
        SERVICE_DESTROYED,
        SERVICE_RESTARTED,
        PROCESS_RECREATED,
        EXCEPTION,
        COMMAND_RECEIVED,
        COMMAND_EXECUTED,
        COMMAND_VERIFIED,
        COMMAND_REJECTED,
        STATE_CHANGED
    }

    data class Entry(
        val at: Long,
        val event: Event,
        val detail: String,
        val state: AccessibilityState
    ) {
        fun render(): String {
            val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.UK).format(Date(at))
            return "$stamp  ${event.name.padEnd(20)} [$state] $detail"
        }
    }

    private val _state = MutableStateFlow(AccessibilityState.UNKNOWN)

    /** Observable, so the UI shows a drop as it happens rather than on next resume. */
    val state: StateFlow<AccessibilityState> = _state.asStateFlow()

    private val entries = ArrayDeque<Entry>()

    /**
     * When this process first started. A SERVICE_CONNECTED shortly after a fresh
     * process start means Android restarted us — the signature of an OEM battery
     * manager having killed the app.
     */
    private val processStartedAt = SystemClock.elapsedRealtime()

    /** How many times the service has connected within this process's lifetime. */
    @Volatile
    var connectCount = 0
        private set

    @Volatile
    private var lastConnectedAt: Long = 0

    @Volatile
    private var lastDisconnectedAt: Long = 0

    // ------------------------------------------------------------- recording

    @Synchronized
    fun record(event: Event, detail: String = "") {
        val entry = Entry(System.currentTimeMillis(), event, detail, _state.value)
        entries.addLast(entry)
        while (entries.size > MAX_EVENTS) entries.removeFirst()
        // Warnings for the transitions worth noticing in a busy logcat.
        when (event) {
            Event.EXCEPTION, Event.SERVICE_DESTROYED, Event.SERVICE_INTERRUPTED, Event.COMMAND_REJECTED ->
                Log.w(TAG, entry.render())
            else -> Log.i(TAG, entry.render())
        }
    }

    fun recordException(where: String, t: Throwable) {
        record(Event.EXCEPTION, "$where: ${t::class.java.simpleName}: ${t.message}")
    }

    // ---------------------------------------------------------- transitions

    fun onCreated() {
        record(Event.SERVICE_CREATED, "process up ${SystemClock.elapsedRealtime() - processStartedAt}ms")
    }

    fun onConnected() {
        connectCount++
        lastConnectedAt = SystemClock.elapsedRealtime()

        // A reconnect inside one process means Android tore the binding down and
        // rebuilt it without killing us — usually a settings toggle.
        if (connectCount > 1) {
            record(Event.SERVICE_RESTARTED, "connect #$connectCount, ${gapSinceDisconnect()}ms after the drop")
        }
        // A connect this soon after process start means the process itself was
        // recreated, which is what an OEM task-killer looks like from in here.
        if (connectCount == 1 && lastConnectedAt < 5_000) {
            record(Event.PROCESS_RECREATED, "connected ${lastConnectedAt}ms after process start")
        }
        moveTo(AccessibilityState.CONNECTED, "onServiceConnected")
    }

    fun onInterrupted() {
        // onInterrupt does NOT mean disconnected — it means "abandon what you're
        // announcing". Treating it as a disconnect would report a fault that isn't one.
        record(Event.SERVICE_INTERRUPTED, "feedback interrupted; binding still held")
    }

    fun onUnbound() {
        record(Event.SERVICE_UNBOUND, "system unbound the service")
        lastDisconnectedAt = SystemClock.elapsedRealtime()
        moveTo(AccessibilityState.DISCONNECTED, "onUnbind")
    }

    fun onDestroyed() {
        record(Event.SERVICE_DESTROYED, "lived ${SystemClock.elapsedRealtime() - lastConnectedAt}ms")
        lastDisconnectedAt = SystemClock.elapsedRealtime()
        moveTo(AccessibilityState.DISCONNECTED, "onDestroy")
    }

    private fun gapSinceDisconnect(): Long =
        if (lastDisconnectedAt == 0L) -1 else SystemClock.elapsedRealtime() - lastDisconnectedAt

    @Synchronized
    private fun moveTo(next: AccessibilityState, why: String) {
        if (_state.value == next) return
        val previous = _state.value
        _state.value = next
        record(Event.STATE_CHANGED, "$previous -> $next ($why)")
    }

    // ------------------------------------------------------------ reconcile

    /**
     * Reconciles the tracked state against what Settings actually says.
     *
     * Needed because two of the four states cannot be observed from a callback.
     * Nothing tells the app "the user just switched you off" — the service is
     * simply destroyed, and if the whole process was killed there is no callback
     * at all. Reading Settings.Secure closes that gap, and distinguishes
     * "switched off" (DISCONNECTED, user must turn it on) from "switched on but
     * not bound yet" (CONNECTING, wait or toggle), which need different advice.
     */
    fun reconcile(context: Context) {
        val bound = LainAccessibilityService.instance != null
        val enabled = isEnabledInSettings(context)
        val next = when {
            bound -> AccessibilityState.CONNECTED
            enabled -> AccessibilityState.CONNECTING
            else -> AccessibilityState.DISCONNECTED
        }
        moveTo(next, "reconcile(bound=$bound, enabledInSettings=$enabled)")
    }

    /** Whether the user has granted the service, regardless of binding. */
    fun isEnabledInSettings(context: Context): Boolean = runCatching {
        val expected = ComponentName(context, LainAccessibilityService::class.java)
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(flat)
        for (entry in splitter) {
            if (ComponentName.unflattenFromString(entry) == expected) return true
        }
        false
    }.getOrDefault(false)

    // -------------------------------------------------------------- reading

    /** True only when an action will actually reach the screen. */
    val isUsable: Boolean get() = _state.value == AccessibilityState.CONNECTED

    /**
     * What to tell the user, matched to the state — because the fix differs and
     * telling someone to enable something they already enabled is how an app
     * looks broken.
     */
    fun advice(): String = when (_state.value) {
        AccessibilityState.CONNECTED -> "Accessibility is connected."
        AccessibilityState.CONNECTING ->
            "Lain's Accessibility Service is switched on but hasn't connected. Android does this after an " +
                "update or when it reclaims memory. Toggle Lain off and back on in Accessibility settings."
        AccessibilityState.DISCONNECTED ->
            "Lain's Accessibility Service is off, so reading and tapping the screen aren't available. " +
                "Turn it on in Settings > Accessibility > Lain."
        AccessibilityState.UNKNOWN ->
            "Lain hasn't been able to check the Accessibility Service yet."
    }

    /** Test hook: the singleton outlives a JVM test class, so state must be clearable. */
    @Synchronized
    internal fun resetForTest() {
        entries.clear()
        connectCount = 0
        lastConnectedAt = 0
        lastDisconnectedAt = 0
        _state.value = AccessibilityState.UNKNOWN
    }

    /** The diagnostic log, newest last, ready to paste into a report. */
    @Synchronized
    fun dump(): String = buildString {
        append("Lain accessibility log\n")
        append("state=${_state.value} connects=$connectCount ")
        append("processUp=${(SystemClock.elapsedRealtime() - processStartedAt) / 1000}s\n")
        append("android=${android.os.Build.VERSION.SDK_INT} ")
        append("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n")
        if (entries.isEmpty()) append("(no events recorded)\n")
        entries.forEach { append(it.render()).append('\n') }
    }
}
