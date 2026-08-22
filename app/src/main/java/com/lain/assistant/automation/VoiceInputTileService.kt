package com.lain.assistant.automation

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.lain.assistant.MainActivity
import com.lain.assistant.R

/**
 * Quick Settings tile — pull down the shade, tap it, "Hello Lain" listening
 * starts or stops.
 *
 * The previous version assumed the toggle could always be honoured and told the
 * tile so before finding out. Three separate things make that false, and each one
 * left the tile lit while nothing was listening:
 *
 *  - Microphone permission may not be granted. A foreground service declared
 *    `microphone` throws SecurityException at startForeground without it, so the
 *    service dies the instant it starts.
 *  - From Android 12 a background foreground-service start can be refused
 *    outright, and the exception surfaces at the call site.
 *  - Speech recognition may not be available on the device at all.
 *
 * So the tile now checks first, does the work inside a catch, and reports the
 * state it actually ended up in. Where it can't act, it sends the user somewhere
 * that can fix it rather than lying about having done it.
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

        if (!recognitionAvailable()) {
            setTileState(active = false, available = false)
            return
        }

        // Without the mic permission the service cannot start at all, and there is
        // no way to ask for a runtime permission from a tile. Open the app, which
        // asks on launch — and unlockAndRun because a tile is reachable from the
        // lock screen, where starting an activity would otherwise be dropped.
        if (!hasMicPermission()) {
            unlockAndRun { openApp() }
            return
        }

        val turningOn = !WakeWordService.isRunning
        val worked = runCatching {
            if (turningOn) WakeWordService.start(this) else WakeWordService.stop(this)
        }.isSuccess

        // Reflect what happened, not what was asked for. Starting is asynchronous —
        // isRunning flips a beat after the call returns — so on success the intent is
        // the best available truth; on failure it definitely isn't.
        setTileState(active = worked && turningOn, available = true)
    }

    private fun refresh() =
        setTileState(active = WakeWordService.isRunning, available = recognitionAvailable())

    private fun hasMicPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun recognitionAvailable() =
        runCatching { SpeechRecognizer.isRecognitionAvailable(this) }.getOrDefault(false)

    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The Intent overload was removed in Android 14 and throws
            // UnsupportedOperationException; only the PendingIntent form works.
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun setTileState(active: Boolean, available: Boolean) {
        val tile = qsTile ?: return
        tile.state = when {
            !available -> Tile.STATE_UNAVAILABLE
            active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "Lain"
        tile.icon = runCatching { Icon.createWithResource(this, R.drawable.ic_lain_glyph) }.getOrNull()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !available -> "No speech recognition"
                active -> "Listening for \"Hello Lain\""
                else -> "Tap to listen"
            }
        }
        runCatching { tile.updateTile() }
    }
}
