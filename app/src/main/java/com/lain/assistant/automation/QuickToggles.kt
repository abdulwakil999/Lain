package com.lain.assistant.automation

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolResult

/**
 * Device switches, done without leaving Lain wherever Android permits it.
 *
 * The platform draws a hard line here and it is worth stating plainly, because
 * pretending otherwise is how an assistant ends up lying:
 *
 *  - Do Not Disturb, ringer mode and volume are genuinely flippable in-process
 *    once the user has granted notification-policy access. These complete inside
 *    Lain, with no screen change at all.
 *  - Wi-Fi, Bluetooth, mobile data and aeroplane mode are NOT. `setWifiEnabled`
 *    became a no-op for third-party apps in Android 10, and `BluetoothAdapter.enable`
 *    in Android 13. This was a deliberate lockdown, not an oversight, and no
 *    amount of cleverness gets round it — anything that appears to is either a
 *    system app or an accessibility-driven fake that breaks on the next OEM skin.
 *    What Android offers instead is [Settings.Panel]: a real system switch that
 *    slides up *over* Lain. The user never leaves the app, and the toggle is the
 *    genuine one. That is the honest maximum, and it is what this does.
 *  - System dark mode needs WRITE_SECURE_SETTINGS, which is adb-only, so it is not
 *    offered here at all. Lain's own look is fixed and doesn't need a switch.
 */
class QuickToggles(private val context: Context) {

    private val notifications: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    private val audio: AudioManager?
        get() = context.getSystemService(AudioManager::class.java)

    // ------------------------------------------------------ do not disturb

    /** Whether the user has allowed Lain to change DND. Never assumed. */
    fun hasDndAccess(): Boolean =
        runCatching { notifications?.isNotificationPolicyAccessGranted == true }.getOrDefault(false)

    fun openDndAccessSettings(): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    fun dndState(): String = when (notifications?.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_NONE -> "on (total silence)"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "on (priority only)"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "on (alarms only)"
        NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
        else -> "unknown"
    }

    /**
     * @param mode "on" / "priority" / "alarms" / "silence" / "off"
     *
     * Completes in-app. Verified by reading the filter back afterwards rather than
     * trusting the setter, because policy access can be revoked between the check
     * and the call.
     */
    fun setDoNotDisturb(mode: String): ToolResult {
        val manager = notifications
            ?: return ToolResult.fail(FailureKind.CAPABILITY_UNAVAILABLE, "No notification service on this device.")

        if (!hasDndAccess()) {
            openDndAccessSettings()
            return ToolResult.fail(
                FailureKind.PERMISSION,
                "Lain needs Do Not Disturb access before she can change it. The permission screen is open — " +
                    "switch Lain on there, then ask again. Nothing has changed yet."
            )
        }

        val target = when (mode.trim().lowercase()) {
            "off", "disable", "false" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "silence", "total", "none", "everything" -> NotificationManager.INTERRUPTION_FILTER_NONE
            "alarms", "alarms only" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            else -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
        }

        return try {
            manager.setInterruptionFilter(target)
            val now = manager.currentInterruptionFilter
            if (now == target) {
                ToolResult.ok("Do Not Disturb is ${dndState()}.")
            } else {
                ToolResult.fail(
                    FailureKind.TOOL_FAILURE,
                    "Asked for that, but the phone still reports Do Not Disturb ${dndState()}."
                )
            }
        } catch (t: SecurityException) {
            ToolResult.fail(FailureKind.PERMISSION, "Do Not Disturb access was refused.", t.message)
        }
    }

    // ------------------------------------------------------------- ringer

    /** @param mode "silent" / "vibrate" / "normal". In-app, no screen change. */
    fun setRingerMode(mode: String): ToolResult {
        val am = audio ?: return ToolResult.fail(FailureKind.CAPABILITY_UNAVAILABLE, "No audio service.")
        val target = when (mode.trim().lowercase()) {
            "silent", "mute", "silence" -> AudioManager.RINGER_MODE_SILENT
            "vibrate", "vibration" -> AudioManager.RINGER_MODE_VIBRATE
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        // Silent and vibrate cross into DND territory from Android 7, so the same
        // grant gates them. Sending the user to the right screen beats a SecurityException.
        if (target != AudioManager.RINGER_MODE_NORMAL && !hasDndAccess()) {
            openDndAccessSettings()
            return ToolResult.fail(
                FailureKind.PERMISSION,
                "Android routes silent and vibrate through Do Not Disturb access. The permission screen is " +
                    "open — allow Lain there and ask again. The ringer is unchanged."
            )
        }
        return try {
            am.ringerMode = target
            val now = when (am.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
            ToolResult.ok("Ringer is now $now.")
        } catch (t: SecurityException) {
            ToolResult.fail(FailureKind.PERMISSION, "The system refused the ringer change.", t.message)
        }
    }

    // -------------------------------------------------------------- panels

    /**
     * The connectivity switches, floated over Lain instead of navigating to Settings.
     *
     * [Settings.Panel] is Android's own answer to "apps can no longer flip this":
     * a system-owned slice that appears on top of the calling app. The user taps the
     * real toggle and stays where they were. Below Android 10 there is no panel, so
     * it falls back to the settings page and the reply says so.
     */
    fun openPanel(what: String): ToolResult {
        val key = what.trim().lowercase()
        val panel = when {
            key.contains("wifi") || key.contains("wi-fi") -> "android.settings.panel.action.WIFI"
            key.contains("data") || key.contains("mobile") || key.contains("internet") ||
                key.contains("cellular") -> "android.settings.panel.action.INTERNET_CONNECTIVITY"
            key.contains("volume") || key.contains("sound") -> "android.settings.panel.action.VOLUME"
            key.contains("nfc") -> "android.settings.panel.action.NFC"
            else -> null
        }

        val friendly = when {
            key.contains("bluetooth") -> "Bluetooth"
            key.contains("wifi") || key.contains("wi-fi") -> "Wi-Fi"
            key.contains("data") || key.contains("mobile") || key.contains("internet") -> "mobile data"
            else -> key
        }

        if (panel != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val opened = runCatching {
                context.startActivity(Intent(panel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (opened) {
                return ToolResult.ok(
                    "$friendly is on screen now, over Lain — flip it and you're straight back. " +
                        "Android hasn't let apps switch this themselves since Android 10, so this is the real toggle."
                )
            }
        }

        // Bluetooth has no panel; the system enable dialog is the nearest equivalent
        // and it also appears over the calling app.
        if (key.contains("bluetooth")) {
            val shown = runCatching {
                context.startActivity(
                    Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrDefault(false)
            if (shown) {
                return ToolResult.ok("Android is asking you to confirm turning Bluetooth on — that prompt is the only way an app can.")
            }
        }

        return DeviceController(context).openSettingsPage(friendly)
    }

    // -------------------------------------------------------------- status

    /** One line covering everything in here, for "what's on right now". */
    fun summary(): String {
        val ringer = when (audio?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            AudioManager.RINGER_MODE_NORMAL -> "normal"
            else -> "unknown"
        }
        return "Do Not Disturb ${dndState()}, ringer $ringer."
    }
}
