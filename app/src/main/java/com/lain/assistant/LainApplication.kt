package com.lain.assistant

import android.app.Application
import com.lain.assistant.agent.Trace

class LainApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Per-turn timings to logcat under the "LainPerf" tag. Debug builds only —
        // this is a development instrument, not a shipped behaviour.
        Trace.enabled = BuildConfig.DEBUG
        container = AppContainer(this)
    }
}
