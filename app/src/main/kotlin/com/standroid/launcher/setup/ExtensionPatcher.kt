package com.standroid.launcher.setup

import android.content.Context
import com.standroid.launcher.service.NodeRunner
import com.standroid.launcher.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Applies the STANDROID extension patch to a SillyTavern installation.
 *
 * The patch replaces simpleGit (requires native git binary) with isomorphic-git
 * (pure JS, no binary needed) in src/endpoints/extensions.js.
 *
 * This is idempotent and safe to call after every install or auto-update.
 */
class ExtensionPatcher(private val ctx: Context) {

    private val TAG = "ExtensionPatcher"
    private val PATCH_SCRIPT_NAME = "fix-extensions-update.js"

    /**
     * Extracts the patch script from assets and runs it against the ST installation.
     *
     * @param stDir  Root directory of the SillyTavern installation
     * @return true if patch was applied (or already applied), false on failure
     */
    suspend fun applyPatch(stDir: File): Boolean = withContext(Dispatchers.IO) {
        // 1. Extract patch script from assets → filesDir/patches/
        val patchScript = extractPatchScript() ?: run {
            AppLogger.w(TAG, "Could not extract patch script from assets — extension update may not work on Android")
            return@withContext false
        }

        // 2. Verify ST target file exists
        val targetFile = File(stDir, "src/endpoints/extensions.js")
        if (!targetFile.exists()) {
            AppLogger.w(TAG, "extensions.js not found at ${targetFile.absolutePath} — skipping patch")
            return@withContext false
        }

        // 3. Run patch script via Node
        AppLogger.i(TAG, "Running extension patch script...")
        val nodeRunner = NodeRunner(ctx)
        val proc = nodeRunner.start(
            workingDir = stDir,
            args = listOf(patchScript.absolutePath),
        ) ?: run {
            AppLogger.w(TAG, "Node binary unavailable — could not apply extension patch")
            return@withContext false
        }

        val exitCode = proc.waitFor()
        return@withContext if (exitCode == 0) {
            AppLogger.i(TAG, "Extension patch applied successfully")
            true
        } else {
            AppLogger.w(TAG, "Extension patch script exited with code $exitCode — extension update may not work on Android")
            false
        }
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Copies the patch script from assets to filesDir so Node can execute it.
     * Returns the extracted File, or null on failure.
     */
    private fun extractPatchScript(): File? {
        return try {
            val destFile = File(ctx.filesDir, "patches/$PATCH_SCRIPT_NAME")
            destFile.parentFile?.mkdirs()

            ctx.assets.open("patches/$PATCH_SCRIPT_NAME").use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            AppLogger.d(TAG, "Patch script extracted to ${destFile.absolutePath}")
            destFile
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to extract patch script from assets: ${e.message}")
            null
        }
    }
}
