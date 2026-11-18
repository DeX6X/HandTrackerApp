package com.example.handtrackerapp

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class HandControlTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        if (!HandControlAccessibilityService.isServiceRunning.value) {
            // หากยังไม่เปิด Accessibility ให้เปลี่ยนสถานะเป็น Unavailable
            qsTile?.state = Tile.STATE_UNAVAILABLE
            qsTile?.updateTile()
            return
        }

        val nextState = !HandControlAccessibilityService.isTrackingEnabled.value
        HandControlAccessibilityService.setTrackingEnabled(nextState)
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isServiceReady = HandControlAccessibilityService.isServiceRunning.value
        val isTracking = HandControlAccessibilityService.isTrackingEnabled.value

        tile.state = when {
            !isServiceReady -> Tile.STATE_UNAVAILABLE
            isTracking -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "Air Hand"
        tile.updateTile()
    }
}