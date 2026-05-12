package com.standroid.launcher

import android.app.Application
import android.content.ComponentCallbacks2
import com.standroid.launcher.util.AppLogger
import com.standroid.launcher.util.AppPrefs

/**
 * Application subclass — initialises singletons that need a Context.
 * Declared in AndroidManifest via android:name=".STApp"
 */
class STApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        AppLogger.init(this)
        AppLogger.i(TAG, "STANDROID ${BuildConfig.VERSION_NAME} starting")
    }

    /**
     * Called by the system when memory is low.
     * We respond to UI_HIDDEN and above by suggesting a GC pass.
     * We do NOT kill the Node.js process here — that would stop the server.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val levelName = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN          -> "UI_HIDDEN"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE   -> "RUNNING_MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW        -> "RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL   -> "RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND         -> "BACKGROUND"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE           -> "MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE           -> "COMPLETE"
            else                                               -> "UNKNOWN($level)"
        }
        AppLogger.d(TAG, "onTrimMemory: $levelName")

        // When the app's UI is no longer visible, suggest a GC pass to free
        // any memory held by the UI layer (bitmaps, view caches, etc.)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            AppLogger.i(TAG, "App went to background — requesting GC")
            System.gc()
        }
    }

    companion object {
        private const val TAG = "STApp"
    }
}
