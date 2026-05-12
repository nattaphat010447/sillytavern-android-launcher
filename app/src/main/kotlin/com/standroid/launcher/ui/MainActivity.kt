package com.standroid.launcher.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.standroid.launcher.R
import com.standroid.launcher.databinding.ActivityMainBinding
import com.standroid.launcher.service.STForegroundService
import com.standroid.launcher.setup.NpmInstaller
import com.standroid.launcher.util.AppLogger
import com.standroid.launcher.util.AppPrefs
import com.standroid.launcher.util.Network
import com.standroid.launcher.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import java.io.File

/**
 * Application entry point and main dashboard.
 *
 * Decision tree on launch:
 *  1. Request POST_NOTIFICATIONS permission (Android 13+)
 *  2. If SillyTavern is not installed → launch [SetupActivity]
 *  3. If installed → optional auto-update check → show Start button
 *
 * Auto-update state machine:
 *  IDLE → CHECKING → UP_TO_DATE | UPDATING → UPDATED | UPDATE_FAILED
 *
 * The Start button is disabled during CHECKING and UPDATING states.
 */
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private lateinit var permissions: Permissions

    /** Prevents double-triggering the update flow on rapid onResume calls. */
    private var isUpdateRunning = false
    private var updateJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate() / setContentView()
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissions = Permissions(this) { granted ->
            AppLogger.i(TAG, "Notification permission granted=$granted")
            proceed()
        }

        setStatus("Checking permissions…", StatusStyle.MUTED)
        permissions.requestIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // Refresh status when returning from Settings (toggle may have changed)
        if (AppPrefs.isStInstalled && !STForegroundService.isServiceRunning && !isUpdateRunning) {
            setStatus("● Ready to launch", StatusStyle.READY)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        updateJob?.cancel()
    }

    // ── Navigation helpers ────────────────────────────────────────────

    private fun proceed() {
        if (!File(filesDir, "SillyTavern/server.js").exists()) {
            AppLogger.i(TAG, "ST not installed — launching SetupActivity")
            setStatus("First launch — starting setup…", StatusStyle.MUTED)
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        // ST is installed — show buttons
        binding.btnStart.visibility = View.VISIBLE
        binding.btnSettings.visibility = View.VISIBLE

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnStart.setOnClickListener {
            launchServer()
        }

        // Decide whether to auto-update before enabling Start
        if (AppPrefs.autoUpdateOnStartup && Network.isConnected(this)) {
            runAutoUpdate()
        } else if (AppPrefs.autoUpdateOnStartup && !Network.isConnected(this)) {
            setStatus("⚠ Offline · Ready to launch", StatusStyle.WARNING)
            setStartEnabled(true)
        } else {
            setStatus("● Ready to launch", StatusStyle.READY)
            setStartEnabled(true)
        }
    }

    // ── Auto-update flow ──────────────────────────────────────────────

    /**
     * Option B: detect new commits after fetch.
     * - If 0 new commits → skip npm install, show "Up to date"
     * - If >0 new commits → hard reset + npm install, show "Updated"
     * - On any failure → show warning but still allow launch
     */
    private fun runAutoUpdate() {
        if (isUpdateRunning) return
        isUpdateRunning = true
        setStartEnabled(false)

        val stDir = File(filesDir, "SillyTavern")
        if (!stDir.exists() || !File(stDir, ".git").exists()) {
            AppLogger.w(TAG, "Auto-update: no git repo found, skipping")
            setStatus("● Ready to launch", StatusStyle.READY)
            setStartEnabled(true)
            isUpdateRunning = false
            return
        }

        updateJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // ── Step 1: Fetch ──────────────────────────────────────
                withContext(Dispatchers.Main) {
                    setStatus("↻ Checking for updates…", StatusStyle.LOADING)
                    startDotAnimation()
                }

                Git.open(stDir).use { git ->
                    git.fetch().call()

                    // ── Step 2: Count new commits ──────────────────────
                    val newCommits = git.log()
                        .addRange(
                            git.repository.resolve("HEAD"),
                            git.repository.resolve("origin/staging")
                        )
                        .call()
                        .count()

                    AppLogger.i(TAG, "Auto-update: $newCommits new commit(s) found")

                    if (newCommits == 0) {
                        // Already up to date — no npm install needed
                        withContext(Dispatchers.Main) {
                            stopDotAnimation()
                            setStatus("✓ Up to date · Ready to launch", StatusStyle.SUCCESS)
                            setStartEnabled(true)
                        }
                    } else {
                        // ── Step 3: Apply update ───────────────────────
                        withContext(Dispatchers.Main) {
                            setStatus("⇣ Updating ($newCommits commit(s))…", StatusStyle.LOADING)
                        }

                        git.reset()
                            .setMode(ResetCommand.ResetType.HARD)
                            .setRef("origin/staging")
                            .call()

                        // ── Step 4: npm install ────────────────────────
                        withContext(Dispatchers.Main) {
                            setStatus("⇣ Installing dependencies…", StatusStyle.LOADING)
                        }

                        val npmInstaller = NpmInstaller(this@MainActivity)
                        val npmOk = npmInstaller.install(
                            stDir = stDir,
                            onLog = { line -> AppLogger.d(TAG, "npm: $line") }
                        )

                        withContext(Dispatchers.Main) {
                            stopDotAnimation()
                            if (npmOk) {
                                setStatus("✓ Updated · Ready to launch", StatusStyle.SUCCESS)
                            } else {
                                setStatus("⚠ Update incomplete · Ready to launch", StatusStyle.WARNING)
                            }
                            setStartEnabled(true)
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Auto-update failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    stopDotAnimation()
                    setStatus("⚠ Update check failed · Ready to launch", StatusStyle.WARNING)
                    setStartEnabled(true)
                }
            } finally {
                isUpdateRunning = false
            }
        }
    }

    private fun launchServer() {
        setStatus("Starting SillyTavern…", StatusStyle.LOADING)
        ContextCompat.startForegroundService(this, STForegroundService.startIntent(this))
        startActivity(Intent(this, WebViewActivity::class.java))
    }

    // ── Status pill helpers ───────────────────────────────────────────

    private enum class StatusStyle { READY, LOADING, SUCCESS, WARNING, MUTED }

    private fun setStatus(text: String, style: StatusStyle) {
        binding.tvStatus.text = text
        val color = when (style) {
            StatusStyle.READY   -> getColor(R.color.purple_glow)
            StatusStyle.LOADING -> getColor(R.color.status_loading)
            StatusStyle.SUCCESS -> getColor(R.color.status_running)
            StatusStyle.WARNING -> getColor(R.color.status_stopped)
            StatusStyle.MUTED   -> getColor(R.color.text_muted)
        }
        binding.tvStatus.setTextColor(color)
    }

    private fun setStartEnabled(enabled: Boolean) {
        binding.btnStart.isEnabled = enabled
        binding.btnStart.alpha = if (enabled) 1.0f else 0.45f
    }

    // ── Dot animation (. .. ...) for long-running operations ──────────

    private var dotJob: Job? = null

    private fun startDotAnimation() {
        dotJob?.cancel()
        dotJob = lifecycleScope.launch(Dispatchers.Main) {
            val baseText = binding.tvStatus.text.toString().trimEnd('.').trimEnd(' ')
            var dots = 0
            while (true) {
                dots = (dots % 3) + 1
                binding.tvStatus.text = "$baseText${".".repeat(dots)}"
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private fun stopDotAnimation() {
        dotJob?.cancel()
        dotJob = null
    }
}
