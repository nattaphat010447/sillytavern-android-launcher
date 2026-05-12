package com.standroid.launcher.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Thin wrapper around SharedPreferences.
 * Initialised once via [init] from [com.standroid.launcher.STApp].
 */
object AppPrefs {

    private const val PREFS_NAME = "standroid_prefs"

    // Keys
    private const val KEY_ST_INSTALLED        = "is_st_installed"
    private const val KEY_ST_VERSION          = "st_version"
    private const val KEY_SERVER_PORT         = "server_port"
    private const val KEY_ST_DIR_PATH         = "st_dir_path"
    private const val KEY_AUTO_UPDATE_STARTUP = "auto_update_on_startup"

    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** True after first-launch setup has completed successfully. */
    var isStInstalled: Boolean
        get() = prefs.getBoolean(KEY_ST_INSTALLED, false)
        set(v) = prefs.edit { putBoolean(KEY_ST_INSTALLED, v) }

    /** The SillyTavern git ref / version tag that is currently installed. */
    var stVersion: String
        get() = prefs.getString(KEY_ST_VERSION, "") ?: ""
        set(v) = prefs.edit { putString(KEY_ST_VERSION, v) }

    /** HTTP port ST listens on (default 8000). */
    var serverPort: Int
        get() = prefs.getInt(KEY_SERVER_PORT, 8000)
        set(v) = prefs.edit { putInt(KEY_SERVER_PORT, v) }

    /** Path to the custom selected directory (if any) */
    var stDirPath: String?
        get() = prefs.getString(KEY_ST_DIR_PATH, null)
        set(v) = prefs.edit { putString(KEY_ST_DIR_PATH, v) }

    /** Auto-update ST on every app startup (git fetch + reset + npm install). Default false. */
    var autoUpdateOnStartup: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE_STARTUP, true)
        set(v) = prefs.edit { putBoolean(KEY_AUTO_UPDATE_STARTUP, v) }

    /** Wipes all prefs — used by Reinstall flow. */
    fun clear(): Unit = prefs.edit { clear() }
}
