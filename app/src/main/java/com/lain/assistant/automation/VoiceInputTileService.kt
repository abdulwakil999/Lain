package com.lain.assistant.automation

import android.app.KeyguardManager
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
 * It used to toggle background listening, and that was the wrong job for a tile.
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

    /**
     * Starts the activity immediately, and only defers when the phone is locked.
     *
     * The permission to launch an activity from the shade is granted by the tap and
     * does not last: it is attached to this click, and anything that returns to the
     * looper before using it can find the grant gone. `unlockAndRun` does exactly
     * that — it posts the block for later — so wrapping every click in it spent the
     * grant on the common case, where the phone was not locked and there was nothing
     * to unlock. That is the intermittent nothing-happens: same tap, same code, and
     * whether it worked depended on timing the user cannot see or influence.
     *
     * Locked is the only case that genuinely needs deferring, so it is the only case
     * that gets it.
     */
    override fun onClick() {
        super.onClick()
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            unlockAndRun { openLain() }
        } else {
            openLain()
        }
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
        val collapsed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // The Intent overload was removed in Android 14 and throws
                // UnsupportedOperationException; only the PendingIntent form works.
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }.isSuccess

        if (collapsed) return

        // The fallback used to be startActivity(intent), which cannot work: this is a
        // Service, and a background activity start from one is refused outright on
        // Android 10 and up. It threw nothing and did nothing, so a failed tile looked
        // identical to a tile nobody pressed.
        //
        // Sending the PendingIntent is the one route left that carries the app's own
        // launch privilege rather than a Service's.
        val sent = runCatching { pending.send() }.isSuccess
        if (sent) return

        // Both routes are gone. Say so on the tile itself, because the alternative is
        // a control that silently does nothing and a user who concludes the app is
        // broken — which, from where they are standing, it is.
        runCatching {
            qsTile?.let { tile ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Couldn't open — tap the app"
                }
                tile.updateTile()
            }
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
