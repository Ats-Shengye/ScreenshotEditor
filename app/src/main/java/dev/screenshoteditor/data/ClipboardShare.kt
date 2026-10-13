package dev.screenshoteditor.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import dev.screenshoteditor.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Hands a cropped screenshot to the clipboard or to the system share sheet via FileProvider URIs.
 *
 * Only the application context is retained, so an instance may outlive the Activity that created it
 * (the clipboard auto-clear timer runs after EditorActivity has finished).
 */
class ClipboardShare(context: Context) {

    private companion object {
        const val TAG = "ClipboardShare"
        const val CLIPBOARD_LABEL = "Screenshot"
    }

    private val appContext = context.applicationContext
    private val authority = "${appContext.packageName}.fileprovider"

    /**
     * Writes [bitmap] to a clipboard file, puts its content URI on the clipboard and schedules the
     * auto-clear. Must be called from the main thread (the clipboard and the timer are main-thread
     * confined); the PNG encoding runs on [Dispatchers.IO].
     *
     * @param clearAfterSeconds seconds until the clip is cleared; 0 disables the auto-clear.
     * @return true when the clip was set.
     */
    suspend fun copyImageToClipboard(bitmap: Bitmap, clearAfterSeconds: Int): Boolean {
        val file = writeToFile(bitmap) { TempCache.createClipboardFile(appContext) } ?: return false
        val uri = uriFor(file) ?: run {
            TempCache.deleteQuietly(file)
            return false
        }
        return try {
            // ClipData.newUri lets the clipboard service grant read access to whichever app pastes;
            // no explicit grantUriPermission to a hard-coded package is needed.
            val clip = ClipData.newUri(appContext.contentResolver, CLIPBOARD_LABEL, uri)
            val clipboardManager = appContext.getSystemService(ClipboardManager::class.java)
                ?: throw IllegalStateException("ClipboardManager unavailable")
            clipboardManager.setPrimaryClip(clip)
            ClipboardAutoClear.onClipSet(appContext, file, clearAfterSeconds)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "copyImageToClipboard: clipboard unavailable", e)
            TempCache.deleteQuietly(file)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "copyImageToClipboard: clipboard write rejected", e)
            TempCache.deleteQuietly(file)
            false
        }
    }

    /**
     * Writes [bitmap] to a share file and returns a chooser Intent for it, or null on failure.
     *
     * The caller starts the Intent from its Activity context, which keeps the chooser in the
     * caller's task instead of requiring FLAG_ACTIVITY_NEW_TASK from the application context.
     * Read permission is granted through FLAG_GRANT_READ_URI_PERMISSION and ends with the receiving
     * task; share files themselves are deleted by age (see [TempCache.createShareFile]).
     */
    suspend fun createShareIntent(bitmap: Bitmap): Intent? {
        val file = writeToFile(bitmap) { TempCache.createShareFile(appContext) } ?: return null
        val uri = uriFor(file) ?: run {
            TempCache.deleteQuietly(file)
            return null
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData mirrors EXTRA_STREAM so that the grant also covers the chooser's preview.
            clipData = ClipData.newRawUri(CLIPBOARD_LABEL, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, appContext.getString(R.string.share_chooser_title))
    }

    // --- private ---

    private suspend fun writeToFile(bitmap: Bitmap, createFile: () -> File): File? =
        withContext(Dispatchers.IO) {
            var file: File? = null
            try {
                val target = createFile()
                file = target
                val written = FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!written) throw IOException("PNG encoding failed")
                target
            } catch (e: IOException) {
                Log.w(TAG, "writeToFile failed", e)
                file?.let { TempCache.deleteQuietly(it) }
                null
            } catch (e: SecurityException) {
                Log.w(TAG, "writeToFile: permission denied", e)
                file?.let { TempCache.deleteQuietly(it) }
                null
            }
        }

    private fun uriFor(file: File): Uri? = try {
        FileProvider.getUriForFile(appContext, authority, file)
    } catch (e: IllegalArgumentException) {
        // Thrown when the file is outside the paths declared in res/xml/file_paths.xml.
        Log.w(TAG, "uriFor: file not covered by FileProvider", e)
        null
    }
}
