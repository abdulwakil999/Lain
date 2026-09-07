package com.lain.assistant

import android.app.Application
import com.lain.assistant.agent.Trace
import com.lain.assistant.automation.AccessibilityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LainApplication : Application() {
    lateinit var container: AppContainer
        private set

    companion object {
        /**
         * One scope for work that legitimately outlives whatever started it — a
         * scheduled task firing from a receiver, an alarm snoozing as its Activity
         * finishes. These were each creating a CoroutineScope on the spot, which
         * leaks a Job nothing ever cancels. A SupervisorJob keeps one failure from
         * taking the rest down with it.
         */
        val appScope = CoroutineScope(SupervisorJob())
    }

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

        // The always-on service is the user's switch, so it has to survive a process
        // restart on its own — an OEM battery manager killing the app is exactly the
        // case it exists for, and it would be useless if it only came back when
        // somebody happened to open the app.
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (container.userPreferencesRepository.isAlwaysOn.first()) {
                    com.lain.assistant.agent.AlwaysOnService.start(this@LainApplication)
                }
            }
            // Same reasoning for hands-free: a switch the user threw has to survive a
            // reboot and a process kill, or it is a switch that quietly stops working.
            // The service itself checks the permission and stands down without it.
            runCatching {
                if (container.userPreferencesRepository.isWakeWordEnabled.first()) {
                    com.lain.assistant.automation.WakeWordService.start(this@LainApplication)
                }
            }
        }
    }
}
