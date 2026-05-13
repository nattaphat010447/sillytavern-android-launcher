package com.standroid.launcher.ui

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AlertDialog
import com.standroid.launcher.R
import com.standroid.launcher.databinding.ActivityAdvancedSettingsBinding
import com.standroid.launcher.service.STForegroundService
import com.standroid.launcher.setup.NpmInstaller
import com.standroid.launcher.setup.STInstaller
import com.standroid.launcher.util.AppLogger
import com.standroid.launcher.util.AppPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Advanced Settings screen — accessible from SettingsActivity.
 *
 * Exposes:
 *  - Auto-Update on Startup toggle
 *  - Reinstall Dependencies — deletes node_modules and runs npm install with live log dialog
 *  - Full Reset — backs up data/, wipes SillyTavern, re-clones, restores data with live log dialog
 *
 * All dialogs use MaterialAlertDialogBuilder with ThemeOverlay.STANDROID.Dialog for
 * consistent dark-purple styling.
 *
 * Live-log dialog features:
 *  - Title: "{Operation} · M:SS" (elapsed timer, updated every second)
 *  - Stats line: "{N} packages installed so far" (updated every 2 s)
 *  - Scrollable monospace log panel with color-coded prefixes
 *  - Cancel button: asks for confirmation before aborting
 *  - On completion: title changes, stats shows result, Cancel becomes "Done"
 */
class AdvancedSettingsActivity : AppCompatActivity() {

    private val TAG = "AdvancedSettingsActivity"
    private lateinit var binding: ActivityAdvancedSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdvancedSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ── Auto-update toggle ────────────────────────────────────────
        binding.switchAutoUpdate.isChecked = AppPrefs.autoUpdateOnStartup
        binding.switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.autoUpdateOnStartup = isChecked
        }

        // ── Reinstall Dependencies ────────────────────────────────────
        binding.rowReinstallDeps.setOnClickListener {
            styledDialog()
                .setTitle("Reinstall Dependencies?")
                .setMessage(
                    "This will delete node_modules and run npm install again.\n\n" +
                    "Your chats and settings are NOT affected.\n\n" +
                    "This may take 5-15 minutes depending on your connection."
                )
                .setPositiveButton("Reinstall") { _, _ -> doReinstallDependencies() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ── Full Reset ────────────────────────────────────────────────
        binding.rowFullReset.setOnClickListener {
            styledDialog()
                .setTitle("Full Reset")
                .setMessage(
                    "This will:\n" +
                    "1. Back up your chat data automatically\n" +
                    "2. Delete the entire SillyTavern installation\n" +
                    "3. Re-clone SillyTavern from GitHub\n" +
                    "4. Restore your chat data\n\n" +
                    "This takes 10-20 minutes and requires internet.\n\n" +
                    "Are you sure?"
                )
                .setPositiveButton("Reset") { _, _ -> confirmFullReset() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ── Convenience builder ───────────────────────────────────────────

    /** Returns a [MaterialAlertDialogBuilder] pre-configured with the STANDROID dialog theme. */
    private fun styledDialog() =
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_STANDROID_Dialog)

    // ── Live-log dialog ───────────────────────────────────────────────

    private inner class LiveProgressDialog(baseTitle: String) {

        private val dialogView = layoutInflater.inflate(R.layout.dialog_live_progress, null)
        private val tvStats: TextView = dialogView.findViewById(R.id.tvStats)
        private val tvLog: TextView = dialogView.findViewById(R.id.tvLog)
        private val scrollLog: ScrollView = dialogView.findViewById(R.id.scrollLog)

        val dialog: AlertDialog = styledDialog()
            .setTitle(baseTitle)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("Cancel", null)
            .create()

        /** Append a color-coded log line (call from any thread). */
        fun appendLog(line: String) {
            val span = buildColoredLine(line)
            runOnUiThread {
                tvLog.append(span)
                scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
            }
        }

        /** Update the persistent stats header (call from any thread). */
        fun updateStats(text: String) {
            runOnUiThread { tvStats.text = text }
        }

        /** Update the dialog title (call from any thread). */
        fun setTitle(title: String) {
            runOnUiThread { dialog.setTitle(title) }
        }

        /**
         * Transition to completed state.
         * Title and stats reflect success/failure; Cancel becomes "Done".
         */
        fun complete(success: Boolean, finalTitle: String) {
            runOnUiThread {
                val icon = if (success) "[OK]" else "[!!]"
                dialog.setTitle("$icon $finalTitle")
                tvStats.text = if (success) "Completed successfully." else "Operation failed."
                tvStats.setTextColor(
                    ContextCompat.getColor(
                        this@AdvancedSettingsActivity,
                        if (success) R.color.status_running else R.color.status_stopped
                    )
                )
                dialog.setCancelable(true)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "Done"
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                    dialog.dismiss()
                }
            }
        }

        /**
         * Wire up the Cancel button with a confirmation dialog.
         * [onConfirmed] is called on the main thread when the user confirms.
         */
        fun setCancelConfirmation(onConfirmed: () -> Unit) {
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                    styledDialog()
                        .setTitle("Cancel operation?")
                        .setMessage(
                            "The operation is still in progress. " +
                            "Cancelling now may leave files in an incomplete state."
                        )
                        .setPositiveButton("Yes, cancel") { _, _ -> onConfirmed() }
                        .setNegativeButton("Keep waiting", null)
                        .show()
                }
            }
        }
    }

    // ── Reinstall Dependencies ────────────────────────────────────────

    private fun doReinstallDependencies() {
        val stDir = File(filesDir, "SillyTavern")
        if (!stDir.exists()) {
            android.widget.Toast.makeText(this, "SillyTavern is not installed.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        if (STForegroundService.isServiceRunning) {
            startService(STForegroundService.stopIntent(this))
        }

        val lp = LiveProgressDialog("Reinstall Dependencies")
        lp.dialog.show()

        var workJob: Job? = null

        lp.setCancelConfirmation {
            workJob?.cancel()
            lp.dialog.dismiss()
        }

        val start = System.currentTimeMillis()
        val nodeModulesDir = File(stDir, "node_modules")

        val timerJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(1000)
                val secs = (System.currentTimeMillis() - start) / 1000
                val m = secs / 60; val s = secs % 60
                lp.setTitle("Reinstall Dependencies · $m:${s.toString().padStart(2, '0')}")
            }
        }

        val statsJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(2000)
                val count = nodeModulesDir.listFiles()?.size ?: 0
                if (count > 0) {
                    withContext(Dispatchers.Main) {
                        lp.updateStats("$count packages installed so far")
                    }
                }
            }
        }

        workJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                lp.appendLog("Deleting node_modules...")
                File(stDir, "node_modules").deleteRecursively()
                File(stDir, "package-lock.json").delete()
                lp.appendLog("node_modules deleted.")

                lp.appendLog("Running npm install...")
                val npmInstaller = NpmInstaller(this@AdvancedSettingsActivity)
                val ok = npmInstaller.install(
                    stDir = stDir,
                    onLog = { raw -> formatNpmLine(raw)?.let { lp.appendLog(it) } }
                )

                timerJob.cancel()
                statsJob.cancel()

                if (ok) {
                    lp.complete(success = true, finalTitle = "Reinstall Complete")
                } else {
                    lp.appendLog("npm install failed. Check your internet connection.")
                    lp.complete(success = false, finalTitle = "Reinstall Failed")
                }
            } catch (e: CancellationException) {
                timerJob.cancel()
                statsJob.cancel()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Reinstall failed", e)
                timerJob.cancel()
                statsJob.cancel()
                lp.appendLog("Error: ${e.message}")
                lp.complete(success = false, finalTitle = "Reinstall Failed")
            }
        }
    }

    // ── Full Reset ────────────────────────────────────────────────────

    private fun confirmFullReset() {
        styledDialog()
            .setTitle("Last chance!")
            .setMessage("This will wipe SillyTavern and re-clone it from GitHub. Your data will be backed up first. Continue?")
            .setPositiveButton("Yes, reset") { _, _ -> doFullReset() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doFullReset() {
        val stDir = File(filesDir, "SillyTavern")

        if (STForegroundService.isServiceRunning) {
            startService(STForegroundService.stopIntent(this))
        }

        val lp = LiveProgressDialog("Full Reset")
        lp.dialog.show()

        var workJob: Job? = null

        lp.setCancelConfirmation {
            workJob?.cancel()
            lp.dialog.dismiss()
        }

        val start = System.currentTimeMillis()
        val timerJob = lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(1000)
                val secs = (System.currentTimeMillis() - start) / 1000
                val m = secs / 60; val s = secs % 60
                lp.setTitle("Full Reset · $m:${s.toString().padStart(2, '0')}")
            }
        }

        workJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dataDir = File(stDir, "data")
                val backupDir = File(filesDir, "st_data_backup_${System.currentTimeMillis()}")
                if (dataDir.exists()) {
                    lp.appendLog("Backing up data/ directory...")
                    dataDir.copyRecursively(backupDir, overwrite = true)
                    lp.appendLog("Backup saved.")
                }

                lp.appendLog("Deleting SillyTavern...")
                stDir.deleteRecursively()
                lp.appendLog("Deleted.")

                lp.appendLog("Cloning SillyTavern from GitHub...")
                val installer = STInstaller(this@AdvancedSettingsActivity)
                installer.install(destDir = stDir) { step, _ -> lp.appendLog(step) }
                lp.appendLog("Clone complete.")

                if (backupDir.exists()) {
                    lp.appendLog("Restoring data/ directory...")
                    val newDataDir = File(stDir, "data")
                    newDataDir.mkdirs()
                    backupDir.copyRecursively(newDataDir, overwrite = true)
                    backupDir.deleteRecursively()
                    lp.appendLog("Data restored.")
                }

                lp.appendLog("Running npm install...")
                val npmInstaller = NpmInstaller(this@AdvancedSettingsActivity)
                val ok = npmInstaller.install(
                    stDir = stDir,
                    onLog = { raw -> formatNpmLine(raw)?.let { lp.appendLog(it) } }
                )

                timerJob.cancel()

                if (ok) {
                    lp.appendLog("SillyTavern reset and data restored. You can now start SillyTavern.")
                    lp.complete(success = true, finalTitle = "Full Reset Complete")
                } else {
                    lp.appendLog("Reset done but npm install failed. Try Settings > Check for Updates.")
                    lp.complete(success = false, finalTitle = "Reset Incomplete")
                }
            } catch (e: CancellationException) {
                timerJob.cancel()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Full reset failed", e)
                timerJob.cancel()
                lp.appendLog("Error: ${e.message}")
                lp.complete(success = false, finalTitle = "Reset Failed")
            }
        }
    }

    // ── Log formatting ────────────────────────────────────────────────

    /**
     * Filters and formats raw npm output lines.
     * Returns null for lines that should be suppressed (304 responses, blank lines, etc.).
     *
     * Handles two main npm http log formats:
     *   "npm http fetch GET 200 https://registry.npmjs.org/lodash/-/lodash-4.17.21.tgz 245ms (cache miss)"
     *   "npm http cache https://registry.npmjs.org/eslint 18ms (cache hit)"
     */
    private fun formatNpmLine(raw: String): String? {
        val line = raw.trim()
        return when {
            line.contains("http fetch GET 200") -> extractPackage(line)?.let { "fetch  $it" }
            line.contains("http cache")         -> extractPackage(line)?.let { "cache  $it" }
            line.contains("http fetch GET 304") -> null
            line.contains("added") && line.contains("packages") -> line
            line.contains("WARN deprecated")    -> "warn   ${line.substringAfter("WARN deprecated ")}"
            line.contains("npm warn", ignoreCase = true) -> line
            line.contains("ERR!") || line.contains("npm error", ignoreCase = true) -> line
            line.isBlank() -> null
            else -> null
        }
    }

    /**
     * Extracts the package name from a registry URL embedded in an npm log line.
     * Handles scoped packages (%2f-encoded) and tarball URLs.
     */
    private fun extractPackage(line: String): String? {
        val marker = "registry.npmjs.org/"
        val idx = line.indexOf(marker)
        if (idx < 0) return null
        val url = line.substring(idx + marker.length).substringBefore(' ').trimEnd('/')
        val decoded = url.replace("%2f", "/", ignoreCase = true)
        val pkg = decoded.substringBefore("/-/")
        return pkg.ifBlank { null }
    }

    /**
     * Builds a [SpannableStringBuilder] with the prefix colored and the rest in text_secondary.
     *
     * Prefix color mapping (6-char padded prefix):
     *   "fetch " → pink_light  (fresh network download)
     *   "cache " → purple_200  (served from local cache)
     *   "warn  " → status_loading (yellow)
     *   "ERR"    → status_stopped (red)
     *   others   → text_secondary
     */
    private fun buildColoredLine(line: String): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        val newline = "\n"

        val prefixEnd = line.indexOf("  ").takeIf { it in 1..7 }
        if (prefixEnd != null) {
            val prefix = line.substring(0, prefixEnd)
            val rest = line.substring(prefixEnd)

            val prefixColor = when (prefix.trim()) {
                "fetch"  -> ContextCompat.getColor(this, R.color.pink_light)
                "cache"  -> ContextCompat.getColor(this, R.color.purple_200)
                "warn"   -> ContextCompat.getColor(this, R.color.status_loading)
                else     -> ContextCompat.getColor(this, R.color.text_secondary)
            }
            val bodyColor = ContextCompat.getColor(this, R.color.text_secondary)

            ssb.append(prefix, ForegroundColorSpan(prefixColor), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.append(rest, ForegroundColorSpan(bodyColor), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            // Error lines, summary lines, plain lines
            val color = when {
                line.contains("ERR", ignoreCase = false) || line.contains("error", ignoreCase = true) ->
                    ContextCompat.getColor(this, R.color.status_stopped)
                line.contains("added") && line.contains("packages") ->
                    ContextCompat.getColor(this, R.color.status_running)
                else ->
                    ContextCompat.getColor(this, R.color.text_secondary)
            }
            ssb.append(line, ForegroundColorSpan(color), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        ssb.append(newline)
        return ssb
    }
}
