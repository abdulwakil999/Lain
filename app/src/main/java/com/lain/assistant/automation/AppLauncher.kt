package com.lain.assistant.automation

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.lain.assistant.agent.FuzzyMatch

class AppLauncher(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    /**
     * All launchable, user-facing apps — used to resolve a spoken app name to a package.
     *
     * Cached, and that matters more than it looks. Building this list means one
     * `queryIntentActivities` plus a `loadLabel` per app, and `loadLabel` opens the
     * other app's resources over binder — on a phone with 150 apps that is 150
     * cross-process calls, several hundred milliseconds, every time. It was being
     * paid twice for a single "open WhatsApp" (once for the exact match, once for
     * the contains match) and again on every subsequent command. Now it's paid once
     * per install/uninstall.
     */
    fun installedApps(): List<Pair<String, String>> = Cache.get(context)

    /** The app's own display name for a spoken one, so a confirmation names what actually opened. */
    fun resolveLabel(spokenName: String): String? = match(spokenName)?.second

    fun resolvePackage(spokenName: String): String? = match(spokenName)?.first

    /**
     * Resolves a spoken app name.
     *
     * Was an exact-then-`contains` pair, which failed on the way people actually
     * name apps: "open call of duty mobile" is a *longer* string than the label
     * "Call of Duty", so `contains` never fired and the reply was "no app matches"
     * for an app sitting on the home screen. [FuzzyMatch] scores both directions,
     * ignores case and punctuation, and tolerates the filler words store listings
     * are full of.
     */
    private fun match(spokenName: String): Pair<String, String>? =
        when (val result = FuzzyMatch.best(spokenName, installedApps()) { it.second }) {
            is FuzzyMatch.Result.Found -> result.hit.value
            // A tie still opens the strongest candidate: opening the wrong app is a
            // back-press, unlike calling the wrong person. openAppDetailed is there
            // for callers that want to surface the choice.
            is FuzzyMatch.Result.Ambiguous -> result.hits.first().value
            FuzzyMatch.Result.None -> null
        }

    fun openApp(spokenName: String): AutomationResult {
        val hit = match(spokenName)
            ?: return AutomationResult.Failure(
                "No installed app matches \"$spokenName\". Call list_apps to see what is installed."
            )
        val (packageName, label) = hit
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: return AutomationResult.Failure("\"$label\" has no launchable activity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            // Names the app that actually opened, not what was asked for — "Opened
            // Call of Duty" after "open call of duty mobile" tells the user the match
            // was understood.
            AutomationResult.Success("Opened $label")
        } catch (t: Throwable) {
            AutomationResult.Failure(t.message ?: "Could not open $label")
        }
    }

    /**
     * Android does not let a normal app force-quit another app — that
     * capability was removed for regular (non-device-owner) apps to stop
     * abuse. `killBackgroundProcesses` only reaps processes the system has
     * already cached in the background, not whatever is currently in the
     * foreground. Genuinely "closing" a foreground app needs the
     * Accessibility Service driving the system Recents UI and swiping the
     * card away — see LainAccessibilityService.closeCurrentApp().
     */
    fun killBackgroundProcess(spokenName: String): AutomationResult {
        val packageName = resolvePackage(spokenName)
            ?: return AutomationResult.Failure("No installed app matches \"$spokenName\"")
        val am = context.getSystemService(ActivityManager::class.java)
        return try {
            am.killBackgroundProcesses(packageName)
            AutomationResult.Success("Stopped background process for $spokenName (won't force-quit a foreground app — use the Recents-swipe path for that)")
        } catch (t: Throwable) {
            AutomationResult.Failure(t.message ?: "Could not stop $spokenName")
        }
    }

    /**
     * The installed-app list, held for the process and invalidated by the system's
     * own package broadcasts rather than by a timer.
     *
     * A time-based cache would be wrong in both directions: too short and the cost
     * comes back, too long and an app installed a minute ago can't be opened.
     * Android already tells us exactly when the answer changed.
     */
    private object Cache {
        @Volatile
        private var apps: List<Pair<String, String>>? = null

        @Volatile
        private var receiverRegistered = false

        @Synchronized
        fun get(context: Context): List<Pair<String, String>> {
            apps?.let { return it }
            val app = context.applicationContext
            registerOnce(app)
            val built = build(app)
            apps = built
            return built
        }

        private fun build(context: Context): List<Pair<String, String>> {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            return runCatching {
                pm.queryIntentActivities(launcherIntent, 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .distinctBy { it.first }
            }.getOrDefault(emptyList())
        }

        private fun registerOnce(context: Context) {
            if (receiverRegistered) return
            receiverRegistered = true
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    object : BroadcastReceiver() {
                        override fun onReceive(c: Context?, i: Intent?) {
                            // Dropped rather than rebuilt: the next lookup pays for it,
                            // and most package changes are never followed by one.
                            apps = null
                        }
                    },
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }
        }
    }
}
