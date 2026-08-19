package com.lain.assistant.automation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolResult
import java.io.File

/**
 * Device-level capabilities that Android exposes to a normal app without special
 * privileges: battery, connectivity, volume, clipboard, settings deep links and
 * app-scoped file storage.
 *
 * Everything here is deliberately inside what the platform sanctions. Where
 * Android refuses (changing system settings, reading arbitrary storage), the tool
 * reports the limit instead of pretending, so the agent can tell the user the
 * truth rather than inventing a success.
 */
class DeviceController(private val context: Context) {

    // ------------------------------------------------------------- status

    fun deviceStatus(): ToolResult {
        return try {
            val bm = context.getSystemService(BatteryManager::class.java)
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val charging = bm?.isCharging == true

            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            val net = when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "connected"
            }
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

            val am = context.getSystemService(AudioManager::class.java)
            val vol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0

            ToolResult.ok(
                "Battery ${if (level >= 0) "$level%" else "unknown"}${if (charging) " (charging)" else ""}. " +
                    "Network: $net${if (!validated && caps != null) " (no internet)" else ""}. " +
                    "Media volume $vol/$maxVol. Android ${Build.VERSION.RELEASE} on ${Build.MANUFACTURER} ${Build.MODEL}."
            )
        } catch (t: Throwable) {
            ToolResult.fail(FailureKind.TOOL_FAILURE, "Couldn't read device status", t.message)
        }
    }

    /**
     * Media volume only. Ringer/DND changes need DO_NOT_DISTURB access that a
     * sideloaded app shouldn't silently assume, so they're not offered.
     */
    fun setMediaVolume(percent: Int): ToolResult {
        return try {
            val am = context.getSystemService(AudioManager::class.java)
                ?: return ToolResult.fail(FailureKind.CAPABILITY_UNAVAILABLE, "No audio service")
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (percent.coerceIn(0, 100) * max / 100.0).toInt()
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            ToolResult.ok("Media volume set to $percent% ($target/$max).")
        } catch (t: Throwable) {
            ToolResult.fail(FailureKind.PERMISSION, "Couldn't change volume", t.message)
        }
    }

    // ---------------------------------------------------------- clipboard

    fun readClipboard(): ToolResult {
        return try {
            val cm = context.getSystemService(ClipboardManager::class.java)
            val clip = cm?.primaryClip
            if (clip == null || clip.itemCount == 0) {
                return ToolResult.ok("Clipboard is empty.")
            }
            val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
            if (text.isBlank()) ToolResult.ok("Clipboard holds no text.")
            else ToolResult.ok("Clipboard: ${text.take(2000)}")
        } catch (t: Throwable) {
            // Android 10+ blocks clipboard reads unless the app has focus — a real limit, not a bug.
            ToolResult.fail(
                FailureKind.PERMISSION,
                "Can't read the clipboard right now — Android only allows it while Lain is the focused app.",
                t.message
            )
        }
    }

    fun writeClipboard(text: String): ToolResult {
        return try {
            val cm = context.getSystemService(ClipboardManager::class.java)
                ?: return ToolResult.fail(FailureKind.CAPABILITY_UNAVAILABLE, "No clipboard service")
            cm.setPrimaryClip(ClipData.newPlainText("Lain", text))
            ToolResult.ok("Copied to clipboard.")
        } catch (t: Throwable) {
            ToolResult.fail(FailureKind.TOOL_FAILURE, "Couldn't write to the clipboard", t.message)
        }
    }

    // ----------------------------------------------------- settings pages

    private val settingsPages = mapOf(
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "data" to Settings.ACTION_DATA_ROAMING_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "security" to Settings.ACTION_SECURITY_SETTINGS,
        "date" to Settings.ACTION_DATE_SETTINGS,
        "keyboard" to Settings.ACTION_INPUT_METHOD_SETTINGS,
        "notifications" to "android.settings.NOTIFICATION_SETTINGS",
        "settings" to Settings.ACTION_SETTINGS
    )

    fun openSettingsPage(page: String): ToolResult {
        val key = page.trim().lowercase()
        val action = settingsPages.entries.firstOrNull { key.contains(it.key) }?.value
            ?: return ToolResult.fail(
                FailureKind.INVALID_INPUT,
                "No settings page matches \"$page\". Available: ${settingsPages.keys.joinToString(", ")}"
            )
        return try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult.ok("Opened the $key settings page.")
        } catch (t: Throwable) {
            ToolResult.fail(FailureKind.APP_UNAVAILABLE, "This device has no $key settings screen", t.message)
        }
    }

    // ---------------------------------------------------------- files
    // Scoped to the app's own external directory. Android's storage rules mean a
    // normal app cannot roam the filesystem, and working within that is correct
    // rather than a shortfall.

    private fun baseDir(): File =
        (context.getExternalFilesDir(null) ?: context.filesDir).also { if (!it.exists()) it.mkdirs() }

    private fun resolve(path: String): File? {
        val clean = path.trim().removePrefix("/")
        val target = File(baseDir(), clean).canonicalFile
        // Refuse anything that escapes the sandbox via ../
        return if (target.path.startsWith(baseDir().canonicalFile.path)) target else null
    }

    fun listFiles(path: String = ""): ToolResult {
        val dir = resolve(path) ?: return ToolResult.fail(FailureKind.INVALID_INPUT, "Path is outside Lain's storage")
        if (!dir.exists()) return ToolResult.ok("Nothing there yet.")
        if (!dir.isDirectory) return ToolResult.ok("${dir.name} is a file (${dir.length()} bytes).")
        val entries = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        if (entries.isEmpty()) return ToolResult.ok("Empty folder.")
        return ToolResult.ok(
            entries.joinToString("\n") {
                if (it.isDirectory) "[dir]  ${it.name}" else "[file] ${it.name} (${it.length()} bytes)"
            }
        )
    }

    fun readFile(path: String): ToolResult {
        val file = resolve(path) ?: return ToolResult.fail(FailureKind.INVALID_INPUT, "Path is outside Lain's storage")
        if (!file.exists() || !file.isFile) return ToolResult.fail(FailureKind.INVALID_INPUT, "No file at $path")
        return try {
            if (file.length() > 200_000) {
                ToolResult.fail(FailureKind.INVALID_INPUT, "File is too large to read (${file.length()} bytes)")
            } else {
                ToolResult.ok(file.readText().take(20_000))
            }
        } catch (t: Throwable) {
            ToolResult.fail(FailureKind.TOOL_FAILURE, "Couldn't read $path", t.message)
        }
    }

    fun writeFile(path: String, content: String, append: Boolean = false): ToolResult {
        val file = resolve(path) ?: return ToolResult.fail(FailureKind.INVALID_INPUT, "Path is outside Lain's storage")
        return try {
            file.parentFile?.mkdirs()
            if (append) file.appendText(content) else file.writeText(content)
            ToolResult.ok("${if (append) "Appended to" else "Wrote"} ${file.name} (${file.length()} bytes).")
        } catch (t: Throwable) {
            ToolResult.fail(FailureKind.TOOL_FAILURE, "Couldn't write $path", t.message)
        }
    }

    fun renameFile(from: String, to: String): ToolResult {
        val src = resolve(from) ?: return ToolResult.fail(FailureKind.INVALID_INPUT, "Source is outside Lain's storage")
        val dst = resolve(to) ?: return ToolResult.fail(FailureKind.INVALID_INPUT, "Target is outside Lain's storage")
        if (!src.exists()) return ToolResult.fail(FailureKind.INVALID_INPUT, "No file at $from")
        return try {
            dst.parentFile?.mkdirs()
            if (src.renameTo(dst)) ToolResult.ok("Renamed to ${dst.name}.")
            else ToolResult.fail(FailureKind.TOOL_FAILURE, "Rename failed")
        } catch (t: Throwable) {
            ToolResult.fail(FailureKind.TOOL_FAILURE, "Rename failed", t.message)
        }
    }

    fun openContacts(): ToolResult = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                data = android.provider.ContactsContract.Contacts.CONTENT_URI
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        ToolResult.ok("Opened Contacts.")
    } catch (t: Throwable) {
        ToolResult.fail(FailureKind.APP_UNAVAILABLE, "Couldn't open Contacts", t.message)
    }
}
