package com.standroid.launcher.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.standroid.launcher.R
import com.standroid.launcher.ui.WebViewActivity
import com.standroid.launcher.util.AppLogger
import com.standroid.launcher.util.AppPrefs
import com.standroid.launcher.util.GitSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that owns the SillyTavern / Node.js process lifecycle.
 *
 * Commands (sent via [startService] intent action):
 *  - [ACTION_START]   — spawn the ST server if not already running
 *  - [ACTION_STOP]    — kill the server and stop the service
 *  - [ACTION_RESTART] — stop then start (e.g. after a settings change)
 *
 * Automatically restarts the server up to 3 times on unexpected crashes.
 */
class STForegroundService : Service() {

    // ── Intent actions ────────────────────────────────────────────────
    companion object {
        const val ACTION_START   = "com.standroid.launcher.ACTION_START"
        const val ACTION_STOP    = "com.standroid.launcher.ACTION_STOP"
        const val ACTION_RESTART = "com.standroid.launcher.ACTION_RESTART"
        const val BROADCAST_SERVER_STOPPED = "com.standroid.launcher.SERVER_STOPPED"

        private const val NOTIF_ID        = 1001
        private const val CHANNEL_ID      = "st_server_channel"

        /**
         * True while the foreground service is actively running the ST server.
         * Used by WebViewActivity.onResume() to detect if the server was stopped
         * while the activity was in the background (avoids creating a new NodeRunner
         * instance just to check a per-instance processRef).
         */
        @Volatile
        var isServiceRunning: Boolean = false
            private set

        /**
         * Optional log listener that receives every stdout/stderr line from the
         * running Node process.  WebViewActivity sets this while its loading
         * overlay is visible and clears it when the overlay is hidden or the
         * activity stops.  Volatile so the service thread sees updates immediately.
         */
        @Volatile
        var nodeLogListener: NodeRunner.LogListener? = null

        fun startIntent(ctx: Context)   = Intent(ctx, STForegroundService::class.java).apply { action = ACTION_START   }
        fun stopIntent(ctx: Context)    = Intent(ctx, STForegroundService::class.java).apply { action = ACTION_STOP    }
        fun restartIntent(ctx: Context) = Intent(ctx, STForegroundService::class.java).apply { action = ACTION_RESTART }
    }

    private val TAG = "STForegroundService"

    private lateinit var nodeRunner: NodeRunner
    private lateinit var healthChecker: HealthChecker

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null
    
    // Auto-restart variables
    private var restartCount = 0
    private var isIntentionallyStopped = false

    // ── Service lifecycle ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        nodeRunner    = NodeRunner(this)
        healthChecker = HealthChecker()
        // Forward every Node log line to whoever is currently registered in the companion.
        nodeRunner.setLogListener { line -> nodeLogListener?.onLine(line) }
        createNotificationChannel()
        // Extract git template files from assets on first run.
        // NodeRunner references filesDir/git-templates via GIT_TEMPLATE_DIR env var.
        GitSetup.ensureTemplates(this)
        AppLogger.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, null -> {
                isIntentionallyStopped = false
                restartCount = 0
                handleStart()
            }
            ACTION_STOP        -> handleStop()
            ACTION_RESTART     -> { 
                isIntentionallyStopped = false
                restartCount = 0
                nodeRunner.stop()
                handleStart() 
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isIntentionallyStopped = true
        isServiceRunning = false
        nodeRunner.stop()
        startJob?.cancel()
        super.onDestroy()
        AppLogger.i(TAG, "Service destroyed")
    }

    // ── Start / Stop logic ────────────────────────────────────────────

    /**
     * Finds an available port starting from the desired port.
     */
    private fun findAvailablePort(startPort: Int): Int {
        var port = startPort
        while (port < 65535) {
            try {
                java.net.ServerSocket(port).use {
                    it.reuseAddress = true
                    return port
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Port $port is in use. Trying next port...")
                port++
            }
        }
        return startPort
    }

    /**
     * Patches config.yaml with Android-safe settings:
     *  - IPv4 only (Android does not support dual-stack binding on all versions)
     *  - browserLaunch disabled (`xdg-open` does not exist on Android)
     *  - git.backend set to builtin (use isomorphic-git instead of native git binary)
     *
     * Creates a minimal config.yaml if the file doesn't exist yet.
     * Uses simple line-by-line replacement — no full YAML parser needed.
     */
    private fun patchConfig(stDir: File) {
        val configFile = File(stDir, "config.yaml")
        
        if (!configFile.exists()) {
            configFile.parentFile?.mkdirs()
            // Create a minimal config so ST doesn't crash on first boot trying to open browser
            val minimalConfig = """
                # Auto-generated by STANDROID
                listen: false
                protocol:
                  ipv4: true
                  ipv6: false
                port: ${AppPrefs.serverPort}
                browserLaunch:
                  enabled: false
                listenAddress:
                  ipv4: 0.0.0.0
                git:
                  backend: builtin
            """.trimIndent()
            configFile.writeText(minimalConfig + "\n")
            AppLogger.i(TAG, "config.yaml: created minimal safe config with git.backend=builtin")
            return
        }

        AppLogger.d(TAG, "--- config.yaml BEFORE PATCH ---\n${configFile.readText()}\n--------------------------------")

        val lines = configFile.readLines()
        val newLines = mutableListOf<String>()
        var changed = false

        var inProtocolBlock = false
        var inBrowserLaunchBlock = false
        var inGitBlock = false
        var foundGitBlock = false

        for (line in lines) {
            val trimmed = line.trim()

            // Detect top-level block starts (no leading spaces)
            if (!line.startsWith(" ") && line.endsWith(":")) {
                inProtocolBlock = line.startsWith("protocol:")
                inBrowserLaunchBlock = line.startsWith("browserLaunch:")
                inGitBlock = line.startsWith("git:")
                if (inGitBlock) foundGitBlock = true
            }

            var patchedLine = line

            when {
                inProtocolBlock && trimmed.startsWith("ipv6:") -> {
                    if (trimmed.endsWith("true")) {
                        patchedLine = line.replace("true", "false")
                        changed = true
                    }
                }
                inBrowserLaunchBlock && trimmed.startsWith("enabled:") -> {
                    if (trimmed.endsWith("true")) {
                        patchedLine = line.replace("true", "false")
                        changed = true
                    }
                }
                inGitBlock && trimmed.startsWith("backend:") -> {
                    if (!trimmed.endsWith("builtin")) {
                        // Replace whatever value is there with builtin
                        patchedLine = line.replace(Regex("backend:.*"), "backend: builtin")
                        changed = true
                    }
                }
            }

            newLines.add(patchedLine)
        }

        // If git block doesn't exist at all, append it
        if (!foundGitBlock) {
            newLines.add("git:")
            newLines.add("  backend: builtin")
            changed = true
        }

        if (changed) {
            configFile.writeText(newLines.joinToString("\n"))
            AppLogger.i(TAG, "config.yaml: patched Android-safe settings (ipv6=false, browserLaunch=false, git.backend=builtin)")
            AppLogger.d(TAG, "--- config.yaml AFTER PATCH ---\n${configFile.readText()}\n-------------------------------")
        } else {
            AppLogger.d(TAG, "config.yaml: no patch needed (already safe)")
        }
    }

    private fun handleStart() {
        if (nodeRunner.isRunning()) {
            AppLogger.d(TAG, "Node already running — skipping start")
            return
        }

        isServiceRunning = true
        startForeground(NOTIF_ID, buildNotification(running = false))

        startJob = serviceScope.launch {
            // Free memory before starting
            System.gc()
            
            val stDir  = File(filesDir, "SillyTavern")
            
            // Check for available port
            var port = AppPrefs.serverPort
            val availablePort = findAvailablePort(port)
            if (availablePort != port) {
                AppLogger.i(TAG, "Port $port in use, switched to $availablePort")
                port = availablePort
                AppPrefs.serverPort = port // Update the preference so WebView knows where to look
            }

            // Dynamic heap size — use 35% of total device RAM, clamped to [512, 2048] MB
            val heapMb = calculateHeapSizeMb()
            AppLogger.i(TAG, "Node.js heap size: ${heapMb}MB (device RAM: ${totalRamMb()}MB)")

            val args = listOf(
                "--max-old-space-size=$heapMb",
                "--no-deprecation",   // suppress deprecation warnings → less stdout noise
                "--no-warnings",      // suppress other Node warnings
                "server.js",
                "--port", port.toString(),
                "--no-open"           // localhost only — no browser launch
            )

            patchConfig(stDir)

            // NODE_COMPILE_CACHE — cache compiled bytecode across restarts for faster startup
            val compileCacheDir = File(cacheDir, "node_compile_cache").also { it.mkdirs() }

            AppLogger.i(TAG, "Starting ST on port $port in ${stDir.absolutePath}")
            val proc = nodeRunner.start(
                workingDir = stDir,
                args = args,
                env = mapOf("NODE_COMPILE_CACHE" to compileCacheDir.absolutePath),
            ) ?: run {
                AppLogger.e(TAG, "Node binary unavailable — stopping service")
                isServiceRunning = false
                stopSelf()
                return@launch
            }

            val ready = healthChecker.waitUntilReady(port = port)
            if (ready) {
                updateNotification(running = true)
                AppLogger.i(TAG, "ST server is UP on port $port")
            } else {
                AppLogger.w(TAG, "ST did not become ready — process alive=${proc.isAlive}")
                updateNotification(running = false)
            }

            // Monitor process for crash recovery
            val exitCode = proc.waitFor()
            
            if (!isIntentionallyStopped) {
                AppLogger.w(TAG, "Node.js process died unexpectedly with exit code: $exitCode")
                
                if (restartCount < 3) {
                    restartCount++
                    AppLogger.i(TAG, "Auto-restarting ST... (Attempt $restartCount/3) in 3 seconds")
                    updateNotification(running = false)
                    
                    kotlinx.coroutines.delay(3000)
                    handleStart()
                } else {
                    AppLogger.e(TAG, "ST crashed too many times. Giving up.")
                    isServiceRunning = false
                    // Show error notification
                    val nm = getSystemService(NotificationManager::class.java)
                    val errorNotif = NotificationCompat.Builder(this@STForegroundService, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentTitle("SillyTavern Crashed")
                        .setContentText("The server stopped unexpectedly too many times.")
                        .build()
                    nm.notify(NOTIF_ID, errorNotif)
                    stopSelf()
                }
            } else {
                AppLogger.i(TAG, "Node.js process stopped normally.")
            }
        }
    }

    private fun handleStop() {
        AppLogger.i(TAG, "Stopping server and broadcasting...")
        isIntentionallyStopped = true
        isServiceRunning = false
        
        // Broadcast BEFORE stopping the runner to ensure it goes through
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(BROADCAST_SERVER_STOPPED))
            
        nodeRunner.stop()
        startJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /** Returns total device RAM in MB. */
    private fun totalRamMb(): Long {
        val am = getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    /**
     * Calculates a sensible Node.js heap size based on device RAM.
     * Uses 35% of total RAM, clamped between 512 MB and 2048 MB.
     */
    private fun calculateHeapSizeMb(): Int {
        val totalMb = totalRamMb()
        val target = (totalMb * 0.35).toLong()
        return target.coerceIn(512L, 2048L).toInt()
    }

    // ── Notifications ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(running: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, WebViewActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val restartIntent = PendingIntent.getService(
            this, 2,
            restartIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(if (running) R.string.notif_title_running else R.string.notif_title_stopped))
            .setContentText(getString(if (running) R.string.notif_text_running else R.string.notif_text_stopped))
            .setContentIntent(openIntent)
            .setOngoing(running)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.btn_stop),    stopIntent)
            .addAction(android.R.drawable.ic_media_rew,   getString(R.string.btn_restart), restartIntent)
            .build()
    }

    private fun updateNotification(running: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(running))
    }
}
