package com.lain.assistant.automation

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

class AppLauncher(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    /** All launchable, user-facing apps — used to resolve a spoken app name to a package. */
    fun installedApps(): List<Pair<String, String>> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
    }

    fun resolvePackage(spokenName: String): String? {
        val query = spokenName.trim().lowercase()
        return installedApps().firstOrNull { (_, label) -> label.lowercase() == query }?.first
            ?: installedApps().firstOrNull { (_, label) -> label.lowercase().contains(query) }?.first
    }

    fun openApp(spokenName: String): AutomationResult {
        val packageName = resolvePackage(spokenName)
            ?: return AutomationResult.Failure("No installed app matches \"$spokenName\"")
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: return AutomationResult.Failure("\"$spokenName\" has no launchable activity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            AutomationResult.Success("Opened $spokenName")
        } catch (t: Throwable) {
            AutomationResult.Failure(t.message ?: "Could not open $spokenName")
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
}
