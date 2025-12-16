package com.example.screenshoteditor.capture

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager

class ProjectionController(
    private val context: Context,
    private val resultCode: Int,
    private val data: Intent
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private val mediaProjectionManager = context.getSystemService(
        Context.MEDIA_PROJECTION_SERVICE
    ) as MediaProjectionManager
    
    fun start(
        width: Int,
        height: Int,
        density: Int,
        imageReader: ImageReader
    ): VirtualDisplay? {
        this.imageReader = imageReader
        
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )
        
        return virtualDisplay
    }
    
    fun stop() {
        imageReader?.close()
        virtualDisplay?.release()
        mediaProjection?.stop()
        
        imageReader = null
        virtualDisplay = null
        mediaProjection = null
    }
}
