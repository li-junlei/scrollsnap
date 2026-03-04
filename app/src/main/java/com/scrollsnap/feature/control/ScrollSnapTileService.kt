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
        if (OverlayControlService.isRunning) {
            // 如果悬浮球正在运行，关闭它
            val intent = Intent(this, OverlayControlService::class.java).setAction(OverlayControlService.ACTION_STOP_OVERLAY)
            startService(intent)
            qsTile?.state = Tile.STATE_INACTIVE
        } else {
            // 如果悬浮球未运行，启动它
            val intent = Intent(this, OverlayControlService::class.java).setAction(OverlayControlService.ACTION_START_OVERLAY)
            ContextCompat.startForegroundService(this, intent)
            qsTile?.state = Tile.STATE_ACTIVE
        }
        qsTile?.updateTile()
    }
}
