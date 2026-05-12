package com.standroid.launcher.util

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.standroid.launcher.R

/**
 * Helper that handles the POST_NOTIFICATIONS runtime permission flow.
 *
 * Usage (from an Activity onCreate):
 * ```
 * val permHelper = Permissions(this) { granted -> if (granted) startService() }
 * permHelper.requestIfNeeded()
 * ```
 */
class Permissions(
    private val activity: AppCompatActivity,
    private val onResult: (granted: Boolean) -> Unit,
) {

    private val launcher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            onResult(granted)
        }

    /** True if the notification permission is already granted. */
    val hasNotificationPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * If permission is already granted, invokes [onResult](true) immediately.
     * Otherwise shows a rationale dialog (if needed) then requests the permission.
     */
    fun requestIfNeeded() {
        if (hasNotificationPermission) {
            onResult(true)
            return
        }

        if (activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            AlertDialog.Builder(activity)
                .setMessage(R.string.perm_rationale_notifications)
                .setPositiveButton(android.R.string.ok) { _, _ -> launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> onResult(false) }
                .show()
        } else {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
