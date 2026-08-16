package com.lain.assistant.automation

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile — pull down the shade, tap it, "Hello Lain" listening starts/stops. */
class VoiceInputTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        // Starting a foreground service is async — its onCreate (which flips isRunning)
        // lands a beat after this call returns, so drive the tile off the action just
        // taken rather than re-reading isRunning immediately.
        val turningOn = !WakeWordService.isRunning
        if (turningOn) WakeWordService.start(this) else WakeWordService.stop(this)
        setTileState(turningOn)
    }

    private fun refresh() = setTileState(WakeWordService.isRunning)

    private fun setTileState(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) "Lain: Listening" else "Lain: Hello Lain"
        tile.updateTile()
    }
}
