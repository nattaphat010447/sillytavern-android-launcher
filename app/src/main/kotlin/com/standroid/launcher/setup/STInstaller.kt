package com.standroid.launcher.setup

import android.content.Context
import com.standroid.launcher.util.AppLogger
import com.standroid.launcher.util.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import java.io.File

/**
 * Clones SillyTavern from GitHub using JGit (shallow clone, staging branch).
 *
 * After a successful clone, [NpmInstaller] is used to install npm dependencies.
 */
class STInstaller(private val ctx: Context) {

    fun interface ProgressCallback {
        /** [step] = human-readable description, [percent] = 0–100 or -1 for indeterminate */
        fun onProgress(step: String, percent: Int)
    }

    private val TAG = "STInstaller"

    private val REPO_URL = "https://github.com/SillyTavern/SillyTavern.git"
    
    // We default to internal filesDir if no custom path is provided yet
    private val defaultStDir get() = File(ctx.filesDir, "SillyTavern")

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Full install flow:
     *  git clone → npm ci
     *
     * Runs entirely on [Dispatchers.IO].
     * Returns the installed branch name, or throws on failure.
     */
    suspend fun install(
        branch: String = "staging",
        destDir: File = defaultStDir,
        onProgress: ProgressCallback,
    ): String = withContext(Dispatchers.IO) {
        if (!Network.isConnected(ctx)) error("No internet connection")

        AppLogger.i(TAG, "Cloning ST branch '$branch' into ${destDir.absolutePath}")

        destDir.deleteRecursively()
        destDir.mkdirs()

        cloneRepository(REPO_URL, branch, destDir, onProgress)

        // Apply extension patch to enable git-less extension updates on Android
        val patcher = ExtensionPatcher(ctx)
        val patchOk = patcher.applyPatch(destDir)
        if (!patchOk) {
            AppLogger.w(TAG, "Extension patch failed — extension updates may not work without native git")
        }

        branch
    }

    /** Returns true if ST files are present in the target directory. */
    fun isInstalled(dir: File = defaultStDir): Boolean = File(dir, "server.js").exists()

    /** Deletes the ST installation directory (for Reinstall). */
    fun uninstall(dir: File = defaultStDir) {
        dir.deleteRecursively()
        AppLogger.i(TAG, "ST uninstalled from ${dir.absolutePath}")
    }

    // ── Private helpers ───────────────────────────────────────────────

    private fun cloneRepository(
        url: String,
        branch: String,
        destDir: File,
        onProgress: ProgressCallback,
    ) {
        val monitor = object : ProgressMonitor {
            private var totalTasks = 0
            private var completedTasks = 0
            private var currentTaskName = ""
            private var lastPercent = -1

            override fun start(totalTasks: Int) {
                this.totalTasks = totalTasks
                this.completedTasks = 0
            }

            override fun beginTask(title: String?, totalWork: Int) {
                currentTaskName = title ?: "Working"
                this.completedTasks = 0
                this.totalTasks = totalWork
                lastPercent = -1
                onProgress.onProgress(currentTaskName, if (totalWork == ProgressMonitor.UNKNOWN) -1 else 0)
            }

            override fun update(completed: Int) {
                this.completedTasks += completed
                if (totalTasks > 0) {
                    val pct = (completedTasks * 100 / totalTasks)
                    if (pct != lastPercent) {
                        lastPercent = pct
                        onProgress.onProgress(currentTaskName, pct)
                    }
                }
            }

            override fun endTask() {
                onProgress.onProgress("$currentTaskName complete", 100)
            }

            override fun isCancelled(): Boolean = false
            
            override fun showDuration(workRetain: Boolean) {}
        }

        try {
            Git.cloneRepository()
                .setURI(url)
                .setDirectory(destDir)
                .setBranch(branch)
                .setCloneAllBranches(false)
                .setCloneSubmodules(true)
                .setDepth(1) // Shallow clone for speed!
                .setProgressMonitor(monitor)
                .call()
                .use { git ->
                    AppLogger.i(TAG, "Clone successful")
                }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Git clone failed", e)
            error("Clone failed: ${e.message}")
        }
    }
}
