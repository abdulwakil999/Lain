package com.lain.assistant.automation

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.lain.assistant.MiniActivity
import com.lain.assistant.R

/**
 * Quick Settings tile — pull down the shade, tap it, Lain opens listening.
 *
 * It used to toggle the wake-word service, and that was the wrong job for a tile.
 * Starting a microphone foreground service is refused outright from the shade on
 * Android 12+, and even when it succeeded the tile could only report "listening"
 * — an invisible state change with no feedback, which is indistinguishable from a
 * tile that does nothing. Hence "the toggle doesn't work".
 *
 * Launching an activity from a tile is explicitly supported and always allowed, so
 * this now does exactly what a widget tap does: opens the mini surface with the
 * mic already live. One tap, visible result, same behaviour everywhere.
 */
class VoiceInputTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        // unlockAndRun because a tile is reachable from the lock screen, where an
        // activity start would otherwise be dropped on the floor.
        unlockAndRun { openLain() }
    }

    private fun openLain() {
        val intent = Intent(this, MiniActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MiniActivity.EXTRA_AUTO_LISTEN, true)
            // Distinct data so a repeat tap is not folded into the previous
            // PendingIntent, which compares by everything except extras.
            data = android.net.Uri.parse("lain://tile/${System.currentTimeMillis()}")
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // The Intent overload was removed in Android 14 and throws
                // UnsupportedOperationException; only the PendingIntent form works.
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }.onFailure {
            // Shade refused to collapse for us; the activity start still stands on
            // its own, and a tile that opens the app late beats one that does nothing.
            runCatching { startActivity(intent) }
        }
    }

    /**
     * Always available and never "on".
     *
     * A tile that opens something is an action, not a switch, so it stays INACTIVE
     * rather than pretending to hold a state it doesn't have.
     */
    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = "Lain"
        tile.icon = runCatching { Icon.createWithResource(this, R.drawable.ic_lain_glyph) }.getOrNull()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = "Tap to talk"
        }
        runCatching { tile.updateTile() }
    }
}
