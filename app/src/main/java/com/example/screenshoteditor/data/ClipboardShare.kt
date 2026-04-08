package com.example.screenshoteditor.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ClipboardShare(private val context: Context) {
    
    companion object {
        private const val TAG = "ClipboardShare"
        private const val CLIPBOARD_LABEL = "Screenshot"
    }
    
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val handler = Handler(Looper.getMainLooper())
    private var clearRunnable: Runnable? = null
    
    suspend fun copyImageToClipboard(bitmap: Bitmap, cacheFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            // Save bitmap to cache file
            val success = saveBitmapToFile(bitmap, cacheFile)
            if (!success) return@withContext false
            
            // Create URI for FileProvider (authority derived from packageName)
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, cacheFile)
            
            // Create ClipData with URI
            val clip = ClipData.newUri(context.contentResolver, CLIPBOARD_LABEL, uri)
            
            withContext(Dispatchers.Main) {
                clipboardManager.setPrimaryClip(clip)
            }
            
            // ClipData.newUri()でURIを設定することでシステムがURI権限を管理する
            // 特定パッケージへのgrantUriPermissionハードコードは不要

            true
        } catch (e: Exception) {
            Log.w(TAG, "copyImageToClipboard failed", e)
            false
        }
    }
    
    fun scheduleClear(delaySeconds: Int) {
        clearRunnable?.let { handler.removeCallbacks(it) }
        
        clearRunnable = Runnable {
            try {
                val emptyClip = ClipData.newPlainText("", "")
                clipboardManager.setPrimaryClip(emptyClip)
            } catch (e: Exception) {
                Log.w(TAG, "scheduleClear: clipboard clear failed", e)
            }
        }
        
        handler.postDelayed(clearRunnable!!, delaySeconds * 1000L)
    }
    
    fun clearClipboardNow() {
        clearRunnable?.let { 
            handler.removeCallbacks(it)
            clearRunnable = null
        }
        
        try {
            val emptyClip = ClipData.newPlainText("", "")
            clipboardManager.setPrimaryClip(emptyClip)
        } catch (e: Exception) {
            Log.w(TAG, "clearClipboardNow failed", e)
        }
    }
    
    suspend fun shareImage(bitmap: Bitmap, shareFile: File) = withContext(Dispatchers.IO) {
        try {
            val success = saveBitmapToFile(bitmap, shareFile)
            if (!success) return@withContext

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, shareFile)

            withContext(Dispatchers.Main) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    // FLAG_GRANT_READ_URI_PERMISSIONをIntentに設定し、
                    // システムのURI権限管理に委ねる（手動revokeは行わない）
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(shareIntent, "スクリーンショットを共有")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }

        } catch (e: Exception) {
            Log.w(TAG, "shareImage failed", e)
        }
    }
    
    private fun saveBitmapToFile(bitmap: Bitmap, file: File): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val outputStream = FileOutputStream(file)
            outputStream.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "saveBitmapToFile failed: ${file.name}", e)
            false
        }
    }
}
