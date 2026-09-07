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
    private fun match(spokenName: String): Pair<String, String>? {
        resolve(spokenName, installedApps())?.let { return it }

        // Nothing matched. Before saying an app isn't installed — which is the reply
        // people report as simply wrong — rebuild the list once and look again. The
        // cache is invalidated by package broadcasts, and that registration is inside
        // a runCatching: if it ever failed, or the process missed the broadcast, the
        // list is a snapshot from launch and every app installed since is invisible.
        // A miss is exactly the moment that is worth ruling out.
        Cache.invalidate()
        return resolve(spokenName, installedApps())
    }

    private fun resolve(
        spokenName: String,
        apps: List<Pair<String, String>>
    ): Pair<String, String>? {
        when (val byLabel = FuzzyMatch.best(spokenName, apps) { it.second }) {
            is FuzzyMatch.Result.Found -> return byLabel.hit.value
            // A tie still opens the strongest candidate: opening the wrong app is a
            // back-press, unlike calling the wrong person. openAppDetailed is there
            // for callers that want to surface the choice.
            is FuzzyMatch.Result.Ambiguous -> return byLabel.hits.first().value
            FuzzyMatch.Result.None -> Unit
        }

        // The display name is not the only name an app has. "WhatsApp Business" is
        // com.whatsapp.w4b, "Files by Google" is com.google.android.apps.nbu.files,
        // and people say "w4b" and "files" — neither of which scores against the
        // label. The package is a second, independent way in.
        val needle = spokenName.lowercase().filter { it.isLetterOrDigit() }
        if (needle.length < 3) return null

        return apps.firstOrNull { (pkg, _) ->
            // The last dotted segment is the app's own name in almost every package.
            pkg.substringAfterLast('.').lowercase().contains(needle)
        } ?: apps.firstOrNull { (pkg, _) ->
            pkg.lowercase().replace(".", "").contains(needle)
        } ?: apps.firstOrNull { (_, label) ->
            // Last resort: bare substring on a squashed label, which catches the
            // spacing and punctuation differences fuzzy scoring discounts —
            // "yt music" against "YT Music", "xbox" against "Xbox Game Pass".
            label.lowercase().filter { it.isLetterOrDigit() }.contains(needle)
        }
    }

    fun openApp(spokenName: String): AutomationResult {
        val hit = match(spokenName)
            ?: return AutomationResult.Failure(
                "No installed app matches \"$spokenName\" among the ${installedApps().size} apps on this " +
                    "phone. Call list_apps with part of the name to check the spelling before telling " +
                    "the user it isn't installed."
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

        /** Drops the snapshot so the next lookup rebuilds it. */
        @Synchronized
        fun invalidate() {
            apps = null
        }

        @Synchronized
        fun get(context: Context): List<Pair<String, String>> {
            apps?.let { return it }
            val app = context.applicationContext
            registerOnce(app)
            val built = build(app)
            apps = built
            return built
        }

        /**
         * Everything on the phone that can actually be opened.
         *
         * Two sources, unioned. The launcher query is the fast one and covers almost
         * everything. The installed-packages sweep catches what it misses: apps whose
         * entry point is a LEANBACK or CAR category rather than LAUNCHER, and apps
         * that ship a launch intent without declaring the category at all. Those are
         * a minority, but they are exactly the ones a user swears are installed while
         * Lain says they are not.
         */
        private fun build(context: Context): List<Pair<String, String>> {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

            val fromLauncher = runCatching {
                pm.queryIntentActivities(launcherIntent, 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            }.getOrDefault(emptyList())

            val seen = fromLauncher.mapTo(mutableSetOf()) { it.first }

            val fromPackages = runCatching {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
                    .asSequence()
                    .filter { it.packageName !in seen }
                    // Openable is the whole test. An app with no launch intent cannot
                    // be started and listing it would only produce a different wrong
                    // answer.
                    .filter { runCatching { pm.getLaunchIntentForPackage(it.packageName) != null }.getOrDefault(false) }
                    .map { it.packageName to runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName) }
                    .toList()
            }.getOrDefault(emptyList())

            return (fromLauncher + fromPackages).distinctBy { it.first }
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
