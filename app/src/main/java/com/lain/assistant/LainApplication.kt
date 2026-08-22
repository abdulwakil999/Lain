package com.lain.assistant

import android.app.Application
import com.lain.assistant.agent.Trace
import com.lain.assistant.automation.AccessibilityMonitor

class LainApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Per-turn timings to logcat under the "LainPerf" tag. Debug builds only —
        // this is a development instrument, not a shipped behaviour.
        Trace.enabled = BuildConfig.DEBUG
        container = AppContainer(this)
        // Establish the accessibility state at process start rather than leaving it
        // UNKNOWN until some screen happens to look. If the process was just recreated
        // by an OEM battery manager, this is the first evidence of it, and the log
        // needs a baseline entry to measure the next connect against.
        AccessibilityMonitor.reconcile(this)
    }
}
