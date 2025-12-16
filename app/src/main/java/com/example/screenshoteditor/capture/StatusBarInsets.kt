package com.example.screenshoteditor.capture

import android.content.Context
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat

object StatusBarInsets {
    
    fun getStatusBarHeight(context: Context): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = windowManager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars()
                )
                insets.top
            } else {
                // Fallback for older Android versions
                getStatusBarHeightFromResource(context)
            }
        } catch (e: Exception) {
            getStatusBarHeightFromResource(context)
        }
    }
    
    private fun getStatusBarHeightFromResource(context: Context): Int {
        val resourceId = context.resources.getIdentifier(
            "status_bar_height",
            "dimen",
            "android"
        )
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }
}
