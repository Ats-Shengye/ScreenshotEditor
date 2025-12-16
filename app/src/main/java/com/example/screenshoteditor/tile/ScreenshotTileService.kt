package com.example.screenshoteditor.tile

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.screenshoteditor.capture.CaptureService

class ScreenshotTileService : TileService() {
    
    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }
    
    override fun onClick() {
        super.onClick()
        
        // タイルをクリックしたらCaptureServiceに撮影指示を送信
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_CAPTURE
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    
    private fun updateTileState() {
        qsTile?.let { tile ->
            tile.state = if (CaptureService.isRunning) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            tile.label = "スクリーンショット"
            tile.contentDescription = "スクリーンショットを撮影"
            tile.updateTile()
        }
    }
}
