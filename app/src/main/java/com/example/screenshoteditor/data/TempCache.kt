package com.example.screenshoteditor.data

import android.content.Context
import java.io.File
import java.util.UUID

object TempCache {
    
    private const val TEMP_DIR = "temp"
    private const val IMAGE_DIR = "images"
    
    fun createTempFile(context: Context): File {
        val tempDir = getTempDir(context)
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        
        // Clean old temp files
        cleanOldFiles(tempDir)
        
        return File(tempDir, "temp_${UUID.randomUUID()}.png")
    }
    
    fun getCacheFile(context: Context): File {
        val cacheDir = getCacheDir(context)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        
        return File(cacheDir, "cache_${UUID.randomUUID()}.png")
    }
    
    fun getTempDir(context: Context): File {
        return File(context.filesDir, TEMP_DIR)
    }
    
    fun getCacheDir(context: Context): File {
        return File(context.cacheDir, IMAGE_DIR)
    }
    
    fun cleanTempFiles(context: Context) {
        val tempDir = getTempDir(context)
        if (tempDir.exists()) {
            tempDir.listFiles()?.forEach { file ->
                file.delete()
            }
        }
    }
    
    fun cleanCacheFiles(context: Context) {
        val cacheDir = getCacheDir(context)
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { file ->
                file.delete()
            }
        }
    }
    
    private fun cleanOldFiles(directory: File) {
        val currentTime = System.currentTimeMillis()
        val maxAge = 24 * 60 * 60 * 1000L // 24 hours
        
        directory.listFiles()?.forEach { file ->
            if (currentTime - file.lastModified() > maxAge) {
                file.delete()
            }
        }
    }
}
