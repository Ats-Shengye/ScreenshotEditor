package dev.screenshoteditor.data

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import java.io.File

/**
 * Process-wide timer that clears the screenshot this app put on the clipboard.
 *
 * Design Decision: the timer is owned by a process singleton holding only the application
 * context. EditorActivity finishes right after copying, so a timer tied to the Activity would
 * either never fire or keep the destroyed Activity alive until it did.
 *
 * Known limitations (cannot be solved without extra permissions or dependencies):
 * - Ownership cannot be verified at clear time. Since Android 10 an app without input focus can
 *   neither read the clipboard (getPrimaryClip / getPrimaryClipDescription return null) nor receive
 *   OnPrimaryClipChangedListener callbacks, and by the time the timer fires EditorActivity is gone.
 *   If the user copies something else in another app before the timer fires, that content is
 *   cleared as well. The settings screen states this, and "never clear" disables the timer.
 * - The timer lives in memory. If the process is killed before it fires (CaptureService normally
 *   keeps the process in the foreground state, so this is unlikely) the clip is not cleared; its
 *   file is removed at the next copy by [TempCache.deleteClipboardFilesExcept].
 *
 * Thread Safety: all members are confined to the main thread.
 */
object ClipboardAutoClear {

    private const val TAG = "ClipboardAutoClear"

    /** Upper bound for the delay; larger values are clamped (defence against corrupted settings). */
    private const val MAX_DELAY_SECONDS = 3600

    private val handler = Handler(Looper.getMainLooper())
    private var pendingClear: Runnable? = null

    /**
     * Called right after [clipFile] has been placed on the clipboard.
     *
     * Cancels any earlier timer, removes files of earlier clips, and schedules a clear after
     * [clearAfterSeconds]. A value of 0 or less means "never clear".
     */
    @MainThread
    fun onClipSet(context: Context, clipFile: File, clearAfterSeconds: Int) {
        val appContext = context.applicationContext
        cancelPending()
        TempCache.deleteClipboardFilesExcept(appContext, clipFile)
        if (clearAfterSeconds <= 0) return

        val delayMs = clearAfterSeconds.coerceAtMost(MAX_DELAY_SECONDS) * 1000L
        val task = Runnable {
            pendingClear = null
            clearNow(appContext, clipFile)
        }
        pendingClear = task
        handler.postDelayed(task, delayMs)
    }

    @MainThread
    private fun cancelPending() {
        pendingClear?.let { handler.removeCallbacks(it) }
        pendingClear = null
    }

    @MainThread
    private fun clearNow(appContext: Context, clipFile: File) {
        val clipboardManager = appContext.getSystemService(ClipboardManager::class.java)
        try {
            // clearPrimaryClip (API 28+) removes the clip itself, unlike setting an empty clip, and
            // lets the system revoke the URI grant it holds for our FileProvider entry.
            clipboardManager?.clearPrimaryClip()
        } catch (e: SecurityException) {
            Log.w(TAG, "clearNow: clipboard clear rejected", e)
        }
        // The file is deleted even if clearing failed, so the screen contents do not linger on disk.
        TempCache.deleteQuietly(clipFile)
    }
}
