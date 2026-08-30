package com.lain.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolResult

/**
 * Screen brightness, which Android guards behind a grant of its own.
 *
 * `Settings.System.SCREEN_BRIGHTNESS` is writable only with WRITE_SETTINGS, and
 * WRITE_SETTINGS is not a runtime permission — no dialog, no `requestPermissions`.
 * The user has to walk into a settings screen and turn it on for the app, and
 * nothing an app does can shortcut that. That is the platform working as intended,
 * so this offers the screen and says plainly what is needed rather than failing
 * quietly or pretending the brightness changed.
 *
 * Auto-brightness is switched off before a manual value is written. Leaving it on
 * means the system overrides the new value within a second or two, and the user
 * watches the screen set itself back — an action that reports success and then
 * visibly undoes itself is worse than one that refuses.
 */
class ScreenBrightness(private val context: Context) {

    private companion object {
        /** Android's scale for the brightness value, regardless of what the slider shows. */
        const val MAX = 255

        /**
         * Never fully dark.
         *
         * Zero is a black screen on many devices, and a user who asked for "dim" and
         * got an unreadable phone has no way to undo it except by feel.
         */
        const val MIN_PERCENT = 5
    }

    fun canWrite(): Boolean = Settings.System.canWrite(context)

    /** @param percent 0-100, clamped so the screen never goes fully dark. */
    fun set(percent: Int): ToolResult {
        if (!canWrite()) {
            openGrantScreen()
            return ToolResult.fail(
                FailureKind.PERMISSION,
                "Changing brightness needs the \"Modify system settings\" permission, which Android " +
                    "only grants from its own screen — I've opened it. Turn it on for Lain and ask again."
            )
        }

        val wanted = percent.coerceIn(MIN_PERCENT, 100)
        return runCatching {
            // Or the system puts it straight back.
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                wanted * MAX / 100
            )
            // Read back rather than assume: this is a content-provider write, and a
            // manufacturer that ignores it fails silently otherwise.
            val actual = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            if (actual < 0) {
                ToolResult.fail(FailureKind.TOOL_FAILURE, "Wrote the brightness but couldn't read it back.")
            } else {
                ToolResult.ok("Brightness at ${actual * 100 / MAX}%.")
            }
        }.getOrElse {
            ToolResult.fail(FailureKind.TOOL_FAILURE, "Couldn't change the brightness: ${it.message}")
        }
    }

    /** Current brightness as a percentage, or null when it can't be read. */
    fun current(): Int? = runCatching {
        val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        raw * 100 / MAX
    }.getOrNull()

    /** Hands the screen back to Android's light sensor. */
    fun setAutomatic(): ToolResult {
        if (!canWrite()) {
            openGrantScreen()
            return ToolResult.fail(
                FailureKind.PERMISSION,
                "That needs the \"Modify system settings\" permission — I've opened the screen for it."
            )
        }
        return runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            )
            ToolResult.ok("Brightness back on automatic.")
        }.getOrElse {
            ToolResult.fail(FailureKind.TOOL_FAILURE, "Couldn't switch brightness to automatic: ${it.message}")
        }
    }

    private fun openGrantScreen() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
