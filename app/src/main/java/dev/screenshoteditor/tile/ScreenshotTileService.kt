package dev.screenshoteditor.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import dev.screenshoteditor.capture.CaptureActivity
import dev.screenshoteditor.capture.CaptureService

class ScreenshotTileService : TileService() {

    companion object {
        private const val TAG = "ScreenshotTileService"
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "onClick: launching CaptureActivity")

        try {
            val intent = Intent(this, CaptureActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "onClick: failed to launch CaptureActivity", e)
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
