package com.lain.assistant.automation

import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.lain.assistant.tools.FailureKind
import com.lain.assistant.tools.ToolResult
import kotlinx.coroutines.delay

/**
 * Wi-Fi, Bluetooth, mobile data, hotspot, location — actually switched.
 *
 * Android removed programmatic control of these from ordinary apps (Wi-Fi in 10,
 * Bluetooth in 13) and no API brings it back. What it did not remove is the user's
 * own Accessibility Service pressing the user's own Quick Settings tile — which is
 * exactly what a screen reader does all day, and exactly what the user is asking for
 * when they say "turn Wi-Fi off". Nothing here is a workaround for a permission: the
 * service is one the user switched on, it acts on the visible UI, and the user can
 * watch it happen.
 *
 * The half that makes it trustworthy is the verification. Every toggle here can be
 * *read* through a normal API even though it can't be written, so after the tap the
 * new state is read back off the system. A tap that hit the wrong tile is caught and
 * reported rather than announced as success — which is the whole difference between
 * this and pretending.
 */
class SystemToggles(private val context: Context) {

    companion object {
        /** How long to let the shade animate before looking for tiles. */
        private const val SHADE_SETTLE_MS = 900L

        /** How long to wait for a radio to actually change state after the tap. */
        private const val STATE_SETTLE_MS = 2_500L
        private const val STATE_POLL_MS = 250L
    }

    /** What Lain can switch this way, with the words people use for each. */
    enum class Toggle(val label: String, val tileNames: List<String>) {
        WIFI("Wi-Fi", listOf("Wi-Fi", "WiFi", "Wifi", "Internet", "Wireless")),
        BLUETOOTH("Bluetooth", listOf("Bluetooth")),
        MOBILE_DATA("Mobile data", listOf("Mobile data", "Data", "Cellular data", "Mobile network")),
        HOTSPOT("Hotspot", listOf("Hotspot", "Mobile hotspot", "Portable hotspot", "Tethering")),
        LOCATION("Location", listOf("Location", "GPS")),
        AIRPLANE("Aeroplane mode", listOf("Airplane mode", "Aeroplane mode", "Flight mode")),
        TORCH("Torch", listOf("Flashlight", "Torch")),
        ROTATION("Auto-rotate", listOf("Auto-rotate", "Auto rotate", "Rotation"));

        companion object {
            /** Matches what the user said to a toggle, case- and spacing-insensitively. */
            fun from(spoken: String): Toggle? {
                val t = spoken.lowercase().replace("-", " ").replace("_", " ").trim()
                return when {
                    t.contains("wifi") || t.contains("wi fi") || t.contains("wireless") -> WIFI
                    t.contains("bluetooth") -> BLUETOOTH
                    t.contains("hotspot") || t.contains("tether") -> HOTSPOT
                    t.contains("mobile data") || t.contains("cellular") || t.contains("data") -> MOBILE_DATA
                    t.contains("location") || t.contains("gps") -> LOCATION
                    t.contains("airplane") || t.contains("aeroplane") || t.contains("flight") -> AIRPLANE
                    t.contains("torch") || t.contains("flashlight") -> TORCH
                    t.contains("rotate") || t.contains("rotation") -> ROTATION
                    else -> null
                }
            }
        }
    }

    /**
     * Reads a toggle's real state.
     *
     * Null where the platform gives no readable answer — hotspot and auto-rotate on
     * some builds. A null here means "acted, can't confirm", never "worked".
     */
    fun stateOf(toggle: Toggle): Boolean? = runCatching {
        when (toggle) {
            Toggle.WIFI ->
                context.getSystemService(WifiManager::class.java)?.isWifiEnabled

            Toggle.BLUETOOTH ->
                context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled

            Toggle.MOBILE_DATA -> {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }

            Toggle.LOCATION ->
                context.getSystemService(LocationManager::class.java)?.let { lm ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
                    else lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                }

            Toggle.AIRPLANE ->
                Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

            Toggle.ROTATION ->
                Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

            // No readable API for the tethering state without a system permission.
            Toggle.HOTSPOT -> null

            Toggle.TORCH -> null
        }
    }.getOrNull()

    /**
     * Switches [toggle] to [on], by pressing the user's own Quick Settings tile.
     *
     * Returns early and does nothing when it is already in the requested state —
     * blindly tapping would switch it the wrong way, which is worse than a no-op.
     */
    suspend fun set(toggle: Toggle, on: Boolean): ToolResult {
        val service = LainAccessibilityService.instance
            ?: return ToolResult.fail(
                FailureKind.PERMISSION,
                "Switching ${toggle.label} needs the Accessibility Service — Android stopped letting apps " +
                    "change it directly, so Lain does it by pressing the tile in your Quick Settings. " +
                    "Turn the service on in Settings and ask again. Nothing has changed."
            )

        val before = stateOf(toggle)
        if (before == on) {
            return ToolResult.ok("${toggle.label} is already ${if (on) "on" else "off"}.")
        }

        service.openQuickSettings()
        delay(SHADE_SETTLE_MS)

        val tapped = toggle.tileNames.any { service.tapByText(it) }
        if (!tapped) {
            service.goBack()
            return ToolResult.fail(
                FailureKind.TOOL_FAILURE,
                "Opened Quick Settings but couldn't find a ${toggle.label} tile. Some phones hide tiles on a " +
                    "second page or rename them — swipe to it and tap it, or tell Lain what it's called."
            )
        }

        // Radios take a moment. Poll rather than sleep so a fast one costs nothing.
        val after = awaitState(toggle, expected = on)
        service.goBack()

        return when {
            after == on ->
                ToolResult.ok("${toggle.label} is ${if (on) "on" else "off"}.")

            // Readable, and it didn't change: the tap landed on the wrong tile, or the
            // system refused. Either way it did not work, and saying so is the point.
            after != null -> ToolResult.fail(
                FailureKind.TOOL_FAILURE,
                "Tapped the ${toggle.label} tile but the phone still reports it " +
                    "${if (after) "on" else "off"}. It didn't take."
            )

            // Nothing readable to check against. Honest about that rather than guessing.
            else -> ToolResult.ok(
                "Tapped the ${toggle.label} tile. Android gives no way to read that one back, so check " +
                    "it's ${if (on) "on" else "off"} yourself."
            )
        }
    }

    private suspend fun awaitState(toggle: Toggle, expected: Boolean): Boolean? {
        val deadline = android.os.SystemClock.elapsedRealtime() + STATE_SETTLE_MS
        var last: Boolean? = stateOf(toggle)
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            last = stateOf(toggle)
            if (last == expected || last == null) {
                if (last == expected) return last
            }
            delay(STATE_POLL_MS)
        }
        return last
    }

    /** One line covering everything readable, for "what's on right now". */
    fun summary(): String = Toggle.entries
        .mapNotNull { toggle -> stateOf(toggle)?.let { "${toggle.label} ${if (it) "on" else "off"}" } }
        .joinToString(", ")
        .ifBlank { "Nothing readable." }
}
