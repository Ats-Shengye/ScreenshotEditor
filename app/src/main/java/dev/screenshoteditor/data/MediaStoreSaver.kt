package dev.screenshoteditor.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaStoreSaver(context: Context) {

    private companion object {
        const val TAG = "MediaStoreSaver"
        val DIRECTORY_SCREENSHOTS = "${Environment.DIRECTORY_PICTURES}/Screenshots"
        const val MIME_TYPE_PNG = "image/png"
        const val DATE_FORMAT = "yyyy-MM-dd_HH-mm-ss_SSS"
    }

    private val appContext = context.applicationContext

    /**
     * Saves [bitmap] to Pictures/Screenshots as PNG.
     *
     * The entry is inserted with IS_PENDING=1 and published (IS_PENDING=0) only after the stream
     * has been closed, so other apps never see a partially written file. On any failure the pending
     * entry is deleted.
     */
    suspend fun saveBitmapToGallery(bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val pendingValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, generateFilename())
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE_PNG)
            put(MediaStore.Images.Media.RELATIVE_PATH, DIRECTORY_SCREENSHOTS)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pendingValues)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "saveBitmapToGallery: insert rejected", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "saveBitmapToGallery: insert not permitted", e)
            null
        } ?: return@withContext false

        val published = try {
            val written = resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            } ?: false
            written && publish(uri)
        } catch (e: IOException) {
            Log.w(TAG, "saveBitmapToGallery: write failed", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "saveBitmapToGallery: write not permitted", e)
            false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "saveBitmapToGallery: provider state error", e)
            false
        }

        if (!published) deletePending(uri)
        published
    }

    private fun publish(uri: Uri): Boolean {
        val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        return appContext.contentResolver.update(uri, values, null, null) > 0
    }

    private fun deletePending(uri: Uri) {
        try {
            appContext.contentResolver.delete(uri, null, null)
        } catch (e: SecurityException) {
            Log.w(TAG, "deletePending failed", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "deletePending failed", e)
        }
    }

    private fun generateFilename(): String {
        val timestamp = SimpleDateFormat(DATE_FORMAT, Locale.US).format(Date())
        return "Screenshot_$timestamp.png"
    }
}
