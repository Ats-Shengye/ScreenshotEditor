package dev.screenshoteditor.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Owns the on-disk locations of screenshot images that live outside MediaStore.
 *
 * - `filesDir/temp/`            raw captures handed from CaptureActivity to EditorActivity
 * - `cacheDir/images/share/`    PNGs exposed to other apps through FileProvider for ACTION_SEND
 * - `cacheDir/images/clipboard/` the PNG behind the current clipboard URI (at most one file)
 *
 * Security Note: all of these may contain sensitive screen contents, so every directory has a
 * deletion policy; nothing is kept indefinitely.
 */
object TempCache {

    private const val TAG = "TempCache"
    private const val TEMP_DIR = "temp"
    private const val IMAGE_DIR = "images"
    private const val SHARE_SUBDIR = "share"
    private const val CLIPBOARD_SUBDIR = "clipboard"

    /**
     * Maximum age of raw captures and share files.
     *
     * Share files cannot be deleted right after the chooser returns because the receiving app may
     * read the URI lazily (e.g. a draft message). 24 hours covers that while bounding retention.
     */
    const val MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L

    /** Creates a new raw-capture file. Captures older than [MAX_AGE_MS] are deleted first. */
    @Throws(IOException::class)
    fun createTempFile(context: Context): File {
        val dir = ensureDir(getTempDir(context))
        deleteExpiredFiles(dir)
        return File(dir, "temp_${UUID.randomUUID()}.png")
    }

    fun getTempDir(context: Context): File = File(context.filesDir, TEMP_DIR)

    /**
     * Creates a new file for ACTION_SEND. Share files older than [MAX_AGE_MS], and files left
     * directly under `cacheDir/images/` by versions before the share/clipboard split, are deleted.
     */
    @Throws(IOException::class)
    fun createShareFile(context: Context): File {
        deleteExpiredFiles(imageRoot(context))
        val dir = ensureDir(File(imageRoot(context), SHARE_SUBDIR))
        deleteExpiredFiles(dir)
        return File(dir, "share_${UUID.randomUUID()}.png")
    }

    /** Creates a new file for the clipboard. See [deleteClipboardFilesExcept] for its lifetime. */
    @Throws(IOException::class)
    fun createClipboardFile(context: Context): File {
        val dir = ensureDir(clipboardDir(context))
        return File(dir, "clip_${UUID.randomUUID()}.png")
    }

    /**
     * Deletes every clipboard file except [keep].
     *
     * Only one clip is on the clipboard at a time, so once a new clip has been set the files of
     * earlier clips are no longer reachable through the clipboard and can be removed. Pass null to
     * delete all of them.
     */
    fun deleteClipboardFilesExcept(context: Context, keep: File?) {
        val keepPath = keep?.absolutePath
        clipboardDir(context).listFiles()?.forEach { file ->
            if (file.absolutePath != keepPath) deleteQuietly(file)
        }
    }

    /** Pure age check, separated for unit testing. */
    fun isExpired(lastModifiedMs: Long, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean =
        nowMs - lastModifiedMs > maxAgeMs

    /** Deletes [file]; a failure is logged (file name only) rather than thrown. */
    fun deleteQuietly(file: File) {
        if (!file.delete() && file.exists()) {
            Log.w(TAG, "deleteQuietly: could not delete ${file.name}")
        }
    }

    // --- private ---

    private fun imageRoot(context: Context): File = File(context.cacheDir, IMAGE_DIR)

    private fun clipboardDir(context: Context): File = File(imageRoot(context), CLIPBOARD_SUBDIR)

    private fun ensureDir(dir: File): File {
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Cannot create directory ${dir.name}")
        }
        return dir
    }

    /** Deletes expired regular files directly inside [dir]; subdirectories are left alone. */
    private fun deleteExpiredFiles(dir: File) {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (file.isFile && isExpired(file.lastModified(), now)) deleteQuietly(file)
        }
    }
}
