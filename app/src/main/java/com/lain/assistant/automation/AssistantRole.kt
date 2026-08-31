package com.lain.assistant.automation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Whether Lain is the app the assistant gesture opens, and how to get there.
 *
 * The choice belongs to the user and Android enforces that: there is no API for an
 * app to make itself the assistant, and there should not be — the gesture is a
 * standing invitation to whatever is on the other end of it. All an app can do is
 * be selectable, and offer the screen.
 */
object AssistantRole {

    /**
     * True when this app currently holds the role.
     *
     * Read from `Settings.Secure`, which is the system's own record rather than
     * anything the app stored. The key is stable across Android versions but is not
     * public API, so a null or an unreadable value is treated as "not us" — claiming
     * the role on a device where it cannot be checked would be a guess presented as
     * a fact, and the whole screen exists to tell the truth about a setting.
     */
    fun isLain(context: Context): Boolean = runCatching {
        val current = Settings.Secure.getString(context.contentResolver, "assistant")
            ?: Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
            ?: return false
        current.startsWith(context.packageName)
    }.getOrDefault(false)

    /**
     * Opens the screen where the assistant is chosen.
     *
     * Three destinations, narrowest first. `ACTION_VOICE_INPUT_SETTINGS` is the
     * assistant picker itself on most devices; some OEMs have removed it, in which
     * case the default-apps list still contains the same choice a level up; and if a
     * ROM has neither, the top of Settings beats a crash.
     */
    fun openPicker(context: Context) {
        val candidates = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in candidates) {
            val opened = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrElse { error ->
                if (error is ActivityNotFoundException) false else false
            }
            if (opened) return
        }
    }
}
