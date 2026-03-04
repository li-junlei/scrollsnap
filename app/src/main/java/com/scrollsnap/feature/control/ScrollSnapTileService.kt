package com.scrollsnap.feature.control

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class ScrollSnapTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.state = if (OverlayControlService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val action = if (OverlayControlService.isRunning) {
            OverlayControlService.ACTION_CAPTURE_NOW
        } else {
            OverlayControlService.ACTION_START_OVERLAY
        }
        val intent = Intent(this, OverlayControlService::class.java).setAction(action)
        ContextCompat.startForegroundService(this, intent)

        qsTile?.state = Tile.STATE_ACTIVE
        qsTile?.updateTile()
    }
}
