package com.example.screenshoteditor.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class MediaStoreSaver(private val context: Context) {
    
    companion object {
        private val DIRECTORY_SCREENSHOTS = "${Environment.DIRECTORY_PICTURES}/Screenshots"
        private const val MIME_TYPE_PNG = "image/png"
        private const val DATE_FORMAT = "yyyy-MM-dd_HH-mm-ss_SSS"
    }
    
    suspend fun saveBitmapToGallery(bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        try {
            val filename = generateFilename()
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE_PNG)
                put(MediaStore.Images.Media.RELATIVE_PATH, DIRECTORY_SCREENSHOTS)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            
            if (uri != null) {
                var outputStream: OutputStream? = null
                try {
                    outputStream = resolver.openOutputStream(uri)
                    if (outputStream != null) {
                        val success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                        outputStream.flush()
                        
                        if (success) {
                            // Mark as complete
                            contentValues.clear()
                            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(uri, contentValues, null, null)
                            return@withContext true
                        }
                    }
                } catch (e: Exception) {
                } finally {
                    outputStream?.close()
                }
                
                // If we reach here, something went wrong, clean up
                resolver.delete(uri, null, null)
            }

            false
        } catch (e: Exception) {
            false
        }
    }
    
    private fun generateFilename(): String {
        val formatter = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())
        val timestamp = formatter.format(Date())
        return "Screenshot_$timestamp.png"
    }
}